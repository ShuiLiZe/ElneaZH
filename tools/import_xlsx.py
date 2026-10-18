#!/usr/bin/env python3
"""Translation sheet -> translations/<asset>.json ({uid: "中文"}).
usage: python tools/import_xlsx.py sheet/ElneaKingdom_汉化对照表.xlsx translations/
"""
import sys, os, json, collections
from openpyxl import load_workbook

wb = load_workbook(sys.argv[1], read_only=True)
ws = wb['全部文本']
out = collections.defaultdict(dict)
for row in ws.iter_rows(min_row=2, values_only=True):
    rid, zh = row[0], row[6]
    if not rid or zh is None or str(zh).strip() == '':
        continue
    name, uid = rid.split('|', 1)
    out[name][uid] = str(zh)
os.makedirs(sys.argv[2], exist_ok=True)
n = 0
for name, d in sorted(out.items()):
    json.dump(dict(sorted(d.items())), open(os.path.join(sys.argv[2], name + '.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n += len(d)
print(f'{n} translated strings in {len(out)} files')
