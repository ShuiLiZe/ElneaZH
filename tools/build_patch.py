#!/usr/bin/env python3
"""Build the Chinese data.unity3d.

usage: python tools/build_patch.py <orig data.unity3d> <out data.unity3d>
         [--tr translations] [--font fonts/zh.ttf] [--images images_zh] [--bmfont fonts/zh.ttf]

- text:   translations/<asset>.json applied with elnea_text.apply (keys/format untouched)
- font:   replaces m_FontData of Font 'default_android' (subset to used chars if fonttools present)
- images: images_zh/<Texture2D name>.png, same size as original; re-encoded in original format
- bmfont: regenerates the NGUI bitmap fonts in Main_Atlas_jp (BitmapFont-Area / BitmapFont-Name)
          with GB2312 + every char used in translations (tools/bmfont_zh.py); texture -> 2048x4096
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
    # GB2312 (all 6763 common hanzi + symbols) so player-typed Chinese names also render
    gb = []
    for hi in range(0xA1, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                gb.append(bytes([hi, lo]).decode('gb2312'))
            except UnicodeDecodeError:
                pass
    latin = ''.join(chr(c) for c in range(0xA0, 0x250))
    sym = ''.join(chr(c) for c in list(range(0x2000, 0x2BFF)) + list(range(0x3000, 0x30FF)) + list(range(0xFF00, 0xFFEF)))
    s.populate(text=base + latin + sym + ''.join(gb) + ''.join(chars))
    s.subset(f)
    bio = io.BytesIO()
    f.save(bio)
    return bio.getvalue()


def etc2_rgba_mips(img, mips):
    """ETC2_RGBA8 with a full mip chain. UnityPy 1.10.18 swaps R/B before etcpak, but etcpak>=0.9.15
    expects RGBA, so its own set_image turns red into blue. Encode here instead (Unity rows bottom-up)."""
    import etcpak
    from PIL import Image
    out = bytearray()
    w, h = img.size
    base = img.transpose(Image.FLIP_TOP_BOTTOM)
    for i in range(mips):
        lw, lh = max(w >> i, 1), max(h >> i, 1)
        lvl = base if i == 0 else base.resize((lw, lh), Image.LANCZOS)
        pw, ph = (lw + 3) // 4 * 4, (lh + 3) // 4 * 4
        if (pw, ph) != (lw, lh):
            pad = Image.new('RGBA', (pw, ph), (0, 0, 0, 0)); pad.paste(lvl, (0, 0)); lvl = pad
        out += etcpak.compress_to_etc2_rgba(lvl.tobytes(), pw, ph)
    return bytes(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('orig'); ap.add_argument('out')
    ap.add_argument('--tr', default='translations')
    ap.add_argument('--font'); ap.add_argument('--images', default='images_zh')
    ap.add_argument('--bmfont', help='TTF used to render Chinese glyphs into the NGUI bitmap font')
    ap.add_argument('--names', default='tools/name_assets.txt',
                    help='TextAssets that must stay stock English (one name per line)')
    a = ap.parse_args()

    # 名字表（NameMale_en / NameFemale_en / NameFamily_en）必须是英文原文：这些名字会写进存档、
    # 再随 read.php / pvp.php / 好友列表上传，服务器侧看到中文名是封号判据。
    # 汉化只做「显示时映射」，所以这里主动把原版英文写回去，不参与任何翻译。
    want = set()
    if os.path.exists(a.names):
        for line in open(a.names, encoding='utf-8'):
            line = line.strip()
            if line and not line.startswith('#'):
                want.add(line)
    assert len(want) == 3, f'{a.names}: expected 3 name tables, got {want}'

    env = UnityPy.load(a.orig)
    used = set()
    nt = ni = nn = 0
    for o in env.objects:
        if o.type.name == 'TextAsset':
            d = o.read()
            if d.m_Name in want:
                # 原文写回（UnityPy 读出来是 str，按 utf-8 编码回字节）
                s = d.m_Script
                raw = s.encode('utf-8', 'surrogateescape') if isinstance(s, str) else bytes(s)
                d.m_Script = raw.decode('utf-8', 'surrogateescape') if isinstance(s, str) else raw
                d.save(); nn += 1
                print(f'name table kept ascii: {d.m_Name}')
                continue
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
            used.update(new if isinstance(new, str) else "")
            d.m_Script = E.encode(new, bom).decode('utf-8', 'surrogateescape') if isinstance(s, str) else E.encode(new, bom)
            d.save(); nt += len(tr)
        elif o.type.name == 'Texture2D' and os.path.isdir(a.images) and not (a.bmfont and o.read().m_Name == 'Main_Atlas_jp'):
            d = o.read()
            p = os.path.join(a.images, d.m_Name + '.png')
            if os.path.exists(p):
                from PIL import Image
                img = Image.open(p).convert('RGBA')
                assert img.size == (d.m_Width, d.m_Height), f'{p}: size {img.size} != {(d.m_Width, d.m_Height)}'
                if d.m_TextureFormat.name == 'ETC2_RGBA8':
                    data = etc2_rgba_mips(img, d.m_MipCount)
                    d.image_data = data
                    d.m_CompleteImageSize = len(data)
                    if hasattr(d, 'm_StreamData') and d.m_StreamData is not None:
                        d.m_StreamData.path = ''; d.m_StreamData.offset = 0; d.m_StreamData.size = 0
                else:
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
    if a.bmfont:
        import bmfont_zh
        bmfont_zh.apply(env.file.files['resources.assets'], a.bmfont, used)
    with open(a.out, 'wb') as f:
        f.write(env.file.save(packer='lz4'))  # keep LZ4 blocks like the original
    print(f'texts {nt}, images {ni}, ascii name tables {nn} -> {a.out}')


if __name__ == '__main__':
    main()
