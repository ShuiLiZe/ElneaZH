#!/usr/bin/env python3
"""上传前名字闸门（native/elneagate）的离线验证。

做三件事：
  1. 语法/汇编检查：确认 .so 里我们自己的符号、目标常量、日志标签都在；
  2. 夹具一致性：C 单测（native/elneagate/gate_test.c）与 Java 侧 UploadNamesSample 以及本脚本
     用的是同一串 hex，任何一边改了另一边必须跟着改（本脚本会读 C 文件里的 RAW_HEX/EXPECT_HEX
     并逐字节复算）；
  3. 端到端复刻：用 Python 复刻 eg_filter_json 的 JSON 层（找 "key":"base64(zlib)" 并原位替换），
     验证「请求明文整体不会被改动，只有存档块里的中文名变拼音」。

用法：
    python tools/verify_gate.py                     # 全部检查
    python tools/verify_gate.py --lib .work/gate    # 额外检查 .so 符号
退出码 0 = 通过。
"""
import argparse
import base64
import os
import re
import struct
import subprocess
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
C_TEST = os.path.join(ROOT, "native", "elneagate", "gate_test.c")
GATE_C = os.path.join(ROOT, "native", "elneagate", "elneagate.c")

# 与 C 测试 ZH[]/ASCII[] 一致（顺序无关，按内容匹配）
PAIRS = {
    "凯登": "KaiEn",
    "蕾娜塔": "LeiNaTa",
    "约瑟夫": "YueSeFu",
    "铁蛋": "TieDan",
    "孙": "Sun",
}

fails = []


def check(ok, what):
    print("  [%s] %s" % ("OK" if ok else "FAIL", what))
    if not ok:
        fails.append(what)


# ---------------------------------------------------------------- 核心复刻

def slot_len(b, i):
    """i 处若是「0x01 + len(1..255 可打印 UTF-8)」返回 len，否则 -1。

    上限是 255（长度字节的极限），不是 64：拼音比汉字长，20 个汉字的名字就有 60 字节，
    放宽到 255 才不漏长名字；替换后长度字节同步改写。
    """
    if i + 2 > len(b) or b[i] != 0x01:
        return -1
    n = b[i + 1]
    if n < 1 or n > 255 or i + 2 + n > len(b):
        return -1
    seg = b[i + 2:i + 2 + n]
    if any(c < 0x20 or c == 0x7F for c in seg):
        return -1
    try:
        if seg.decode("utf-8").encode("utf-8") != seg:
            return -1
    except UnicodeError:
        return -1
    return n


def has_han(s):
    for ch in s:
        cp = ord(ch)
        if (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF
                or 0xF900 <= cp <= 0xFAFF or 0x20000 <= cp <= 0x2FA1F):
            return True
    return False


def filter_raw(raw, pairs):
    out = bytearray()
    i = 0
    hits = 0
    while i < len(raw):
        n = slot_len(raw, i)
        if n < 0:
            out.append(raw[i])
            i += 1
            continue
        name = raw[i + 2:i + 2 + n].decode("utf-8")
        asc = pairs.get(name)
        if asc and asc.isascii():
            nb = asc.encode()
            out += b"\x01" + bytes([len(nb)]) + nb
            hits += 1
        else:
            out += raw[i:i + 2 + n]
        i += 2 + n
    return bytes(out), hits


def filter_zlib(packed, pairs):
    if len(packed) < 2 or packed[0] != 0x78:
        return None, 0
    try:
        raw = zlib.decompress(packed)
    except zlib.error:
        return None, 0
    fixed, hits = filter_raw(raw, pairs)
    if hits == 0:
        return None, 0
    return zlib.compress(fixed, 9), hits


def looks_zlib_b64(v):
    if len(v) < 24 or len(v) % 4:
        return False
    for c in v:
        if not (c.isalnum() or c in "+/=_-"):
            return False
    try:
        head = base64.b64decode(v[:4].replace("-", "+").replace("_", "/"))
    except Exception:
        return False
    return bool(head) and head[0] == 0x78


def rewrite_nested(lit, pairs):
    """lit 是 JSON 字符串字面量内容（已含 \\" 转义）；
    把内层 \\"key\\":\\"<base64>\\" 的 value 原位换掉。返回新内容，无改动返回 None。"""
    q = chr(92) + '"'      # 反斜杠 + 引号
    out = []
    i = 0
    n = len(lit)
    touched = False
    while i < n:
        k = lit.find(q, i)
        if k < 0:
            out.append(lit[i:])
            break
        out.append(lit[i:k + 2])
        start = k + 2
        e = -1
        scan = start
        while True:
            c = lit.find(q, scan)
            if c < 0:
                break
            bs = 0
            b = c - 1
            while b >= start and lit[b] == chr(92):
                bs += 1
                b -= 1
            if bs % 2 == 0:
                e = c
                break
            scan = c + 2
        if e < 0:
            out.append(lit[k:])
            break
        val = lit[start:e]
        rep = val
        if looks_zlib_b64(val):
            packed = base64.b64decode(val.replace("-", "+").replace("_", "/"))
            fixed, hits = filter_zlib(packed, pairs)
            if hits:
                cand = base64.b64encode(fixed).decode()
                if len(cand) <= len(val):
                    rep = cand.replace("/", chr(92) + "/")
                    touched = True
        out.append(rep)
        out.append(q)
        i = e + 2
    return "".join(out) if touched else None


def filter_json(plain, pairs):
    """与 native eg_filter_json 同构：扫明文里每个 JSON 字符串字面量，值以 ':' 打头的，
    把字面量内容交给 rewrite_nested。返回 (新明文, 是否有改动)。"""
    n = len(plain)
    out = []
    copied = 0
    i = 0
    touched = False
    while i < n:
        if plain[i] != '"':
            i += 1
            continue
        j = i + 1
        close = -1
        while j < n:
            c = plain[j]
            if c == chr(92):
                if j + 1 < n and plain[j + 1] in ('"', chr(92)):
                    j += 2
                    continue
                j += 1
                continue
            if c == '"':
                close = j
                break
            j += 1
        if close < 0:
            break
        k = i - 1
        while k >= 0 and plain[k] in " \t\r\n":
            k -= 1
        if k >= 0 and plain[k] == ":":
            nested = rewrite_nested(plain[i + 1:close], pairs)
            if nested is not None:
                out.append(plain[copied:i + 1])
                out.append(nested)
                copied = close
                touched = True
        i = close + 1
    if not touched:
        return plain, 0
    out.append(plain[copied:])
    return "".join(out), 1


def c_json_layer(raw, want, pairs):
    """把 C 侧 eg_filter_json 的语义在 Python 里跑一遍：
    输入"data"字段里嵌 \\"ws_profiles\\":\\"<b64>\\" 形态的明文，返回 (新明文, 是否改动)。
    与 eg_names.c 的实现一一对应（eg_filter_json → eg_rewrite_nested → eg_filter_one → eg_filter_zlib）。"""
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode()
    inner = '{\\"ws_profiles\\":\\"' + b64 + '\\",\\"uuid\\":\\"abc123\\"}'
    plain = '{"data":"' + inner + '","lang":"en"}'
    fixed, hits = filter_json(plain, pairs)
    return plain, fixed, hits


def c_selfcheck(raw, want, pairs):
    """检查 3b：C 侧端到端语义（含 JSON 层解压回 EXPECT）。"""
    plain, fixed, hits = c_json_layer(raw, want, pairs)
    print("== 3b. JSON 层端到端（native eg_filter_json 同构）")
    check(fixed != plain, "含存档块的明文被改写")
    check("abc123" in fixed, "uuid 等非存档字段原样保留")
    check(hits > 0, "hits > 0（实际 %d）" % hits)
    m = re.search(r'ws_profiles\\":\\"([A-Za-z0-9+/=]+)', fixed)
    check(m is not None, "能取回改写后的存档块")
    if m:
        after = zlib.decompress(base64.b64decode(m.group(1)))
        check(after == want, "改后存档块解压 == EXPECT_HEX")


# ---------------------------------------------------------------- 检查项

def read_c_fixture():
    src = open(C_TEST, encoding="utf-8").read()
    raw = re.search(r'static const char \*RAW_HEX =\s*"([0-9a-fA-F]+)";', src)
    want = re.search(r'static const char \*EXPECT_HEX =\s*"([0-9a-fA-F]+)";', src)
    if not raw or not want:
        return None, None
    return bytes.fromhex(raw.group(1)), bytes.fromhex(want.group(1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lib", default=".work/gate", help="build_gate.py 的输出目录（可不存在）")
    args = ap.parse_args()

    print("== 1. C 夹具（native/elneagate/gate_test.c）")
    raw, want = read_c_fixture()
    check(raw is not None and want is not None, "RAW_HEX / EXPECT_HEX 存在且是合法 hex")
    if raw and want:
        got, hits = filter_raw(raw, PAIRS)
        check(hits == len(PAIRS), "识别并替换 %d 个中文名（实际 %d）" % (len(PAIRS), hits))
        check(got == want, "过滤结果与 EXPECT_HEX 逐字节一致")
        got2, hits2 = filter_raw(got, PAIRS)
        check(hits2 == 0 and got2 == got, "第二次过滤无改动（幂等）")

    print("== 2. zlib + base64 路径")
    if raw and want:
        packed = zlib.compress(want, 9)
        fixed, hits = filter_zlib(zlib.compress(raw, 9), PAIRS)
        check(hits == len(PAIRS), "压缩态里同样替换 %d 个名字" % len(PAIRS))
        check(fixed is not None and zlib.decompress(fixed) == want,
              "压回再解开 == EXPECT_HEX（其余字节零改动）")
        b64 = base64.b64encode(zlib.compress(raw, 9)).decode()
        check(looks_zlib_b64(b64), "base64(zlib) 识别正确")

    print("== 3. JSON 层（只动存档块，不动其它字段）")
    if raw and want:
        b64 = base64.b64encode(zlib.compress(raw, 9)).decode()
        b64 = base64.b64encode(zlib.compress(raw, 9)).decode()
        inner = '{\\"ws_profiles\\":\\"' + b64 + '\\",\\"uuid\\":\\"abc123\\"}'
        plain = '{"data":"' + inner + '","lang":"en"}'
        fixed, _ = filter_json(plain, PAIRS)
        check("abc123" in fixed, "uuid 等非存档字段原样保留")
        check('"lang":"en"' in fixed, "语种字段原样保留")
        # 从改后的明文里把 base64 抠出来，解压比对
        m = re.search(r'ws_profiles\\":\\"([A-Za-z0-9+/=]+)', plain)
        m2 = re.search(r'ws_profiles\\":\\"([A-Za-z0-9+/=]+)', fixed)
        check(m2 is not None and m is not None, "能在原/改后 JSON 里找回 ws_profiles 值")
        if m and m2:
            before = zlib.decompress(base64.b64decode(m.group(1)))
            after = zlib.decompress(base64.b64decode(m2.group(1)))
            check(before == raw, "改前存档块解压 == RAW_HEX")
            check(after == want, "改后存档块解压 == EXPECT_HEX")
            check(m2.group(1) != m.group(1), "存档块确实被改写")
        check(any(c in fixed for c in "铁蛋凯登孙") is False, "改后的明文里不再有这些中文名")

    print("== 4. 纯 ASCII 请求体零改动")
    plain = '{"acc":"AAAA","lang":"en","ws_profiles":"AAAABBBBCCCCDDDDEEEEFFFF"}'
    out, _ = filter_json(plain, PAIRS)
    check(out == plain, "无中文时原样返回（引用相同语义）")

    c_selfcheck(raw, want, PAIRS)

    print("== 5. .so（%s）" % args.lib)
    if os.path.isdir(args.lib):
        for abi in ("arm64-v8a", "armeabi-v7a"):
            so = os.path.join(args.lib, abi, "libelneagate.so")
            if not os.path.isfile(so):
                check(False, "%s 存在" % so)
                continue
            data = open(so, "rb").read()
            check(data[:4] == b"\x7fELF", "%s 是 ELF" % abi)
            check(len(data) < 200 * 1024, "%s 体积 < 200KB（%d B）" % (abi, len(data)))
            check(b"ElneaGate" in data, "%s 含日志标签 ElneaGate" % abi)
            check(b"ElneaOffline" in data, "%s 含单机闸门日志标签 ElneaOffline" % abi)
            # 单机闸门靠 JNI 入口进 native，符号被 strip 掉就等于没装
            for sym in (b"OfflineGate_nativeInstall", b"OfflineGate_nativePush",
                        b"OfflineGate_nativeStats", b"OfflineGate_nativeResetStats",
                        b"OfflineGate_nativeResolvable", b"OfflineGate_nativeClassPresent",
                        b"OfflineGate_nativeProbe", b"OfflineGate_nativeOff",
                        b"OfflineGate_nativeEncState"):
                check(sym in data, "%s 导出 JNI 入口 %s" % (abi, sym.decode()))
            # 六族钩子的日志：真机上靠这几条判断「哪一族挂上了」
            # 只在 arm64-v8a 上检查 —— armv7 没有 hook 引擎，eg_dispatch 会被优化掉。
            if abi == "arm64-v8a":
                for tag in (b"skip entry", b"drop request", b"panel not created",
                            b"blocked (entry hidden=", b"hidden on refresh", b"no mask for"):
                    check(tag in data, "%s 含钩子日志 %r" % (abi, tag.decode()))
                check(b"resolve miss" in data, "%s 含解析失败日志" % abi)
                check(b"install: target=" in data, "%s 含安装汇总日志" % abi)
    else:
        print("  (跳过：%s 不存在，先跑 tools/build_gate.py)" % args.lib)

    print()
    if fails:
        print("RESULT: FAIL (%d)" % len(fails))
        for f in fails:
            print("  - " + f)
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
