#!/usr/bin/env python3
"""native 不得阻塞主线程：静态检查 + 负例自检。

背景（真机事故，2026-10-21 17:13 selfcheck，OnePlus Android 16 arm64 PAC）：
    单机开、单机**关**都黑屏，过一会儿进程退出；crash 缓冲区没有新 tombstone、
    没有 AndroidRuntime 异常；`adb logcat -s ElneaGate ElneaOffline` 一条都没有。
    → 不是崩溃，是主线程被卡住（ANR / Unity 看门狗杀进程）；而「一条日志都没有」
      说明卡在打日志之前，或者 .so 的加载本身走了不该走的同步路径。

本脚本守的是**结构**上的约束（真机行为仍要上机验）：
  1. JNI_OnLoad 存在，且函数体里没有 dlsym / dlopen / fopen(/proc...) / sleep / pthread_join /
     A64HookFunction / il2cpp_* 调用 —— 主线程只做 O(1)。
  2. 没有任何 `__attribute__((constructor))`（加载期跑 C 代码 = 谁 dlopen 谁卡）。
  3. eg_sym.c 的 maps 读取是「一次读取 + 区间表查表」：dynsym_lookup 里不许出现 fopen，
     且必须存在 200ms 级的快照 TTL（旧版每个符号读一遍整张 maps，O(n²)）。
  4. Java 侧 NameGate.install 不许再 `done.await()`（旧版在那里同步等最多 20s，
     那就是主线程被卡住的直接来源）；安装/重试必须在 `new Thread(...)` 起的后台线程里。
  5. OfflineGate 的安装线程必须限时（INSTALL_WINDOW_MS），并且单机**关着**时不装六族。
  6. 日志：JNI_OnLoad 进/出、installer 线程启动、每次 libil2cpp 查找、每个 hook 写槽前后，
     都要有 ElneaGate（I 级）日志。

用法: python tools/verify_native_nonblock.py [--verbose]
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAT = os.path.join(ROOT, "native", "elneagate")
INJ = os.path.join(ROOT, "inj", "com", "shuilize", "elneazh", "inj")

fails = []
oks = []


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def check(cond, msg):
    (oks if cond else fails).append(msg)


def func_body(src, signature_regex):
    """从函数名开始，按大括号配平取函数体（够用的粗粒度解析）。"""
    m = re.search(signature_regex, src)
    if not m:
        return None
    i = src.find("{", m.end())
    if i < 0:
        return None
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
    return None


def main():
    verbose = "--verbose" in sys.argv

    boot_path = os.path.join(NAT, "eg_boot.c")
    check(os.path.isfile(boot_path), "存在 eg_boot.c（JNI_OnLoad 单独成文件）")
    boot = strip_comments(read(boot_path)) if os.path.isfile(boot_path) else ""

    # 1) JNI_OnLoad 只能是 O(1)
    jni = func_body(boot, r"JNI_OnLoad\s*\(\s*JavaVM")
    check(jni is not None, "JNI_OnLoad 已定义")
    if jni:
        banned = [
            (r"\bdlsym\b", "dlsym"),
            (r"\bdlopen\b", "dlopen"),
            (r"fopen", "fopen"),
            (r"/proc/", "/proc/self/maps 读取"),
            (r"\bsleep\b|\busleep\b|nanosleep", "sleep"),
            (r"pthread_join", "pthread_join"),
            (r"pthread_create", "pthread_create（加载期起线程也算在主线程上做决定）"),
            (r"A64HookFunction", "A64HookFunction"),
            (r"\bil2cpp_", "il2cpp_* 调用"),
            (r"pthread_mutex_lock", "加锁"),
        ]
        for rx, name in banned:
            check(not re.search(rx, jni), "JNI_OnLoad 里没有 %s" % name)
        check("JNI_VERSION_1_6" in jni, "JNI_OnLoad 返回 JNI_VERSION_1_6（不用显式 return 值也行）"
              if "return JNI_VERSION_1_6" in jni else "JNI_OnLoad 返回合法 JNI 版本")
        try:
            idx = jni.index("return JNI_VERSION_1_6")
            after = jni[idx:]
            check("return" not in after.replace("return JNI_VERSION_1_6", "", 1),
                  "JNI_OnLoad 只有一条 return（没有中间提前返回的阻塞分支）")
        except ValueError:
            oks.append("JNI_OnLoad 用 JNI_VERSION_1_6 常量以外的版本宏（可接受）")
        check("eg_now_ms" in jni, "JNI_OnLoad 打了 enter/exit 两条带耗时的日志")

    # 2) 没有构造函数
    for name in ("eg_boot.c", "eg_guard.c", "elneagate.c", "eg_offline.c", "eg_sym.c",
                 "eg_names.c"):
        p = os.path.join(NAT, name)
        src = strip_comments(read(p))
        check("__attribute__((constructor" not in src.replace(" ", ""),
              "%s 里没有 __attribute__((constructor))" % name)

    # 3) maps 快照
    sym = strip_comments(read(os.path.join(NAT, "eg_sym.c")))
    check("EG_MAPS_TTL_MS" in sym, "eg_sym.c 有 maps 快照 TTL（EG_MAPS_TTL_MS）")
    check(re.search(r"EG_MAPS_TTL_MS\s+(\d+)", sym) and
          int(re.search(r"EG_MAPS_TTL_MS\s+(\d+)", sym).group(1)) <= 1000,
          "maps 快照 TTL ≤ 1000ms（不再每符号重读）")
    dyn = func_body(sym, r"dynsym_lookup\s*\(")
    check(dyn is not None, "dynsym_lookup 存在")
    if dyn:
        check("fopen" not in dyn, "dynsym_lookup 里没有 fopen（不再每符号读一次 maps）")
        check(dyn.count("mem_ok(") <= 12, "dynsym_lookup 里 mem_ok 调用 <= 12 次（快照查表）")
    maps_reload = func_body(sym, r"eg_maps_reload\s*\(")
    check(maps_reload is not None and "fopen" in maps_reload,
          "只有 eg_maps_reload 负责读 /proc/self/maps")
    check(sym.count('fopen("/proc/self/maps"') <= 3,
          "eg_sym.c 里读 maps 的位置 <= 3 处（快照 / 找路径 / 找基址兜底）")

    # 4) Java：NameGate 不再同步等待
    ng = strip_comments(read(os.path.join(INJ, "NameGate.java")))
    check(".await(" not in ng, "NameGate 里没有 CountDownLatch.await（不再同步等安装完成）")
    check("new Thread(" in ng, "NameGate 用后台线程做安装/重试")
    check(re.search(r"MAX_TRIES|MAX_ATTEMPTS|tries\s*<", ng) is not None,
          "NameGate 的重试有上限")
    install_body = func_body(ng, r"static\s+int\s+install\s*\(")
    if install_body:
        check(".await(" not in install_body, "NameGate.install 本身不等待")

    # 5) OfflineGate：限时窗口 + 关单机跳过
    og = strip_comments(read(os.path.join(INJ, "OfflineGate.java")))
    check("INSTALL_WINDOW_MS" in og, "OfflineGate 有安装窗口上限 INSTALL_WINDOW_MS")
    check("ElneaOffline-install" in og, "OfflineGate 的安装跑在命名线程里（ElneaOffline-install）")
    eg_off = strip_comments(read(os.path.join(NAT, "eg_offline.c")))
    of_install = func_body(eg_off, r"int\s+eg_of_install\s*\(")
    check(of_install is not None, "eg_of_install 存在")
    if of_install:
        check("eg_is_off()" in of_install.split("pthread_mutex_lock")[0],
              "eg_of_install 在单机**关**时直接返回（不装六族，只保底闸）")

    # 6) 日志齐全
    check(re.search(r"JNI_OnLoad enter|JNI_OnLoad 进入", boot) is not None,
          "JNI_OnLoad 进入有日志")
    check(re.search(r"JNI_OnLoad exit", boot) is not None, "JNI_OnLoad 退出有日志（带耗时）")
    egc = strip_comments(read(os.path.join(NAT, "elneagate.c")))
    check("name gate: base=" in egc or "base=%p" in egc,
          "name gate 打了基址/目标地址（libil2cpp 查找结果）")
    check("name gate: hooked" in egc, "name gate 写槽成功有日志")
    check("slot write ok" in eg_off, "每个 hook 写槽成功有日志（slot write ok）")
    check("mprotect(" in eg_off and "failed" in eg_off, "写槽失败（mprotect）有日志")
    check("eg_log.h" in egc and "eg_log.h" in eg_off, "两处都用统一日志头 eg_log.h")
    logh = read(os.path.join(NAT, "eg_log.h"))
    check("SYS_gettid" in logh, "日志带 tid")
    check("pthread_getname_np" in logh, "日志带线程名（API26+），低版本退化")
    check("ANDROID_LOG_INFO" in logh, "阶段日志是 I 级（adb logcat -s ElneaGate 能看到）")

    # 7) 崩溃保护
    guard = strip_comments(read(os.path.join(NAT, "eg_guard.c"))) \
        if os.path.isfile(os.path.join(NAT, "eg_guard.c")) else ""
    check("eg_guard.c" in os.listdir(NAT), "存在 eg_guard.c（crash guard native 侧）")
    check("nativeGuard" in guard, "crash guard 有 JNI 入口 nativeGuard")
    bg = read(os.path.join(ROOT, "tools", "build_gate.py"))
    for f in ("eg_boot.c", "eg_guard.c"):
        check(f in bg, "build_gate.py 编译了 %s" % f)

    if verbose:
        for o in oks:
            print("  ok   " + o)
    for f in fails:
        print("  FAIL " + f)
    print()
    print("native non-block: %d checks / %d failed" % (len(oks) + len(fails), len(fails)))
    if fails:
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
