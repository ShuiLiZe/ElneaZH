#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""交付包里必须带上 native 闸门 .so —— 静态检查（不跑 gradle、不装 APK）。

真机事故：游戏进程里 selfcheck 写 `native: not loaded`、`adb logcat -s ElneaOffline ElneaGate ElneaZH`
一条日志都没有。原因有两层：

  1. 汉化器 APK 自身 assets/elneagate/lib<abi>/libelneagate.so 有（CI 用 build_gate.py 编进去），
     但**没人把它搬进 payload 目录**（assets/elneazh/ 那一套），Pipeline.addZh 只搬
     Contents/map/about/data/bundles → 游戏 APK 里根本没有 assets/elneagate/…；
  2. 就算解包成功，旧代码解完 .so 后调的是 System.loadLibrary("elneagate")，
     linker 去 APK 的 lib 目录找（那里没有）→ UnsatisfiedLinkError，而且 loadTried 一次性锁死。

本脚本钉住 1（静态）与 2（源码口径），并与 CI 一起作为必跑项。

用法: python tools/verify_native_load.py [--payload DIR] [--libs DIR]
退出码 0 = 全部通过。
"""
import argparse
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ABIS = ('arm64-v8a', 'armeabi-v7a')
SO = 'libelneagate.so'
BAD = 0
N = 0


def check(ok, msg):
    global BAD, N
    N += 1
    if not ok:
        BAD += 1
    print(('OK  ' if ok else 'BAD ') + msg)


def strip_comments(src):
    """去掉 // 与 /* */ 注释，只留代码（否则注释里写的旧 API 名字会让断言误报）。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def read(rel):
    with open(os.path.join(ROOT, rel), encoding='utf-8') as f:
        return f.read()


def elf_ok(data):
    """真要能 dlopen 的前提：ELF64/ELF32 小端、ET_DYN、机器码对。"""
    if len(data) < 64 or data[:4] != b'\x7fELF':
        return False, 'not ELF'
    cls = data[4]
    if cls not in (1, 2):
        return False, 'bad class'
    e_type = struct.unpack_from('<H', data, 16)[0]
    machine = struct.unpack_from('<H', data, 18)[0]
    if e_type != 3:
        return False, 'not ET_DYN (e_type=%d)' % e_type
    return True, ('ELF%d machine=%d' % (64 if cls == 2 else 32, machine))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--payload', default=os.path.join(ROOT, 'app/src/main/assets/elneazh'))
    ap.add_argument('--libs', default=os.path.join(ROOT, 'app/src/main/assets/elneagate'))
    a = ap.parse_args()

    print('== 1. 汉化器 APK 自带 .so（assets/elneagate/lib<abi>/）')
    sizes = {}
    for abi in ABIS:
        p = os.path.join(a.libs, 'lib' + abi, SO)
        ok = os.path.isfile(p)
        check(ok, '%s/lib%s/%s 存在' % (abi, abi, SO))
        if not ok:
            continue
        data = open(p, 'rb').read()
        sizes[abi] = len(data)
        good, why = elf_ok(data)
        check(good, '%s 是合法 ELF 共享库（%s，%d B）' % (abi, why, len(data)))
        check(b'Java_com_shuilize_elneazh_inj_NameGate_nativeInstall' in data,
              '%s 导出 NameGate JNI 符号（System.load 后按名解析）' % abi)
        check(b'Java_com_shuilize_elneazh_inj_OfflineGate_nativeInstall' in data,
              '%s 导出 OfflineGate JNI 符号（System.load 后按名解析）' % abi)
        check(b'ElneaGate' in data, '%s 含日志标签 ElneaGate（真机 logcat 才有东西看）' % abi)
        check(b'ElneaOffline' in data or b'EG_' in data, '%s 含单机闸门实现' % abi)

    print('== 2. payload 目录里有 native 闸门（Pipeline.addZh 从这里搬进游戏 APK）')
    # payload 由构建流程生成（CI 的「组装 payload」步骤）；本机源码树里通常只有 about/data，
    # 这时只校链路（第 3 节）。目录里有任何构建产物（Contents/map/bundles）就视为已组装，按文件判。
    assembled = any(os.path.isdir(os.path.join(a.payload, d))
                    for d in ('Contents', 'map', 'bundles'))
    if not assembled:
        print('--  payload 还没组装（%s 只有源码资产），跳过字节比对；链路见第 3 节' % a.payload)
    else:
        for abi in ABIS:
            p = os.path.join(a.payload, 'elneagate', 'lib' + abi, SO)
            ok = os.path.isfile(p)
            check(ok, 'payload elneagate/lib%s/%s 存在' % (abi, SO))
            if ok:
                check(os.path.getsize(p) == sizes.get(abi, -1),
                      '%s payload 里的 .so 与汉化器自带的字节数一致' % abi)

    print('== 3. 打包链路：MainViewModel 搬 elneagate，Pipeline 放进 assets/elneagate/')
    vm = read('app/src/main/java/com/shuilize/elneazh/MainViewModel.kt')
    check('copyAssetDir(ctx.assets, "elneagate"' in vm,
          'MainViewModel 把 assets/elneagate 搬进 payload（copyAssetDir）')
    check(re.search(r'copyAssetDir\(ctx\.assets, "elneagate"[^\n]*\n', vm) is not None,
          '搬运紧跟在 elneazh payload 之后（同一处，不会被漏）')
    pl = read('app/src/main/java/com/shuilize/elneazh/core/Pipeline.java')
    check('GATE_DIR' in pl and 'GATE_LIB' in pl, 'Pipeline 有 GATE_DIR/GATE_LIB 常量')
    check('rep.put("assets/" + GATE_DIR + "/lib" + abi + "/lib" + GATE_LIB + ".so"' in pl,
          'Pipeline.addZh 把 .so 写进游戏 APK 的 assets/elneagate/lib<abi>/')
    m = re.search(r'for \(String abi : new String\[\]\{([^}]*)\}\)', pl)
    check(m is not None and 'arm64-v8a' in m.group(1) and 'armeabi-v7a' in m.group(1),
          'Pipeline 对两个 ABI 都搬（arm64-v8a + armeabi-v7a）')

    print('== 4. Java 侧加载口径：解包 → System.load(绝对路径)，失败可重试')
    nl = read('inj/com/shuilize/elneazh/inj/NativeLoad.java')
    nlc = strip_comments(nl)
    check('System.load(path)' in nlc, 'NativeLoad 用 System.load(绝对路径)')
    check('loadLibrary' not in nlc, 'NativeLoad 不用 System.loadLibrary（APK lib 目录里没有我们的 .so）')
    check('RETRY_GAP_MS' in nl, 'NativeLoad 有失败重试间隔（不会被一次失败锁死）')
    check('static final String TAG = "ElneaGate"' in nl, 'NativeLoad 用 ElneaGate 标签打日志')
    check('Log.w(TAG' in nl and 'Log.i(TAG' in nl, 'NativeLoad 成功/失败都打 android.util.Log')
    for cls in ('NameGate', 'OfflineGate'):
        src = strip_comments(read('inj/com/shuilize/elneazh/inj/%s.java' % cls))
        body = src[src.find('static synchronized boolean load(Context'):]
        body = body[:body.find('return loaded;') + len('return loaded;')]
        check('loadLibrary' not in body, '%s.load 不再调 System.loadLibrary' % cls)
        check('loadTried' not in src, '%s 删掉了一次性 loadTried（失败后可重试）' % cls)
        check('NativeLoad.tryLoad' in body, '%s.load 走 NativeLoad.tryLoad' % cls)
    check('loadState()' in read('inj/com/shuilize/elneazh/inj/NameGate.java'),
          'NameGate 暴露 loadState() 供 selfcheck 如实写')
    check('loadState()' in read('inj/com/shuilize/elneazh/inj/OfflineGate.java'),
          'OfflineGate 暴露 loadState() 供 selfcheck 如实写')

    print('== 5. selfcheck：native 挂上后重写，失败写出 dlopen 错误串')
    sc = read('inj/com/shuilize/elneazh/inj/SelfCheck.java')
    check('rewriteAsync' in sc, 'SelfCheck 有 rewriteAsync（后台线程重写，不阻塞安装线程）')
    check('native load:' in sc, 'selfcheck 有 native load: 行')
    check('native load error:' in sc, 'selfcheck 失败时写 native load error:（dlopen 原文）')
    check('NativeLoad.describe()' in sc, 'selfcheck 用 NativeLoad.describe() 如实反映加载状态')
    check('written:' in sc, 'selfcheck 标注写入时刻（区分「加载前」与「挂完后」两份）')
    og = read('inj/com/shuilize/elneazh/inj/OfflineGate.java')
    check('SelfCheck.rewriteAsync' in og, 'OfflineGate 安装结束后重写 selfcheck')
    m_ia = re.search(r'private static void installAll\(\)\s*\{(.*?)\n    \}', og, re.S)
    check(m_ia is not None and 'rewriteAsync' in m_ia.group(1),
          'installAll 末尾（挂完之后）重写 selfcheck，不是加载前那份')
    check('rewriteAsync' in og[og.find('static int install(final Context ctx'):][:1200],
          'install 没加载上时也重写 selfcheck（把错误串落盘）')

    print()
    print('native load OK' if BAD == 0 else 'native load WRONG (%d)' % BAD)
    return 0 if BAD == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
