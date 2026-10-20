#!/usr/bin/env python3
"""Patch IL2CPP global-metadata.dat (Unity 6000.3, metadata v39) string literals.

usage: python tools/metadata_zh.py <orig global-metadata.dat> <out> [--tr translations/metadata_literals.json] [--dump literals.json]

v39 header: magic, version, then 31 x (offset, size, count) int32 triples (header = 380 bytes).
Section 0 = string literal table: one uint32 dataIndex per literal; literal i = data[idx[i]:idx[i+1]] (UTF-8, no length field).
Section 1 = string literal data.
Changing a literal's length: rewrite section 1, shift later idx, and add delta to every section offset after section 1.

translations/metadata_literals.json: {"<original literal, exact>": "<中文>"}  (matched by exact text, all occurrences)
Special: NAME_WHITELIST (the in-game allowed-name characters) is left exactly as shipped — no hanzi appended,
because Chinese names get written into the save and uploaded via read.php / pvp.php / friend lists.
NPC name tables (NameMale_en / NameFemale_en / NameFamily_en) are never translated.
"""
import struct, json, argparse, os

# 原版「允许取名」字符白名单。**保持出厂原样**：汉字不再追加进去。
# 中文名会写进存档并随 read.php / pvp.php / 好友列表上传，服务器侧一眼可疑 —— 防封优先。
NAME_WHITELIST = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ' 0123456789-!:"

# NPC 名字表必须保持原版英文（它们会被写进存档再上传）
NO_TRANSLATE = ('NameMale_en', 'NameFemale_en', 'NameFamily_en')


def gb2312_hanzi():
    out = []
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                out.append(bytes([hi, lo]).decode('gb2312'))
            except UnicodeDecodeError:
                pass
    return ''.join(out)


def load(b):
    assert struct.unpack_from('<I', b, 0)[0] == 0xFAB11BAF, 'not il2cpp metadata'
    ver = struct.unpack_from('<i', b, 4)[0]
    assert ver == 39, f'metadata v{ver}, tool written for v39'
    secs = [list(struct.unpack_from('<3i', b, 8 + 12 * i)) for i in range(31)]
    (lo, ls, lc), (do, ds, _) = secs[0], secs[1]
    idx = list(struct.unpack_from('<%dI' % lc, b, lo))
    lits = [b[do + idx[i]: do + (idx[i + 1] if i + 1 < lc else ds)] for i in range(lc)]
    return secs, lits


def save(b, secs, lits):
    (lo, ls, lc), (do, ds, dc) = secs[0], secs[1]
    data = bytearray(); idx = []
    for s in lits:
        idx.append(len(data)); data += s
    delta = len(data) - ds
    delta += (-delta) % 16           # later sections keep their original alignment; the gap is zero-filled
    out = bytearray(b[:8])
    for i, (o, sz, c) in enumerate(secs):
        if i == 1:
            sz = len(data)
        elif o > do:
            o += delta
        out += struct.pack('<3i', o, sz, c)
    out += b[len(out):lo]
    out += struct.pack('<%dI' % lc, *idx)
    out += b[lo + 4 * lc:do]
    out += data + b'\0' * (delta - (len(data) - ds))
    out += b[do + ds:]
    return bytes(out), delta


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('orig'); ap.add_argument('out')
    ap.add_argument('--tr', default='translations/metadata_literals.json')
    ap.add_argument('--dump')
    a = ap.parse_args()
    b = open(a.orig, 'rb').read()
    secs, lits = load(b)
    if a.dump:
        json.dump([s.decode('utf-8', 'replace') for s in lits], open(a.dump, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    tr = json.load(open(a.tr, encoding='utf-8')) if os.path.exists(a.tr) else {}
    tr = {k: v for k, v in tr.items() if not k.startswith('//')}
    n = 0; wl = 0; skip = 0
    for i, s in enumerate(lits):
        t = s.decode('utf-8', 'replace')
        if t == NAME_WHITELIST:
            wl += 1                       # 只计数，不改写（保持出厂字符集）
        elif any(t.startswith(k) or t.endswith(k) for k in NO_TRANSLATE):
            skip += 1; continue           # 名字表不翻译
        elif t in tr and tr[t]:
            lits[i] = tr[t].encode('utf-8'); n += 1
    new, delta = save(b, secs, lits)
    # self-check: reparse and compare
    secs2, lits2 = load(new)
    assert lits2 == lits
    for i in range(2, 31):
        o, sz, _ = secs[i]; o2, sz2, _ = secs2[i]
        assert new[o2:o2 + sz2] == b[o:o + sz], f'section {i} moved wrong'
    open(a.out, 'wb').write(new)
    print(f'metadata: {n} literals translated, name whitelist kept as shipped (x{wl}), skipped name tables {skip}, size delta {delta}')


if __name__ == '__main__':
    main()
