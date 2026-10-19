#!/usr/bin/env python3
"""Build assets/elneazh/data/names.tsv (kind\\tEnglish\\tChinese) for in-game save name conversion.
kind F = first name (NameMale/NameFemale), L = family name (NameFamily). English originals are in
translations/names_en/, Chinese in translations/Name*_en.json (key L<line>)."""
import json, sys
out, seen = [], set()
for n, kind in (('Male', 'F'), ('Female', 'F'), ('Family', 'L')):
    en = open(f'translations/names_en/Name{n}_en.txt', encoding='utf-8-sig').read().splitlines()
    zh = json.load(open(f'translations/Name{n}_en.json', encoding='utf-8'))
    for i, e in enumerate(en):
        e, z = e.strip(), zh.get(f'L{i+1}', '').strip()
        if e and z and (kind, e) not in seen:
            seen.add((kind, e)); out.append(f'{kind}\t{e}\t{z}')
open(sys.argv[1], 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('names', len(out))
