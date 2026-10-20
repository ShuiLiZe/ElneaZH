#!/usr/bin/env python3
"""汉字→拼音表（GB2312 6763 字），生成两处产物：

  - translations/pinyin.tsv              仓库里存一份（可复现、可 review）
  - app/src/main/assets/elneazh/data/pinyin.tsv  打进 assets，供 inj/Pinyin.java 在 API<29 兜底

用法: python tools/pinyin_tables.py [--out PATH] [--copy-to-assets]
默认两个都写。
"""
import argparse
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_TSV = os.path.join(ROOT, 'translations', 'pinyin.tsv')
ASSET_TSV = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'elneazh', 'data', 'pinyin.tsv')
HEADER = ('# 汉字\t拼音（无声调，小写）。由 tools/pinyin_tables.py 生成，'
          'app 端 API<29 兜底用；API29+ 走 android.icu Transliterator("Han-Latin; Latin-ASCII")。\n')


def gb2312_hanzi():
    out = []
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                c = bytes([hi, lo]).decode('gb2312')
            except UnicodeDecodeError:
                continue
            out.append(c)
    return out


def build():
    from pypinyin import lazy_pinyin, Style
    rows = []
    for c in gb2312_hanzi():
        p = lazy_pinyin(c, style=Style.NORMAL, errors='ignore')
        if p and p[0] and p[0].isalpha():
            rows.append((c, p[0].lower()))
    return rows


def write(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(HEADER)
        for c, p in rows:
            f.write('%s\t%s\n' % (c, p))
    return len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=None, help='只写这个路径（不写默认两处）')
    a = ap.parse_args()
    try:
        rows = build()
    except ImportError:
        print('need pypinyin: python -m pip install pypinyin', file=sys.stderr)
        return 1
    if a.out:
        print(write(a.out, rows), '->', a.out)
        return 0
    if not os.path.isfile(REPO_TSV):
        write(REPO_TSV, rows)
        print('repo table ->', REPO_TSV)
    n = write(ASSET_TSV, rows)
    print('assets table ->', ASSET_TSV, n, 'chars')
    return 0


if __name__ == '__main__':
    sys.exit(main())
