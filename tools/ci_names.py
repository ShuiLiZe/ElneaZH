"""检查 data.unity3d 里的 NPC 名字表是否仍是原版英文（无 CJK）。

名字会写进存档并随 read.php / pvp.php / 好友列表 / recov2.php 上传，
服务器侧看到中文名是封号判据 —— 所以这三张表必须是纯 ASCII。

用法：
    python tools/ci_names.py <data.unity3d> [--orig <base/data.unity3d>]

带 --orig 时逐字比对，必须与原始完全一致（防止有人改动空格/换行）。
"""
import argparse
import sys

import UnityPy

DEFAULT_NAMES = ['NameMale_en', 'NameFemale_en', 'NameFamily_en']

# CJK 精确范围（与 Java 侧 Pinyin.isHan 保持一致）
CJK_RANGES = [
    (0x2E80, 0x9FFF),    # CJK 部首补充 + 统一表意文字
    (0xF900, 0xFAFF),    # 兼容表意文字
    (0xFF00, 0xFFEF),    # 全角/半角形式
    (0x3000, 0x303F),    # CJK 标点
]


def is_cjk(ch):
    o = ord(ch)
    return any(lo <= o <= hi for lo, hi in CJK_RANGES)


def read_tables(path, names):
    env = UnityPy.load(path)
    out = {}
    for o in env.objects:
        if o.type.name != 'TextAsset':
            continue
        d = o.read()
        if d.m_Name not in names:
            continue
        s = d.m_Script
        out[d.m_Name] = s.encode('utf-8', 'surrogateescape') if isinstance(s, str) else bytes(s)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('data')
    ap.add_argument('--orig')
    ap.add_argument('--names', nargs='*', default=DEFAULT_NAMES)
    a = ap.parse_args()

    cur = read_tables(a.data, set(a.names))
    orig = read_tables(a.orig, set(a.names)) if a.orig else {}
    missing = [n for n in a.names if n not in cur]
    if missing:
        print(f'FAIL missing TextAsset: {missing}')
        return 1

    bad = 0
    for n in a.names:
        raw = cur[n]
        text = raw.decode('utf-8', 'replace')
        lines = [x for x in text.replace('\r\n', '\n').split('\n') if x.strip()]
        cjk = sum(1 for ch in text if is_cjk(ch))
        same = ''
        if orig:
            ok = raw == orig.get(n)
            same = f' identical to original {ok}'
            if not ok:
                bad += 1
        print(f'{n}: {len(raw)}B, {len(lines)} lines, cjk {cjk}{same}')
        if cjk:
            bad += 1
            for ln in lines:
                if any(is_cjk(c) for c in ln):
                    print(f'   CJK line: {ln[:60]}')
    if bad:
        print('FAIL name tables are not stock English')
        return 1
    print('name tables OK (stock English, no CJK)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
