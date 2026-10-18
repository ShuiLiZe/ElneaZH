"""Restore v6 borrowed glyphs -> real Simplified Chinese, rewrite translations/contents/*.json and content_zh/Contents/c_* (encrypted).
borrow_map_flat.json maps real->borrowed; keys and values are disjoint, so inversion is safe."""
import json, glob, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import contents_codec as cc
R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
inv = {v: k for k, v in json.load(open(os.path.join(R, 'tools/borrow_map_flat.json'), encoding='utf-8')).items()}
EXTRA = {}  # manual fixes for borrows missing from the map, e.g. '侘'
fix = lambda s: ''.join(EXTRA.get(c, inv.get(c, c)) for c in s)
for f in sorted(glob.glob(os.path.join(R, 'content_zh/Contents/c_*'))):
    name = os.path.basename(f)
    d = cc.decrypt(open(f, 'rb').read())
    mp = {rid: fix(t) for s, e, rid, t in cc.strings(d) if fix(t) != t}
    nd = cc.replace(d, mp)
    enc = cc.encrypt(nd)
    assert {r: t for _, _, r, t in cc.strings(cc.decrypt(enc))}.items() >= {r: t for r, t in mp.items()}.items()
    open(f, 'wb').write(enc)
    j = os.path.join(R, f'translations/contents/{name}.json')
    if os.path.exists(j):
        js = json.load(open(j, encoding='utf-8'))
        json.dump({k: fix(v) for k, v in js.items()}, open(j, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(name, len(mp))
