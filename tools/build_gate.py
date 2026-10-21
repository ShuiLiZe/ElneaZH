#!/usr/bin/env python3
"""把 native/elneagate 编成 libelneagate.so，并放进 app/src/main/assets/elneagate/。

为什么编进 assets 而不是 jniLibs：我们的 APK 是「汉化器」本体，重打包器（Pipeline.addZh）只往
base APK 里塞 libil2cpp.so 与 assets/elneazh/*。把 .so 放在 assets 下，游戏进程启动时由
ZhInit/NameGate 解到私有目录再 System.load，不依赖游戏 APK 的 lib 目录如何被签名/对齐。

用法：
    python tools/build_gate.py                     # 自动找 NDK，编两个 ABI 并复制到 assets
    python tools/build_gate.py --ndk D:\\ndk        # 指定 NDK
    python tools/build_gate.py --out build/gate    # 只产出，不复制进 app

NDK 查找顺序：--ndk / ANDROID_NDK_HOME / ANDROID_NDK_ROOT / %LOCALAPPDATA%\\Android\\Sdk\\ndk\\*
/ <repo>/.work/dl/android-ndk-*（本地缓存）。
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys

ABIS = [
    ("aarch64-linux-android24", "arm64-v8a"),
    ("armv7a-linux-androideabi24", "armeabi-v7a"),
]
SRC = ["native/elneagate/elneagate.c",
       # JNI_OnLoad（只做 O(1)：记 vm + 起后台线程）与阶段日志计数器
       "native/elneagate/eg_boot.c",
       # 崩溃保护：crash guard 允许标记 / 后台安装窗口上限
       "native/elneagate/eg_guard.c",
       "native/elneagate/eg_names.c",
       "native/elneagate/eg_sym.c",
       "native/elneagate/eg_offline.c",
       # 方案 B 第一步：联网录制（请求/回包明文 + 进程内方法表）
       "native/elneagate/eg_rec.c",
       "native/elneagate/vendor/And64InlineHook.cpp"]
HDRS = ["native/elneagate", "native/elneagate/vendor"]


def find_ndk(explicit=None):
    cands = []
    if explicit:
        cands.append(explicit)
    for k in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME"):
        v = os.environ.get(k)
        if v:
            cands.append(v)
    local = os.environ.get("LOCALAPPDATA")
    if local:
        cands.extend(sorted(glob.glob(os.path.join(local, "Android", "Sdk", "ndk", "*")), reverse=True))
    cands.extend(sorted(glob.glob(os.path.join(".work", "dl", "android-ndk-*")), reverse=True))
    for c in cands:
        clang = os.path.join(c, "toolchains", "llvm", "prebuilt", _host_tag(), "bin", "clang.exe" if os.name == "nt" else "clang")
        if os.path.isfile(clang):
            return c, clang
    return None, None


def _host_tag():
    if os.name == "nt":
        return "windows-x86_64"
    if sys.platform == "darwin":
        return "darwin-x86_64"
    return "linux-x86_64"


def build(ndk, clang, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    made = {}
    for target, abi in ABIS:
        obj_dir = os.path.join(".work", "gateobj", abi)
        os.makedirs(obj_dir, exist_ok=True)
        objs = []
        for src in SRC:
            obj = os.path.join(obj_dir, os.path.basename(src) + ".o")
            cmd = [clang, "--target=" + target, "-O2", "-fPIC", "-fvisibility=hidden",
                   "-Wall", "-Wextra", "-Wno-unused-parameter"]
            for h in HDRS:
                cmd += ["-I", h]
            cmd += ["-c", src, "-o", obj]
            print("  " + " ".join(cmd))
            subprocess.check_call(cmd)
            objs.append(obj)
        so = os.path.join(out_dir, abi, "libelneagate.so")
        os.makedirs(os.path.dirname(so), exist_ok=True)
        # --whole-archive: 让 .init_array 里的 A64HookFunction 注册表不被 --gc-sections 丢掉
        cmd = [clang, "--target=" + target, "-shared", "-o", so,
               "-Wl,--whole-archive", "-Wl,--no-whole-archive"] + objs + \
              ["-Wl,--gc-sections", "-Wl,--exclude-libs,ALL", "-lz", "-llog", "-ldl", "-lm"]
        print("  " + " ".join(cmd))
        subprocess.check_call(cmd)
        made[abi] = so
        print("%-12s %-34s %d B" % (abi, so, os.path.getsize(so)))
    return made


def strip_so(ndk, made):
    strip = os.path.join(ndk, "toolchains", "llvm", "prebuilt", _host_tag(), "bin",
                         "llvm-strip.exe" if os.name == "nt" else "llvm-strip")
    if not os.path.isfile(strip):
        return
    for abi, so in made.items():
        subprocess.check_call([strip, "--strip-unneeded", so])
        print("stripped %-12s %d B" % (abi, os.path.getsize(so)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ndk", default=None)
    ap.add_argument("--out", default=".work/gate")
    ap.add_argument("--dest", default="app/src/main/assets/elneagate")
    ap.add_argument("--no-copy", action="store_true")
    args = ap.parse_args()

    ndk, clang = find_ndk(args.ndk)
    if not clang:
        sys.exit("NDK not found (set ANDROID_NDK_HOME or pass --ndk)")
    print("ndk   :", ndk)
    print("clang :", clang)
    made = build(ndk, clang, args.out)
    strip_so(ndk, made)
    if not args.no_copy:
        for abi, so in made.items():
            dst = os.path.join(args.dest, "lib" + abi)
            os.makedirs(dst, exist_ok=True)
            shutil.copy2(so, os.path.join(dst, "libelneagate.so"))
            print("copied -> %s" % os.path.join(dst, "libelneagate.so"))
    print("gate build OK")


if __name__ == "__main__":
    main()
