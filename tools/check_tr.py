#!/usr/bin/env python3
"""check translations/<name>.json against work/src/<name>.json"""
import sys, json, re, os
B = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PH = re.compile(r'\$\([^)]*\)\$|\{\d+(?::[^}]*)?\}|\\n')
bad = 0
for name in sys.argv[1:]:
    src = json.load(open(f'{B}/work/src/{name}.json', encoding='utf-8'))
    tr = json.load(open(f'{B}/repo/translations/{name}.json', encoding='utf-8'))
    miss = [u for u in src if u not in tr or not str(tr[u]).strip()]
    extra = [u for u in tr if u not in src]
    for u, en in src.items():
        zh = tr.get(u)
        if zh is None:
            continue
        if sorted(PH.findall(en)) != sorted(PH.findall(zh)):
            print(f'{name}|{u} placeholder mismatch: {PH.findall(en)} vs {PH.findall(zh)}'); bad += 1
        if '"' in zh or (name == 'ZoneHelp_en' and '^' in zh):
            print(f'{name}|{u} forbidden char'); bad += 1
    if miss: print(f'{name}: {len(miss)} missing e.g. {miss[:5]}'); bad += len(miss)
    if extra: print(f'{name}: {len(extra)} unknown uids {extra[:5]}'); bad += len(extra)
    print(f'{name}: {len(tr)}/{len(src)} ok' if not miss else '')
sys.exit(1 if bad else 0)
