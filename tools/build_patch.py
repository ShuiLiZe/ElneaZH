#!/usr/bin/env python3
"""Build the Chinese data.unity3d.

usage: python tools/build_patch.py <orig data.unity3d> <out data.unity3d>
         [--tr translations] [--font fonts/zh.ttf] [--images images_zh]

- text:   translations/<asset>.json applied with elnea_text.apply (keys/format untouched)
- font:   replaces m_FontData of Font 'default_android' (subset to used chars if fonttools present)
- images: images_zh/<Texture2D name>.png, same size as original; re-encoded in original format
Then (separately) the app patch = bsdiff(orig, out); see HANDOFF.md.
"""
import sys, os, json, argparse, io
import UnityPy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import elnea_text as E


def subset_font(path, chars):
    try:
        from fontTools import subset
        from fontTools.ttLib import TTFont
    except ImportError:
        return open(path, 'rb').read()
    f = TTFont(path)
    opt = subset.Options()
    opt.layout_features = ['*']
    opt.name_IDs = ['*']
    opt.notdef_outline = True
    s = subset.Subsetter(opt)
    base = ''.join(chr(c) for c in range(0x20, 0x7f)) + '，。！？、：；“”‘’（）《》…—～·「」『』【】♪★☆♥①②③④⑤'
    s.populate(text=base + ''.join(chars))
    s.subset(f)
    bio = io.BytesIO()
    f.save(bio)
    return bio.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('orig'); ap.add_argument('out')
    ap.add_argument('--tr', default='translations')
    ap.add_argument('--font'); ap.add_argument('--images', default='images_zh')
    a = ap.parse_args()

    env = UnityPy.load(a.orig)
    used = set()
    nt = ni = 0
    for o in env.objects:
        if o.type.name == 'TextAsset':
            d = o.read()
            p = os.path.join(a.tr, d.m_Name + '.json')
            if not d.m_Name.endswith('_en') or not os.path.exists(p):
                continue
            tr = json.load(open(p, encoding='utf-8'))
            s = d.m_Script
            raw = s.encode('utf-8', 'surrogateescape') if isinstance(s, str) else bytes(s)
            text, bom = E.decode(raw)
            new = E.apply(d.m_Name, text, tr)
            for v in tr.values():
                used.update(v)
            d.m_Script = E.encode(new, bom).decode('utf-8', 'surrogateescape') if isinstance(s, str) else E.encode(new, bom)
            d.save(); nt += len(tr)
        elif o.type.name == 'Texture2D' and os.path.isdir(a.images):
            d = o.read()
            p = os.path.join(a.images, d.m_Name + '.png')
            if os.path.exists(p):
                from PIL import Image
                img = Image.open(p).convert('RGBA')
                assert img.size == (d.m_Width, d.m_Height), f'{p}: size {img.size} != {(d.m_Width, d.m_Height)}'
                d.set_image(img, target_format=d.m_TextureFormat)
                d.save(); ni += 1
    if a.font:
        for o in env.objects:
            if o.type.name == 'Font':
                d = o.read()
                if d.m_Name == 'default_android':
                    data = subset_font(a.font, used)
                    d.m_FontData = list(data) if isinstance(d.m_FontData, list) else data
                    d.save()
                    print('font replaced', len(data), 'bytes')
    with open(a.out, 'wb') as f:
        f.write(env.file.save(packer='lz4'))  # keep LZ4 blocks like the original
    print(f'texts {nt}, images {ni} -> {a.out}')


if __name__ == '__main__':
    main()
