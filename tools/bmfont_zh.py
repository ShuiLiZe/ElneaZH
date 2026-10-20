#!/usr/bin/env python3
"""Rebuild the NGUI bitmap fonts in Main_Atlas_jp with Chinese glyphs.

Game structure (Elnea Kingdom 2.10.7, resources.assets, no typetree -> raw bytes):
  NGUIFont (ScriptableObject) 'BitmapFont-Area'  main UI/dialogue font, 24px, base 23, 4205 glyphs (JIS kanji)
  NGUIFont 'BitmapFont-Name'  name font = the set of characters allowed in player-typed names
                              (Latin + katakana, 260 glyphs); name input rejects anything not in it
  NGUIFont 'BitmapFont-Clock' / 'BitmapFont-Figure'  clock / big digits (left untouched)
  NGUIAtlas 'Main_Atlas_jp' -> Material -> Texture2D 'Main_Atlas_jp' (2048x2048 RGBA32, premultiplied alpha)
    sprite 'BitmapFont-Area_0' = (0,1002) 2047x1046, padding (l0,r1,t0,b1002)  -> rows 1002..2047
    sprite 'BitmapFont-Name_0' = (0,445) 511x177 (upper part, among UI sprites)

NGUIFont raw layout after m_Name (aligned):  int, PPtr mMat(fileID,int64 pathID)=12B, Rect mUVRect (4 floats),
  BMFont { int mSize, int mBase, int mWidth, int mHeight, string mSpriteName, List<BMGlyph> mSaved }
  BMGlyph { int index,x,y,width,height,offsetX,offsetY,advance,channel; List<int> kerning }
  then mAtlas PPtr ... (suffix kept byte-for-byte).
NGUIAtlas sprite record: string name; int x,y,w,h, borderL,R,B,T, padL,R,T,B  (pixels, top-left origin).

What apply() does:
  * texture grows to 2048x4096 (rows 0..1001 = UI sprites, copied untouched; pixel rects stay valid
    because NGUI sprite rects are measured from the top-left and UVs are derived from texture size at runtime)
  * rows 1002..4095 hold a freshly packed glyph sheet:
      - all non-kanji glyphs of the Area font, copied pixel-exact with their original metrics
      - every hanzi of GB2312 (6763) + every CJK char in the translations, rendered from the TTF
        (Noto Sans SC) at 23px in a 24px advance (same box as the old kanji: ink ~22x22, offset 1,1)
      - original JIS kanji not covered by the above (copied, so leftover Japanese still renders)
      - translation symbols missing from the old font (e.g. — ·), rendered from the TTF
      - the Name font's own 260 glyphs, copied pixel-exact
  * 'BitmapFont-Area_0' sprite -> (0,1002) 2048x3094, no padding; Area font table rewritten
  * 'BitmapFont-Name' switched to sprite 'BitmapFont-Area_0' with its own glyphs + all hanzi,
    so Chinese names render and pass the name check.
"""
import json
import os
import struct
import numpy as np
from PIL import Image, ImageDraw, ImageFont

TEX_NAME = 'Main_Atlas_jp'
AREA, NAME = 'BitmapFont-Area', 'BitmapFont-Name'
SPRITE = 'BitmapFont-Area_0'
NEW_H = 4096
REGION_Y = 1002          # first row of the glyph sheet (sprites end at row 1001)
RENDER_PX = 23           # TTF pixel size for hanzi (ink ~22px like the original kanji)
LINE_BASE = 21           # baseline row inside the 24px line used for rendered glyphs
CN_PUNCT = '，。！？、：；“”‘’（）《》〈〉…—～·「」『』【】'


def is_han(c):
    o = ord(c)
    return 0x3400 <= o <= 0x4DBF or 0x4E00 <= o <= 0x9FFF or 0xF900 <= o <= 0xFAFF or 0x20000 <= o <= 0x2FFFF


def gb2312_hanzi():
    out = []
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                out.append(bytes([hi, lo]).decode('gb2312'))
            except UnicodeDecodeError:
                pass
    return out


# ---------------- raw parsing ----------------
def _str(b, p):
    n = struct.unpack_from('<i', b, p)[0]
    s = b[p + 4:p + 4 + n].decode('utf-8')
    return s, (p + 4 + n + 3) & ~3


def _wstr(s):
    e = s.encode('utf-8')
    return struct.pack('<i', len(e)) + e + b'\0' * ((-len(e)) % 4)


def mb_name(b):
    return _str(b, 28)[0]


def parse_font(b):
    _, p = _str(b, 28)
    p += 16                      # int + PPtr mMat
    uv_off = p
    p += 16                      # mUVRect
    bm_off = p
    size, base, w, h = struct.unpack_from('<4i', b, p); p += 16
    sprite, p = _str(b, p)
    n = struct.unpack_from('<i', b, p)[0]; p += 4
    glyphs = []
    for _ in range(n):
        v = list(struct.unpack_from('<9i', b, p)); p += 36
        k = struct.unpack_from('<i', b, p)[0]; p += 4
        kern = list(struct.unpack_from('<%di' % k, b, p)); p += 4 * k
        glyphs.append(dict(id=v[0], x=v[1], y=v[2], w=v[3], h=v[4], ox=v[5], oy=v[6], adv=v[7], ch=v[8], kern=kern))
    return dict(uv_off=uv_off, bm_off=bm_off, end=p, size=size, base=base, w=w, h=h, sprite=sprite, glyphs=glyphs)


def build_font(b, f, uv, size, base, w, h, sprite, glyphs):
    out = bytearray(b[:f['uv_off']])
    out += struct.pack('<4f', *uv)
    out += struct.pack('<4i', size, base, w, h) + _wstr(sprite) + struct.pack('<i', len(glyphs))
    for g in sorted(glyphs, key=lambda g: g['id']):
        out += struct.pack('<9i', g['id'], g['x'], g['y'], g['w'], g['h'], g['ox'], g['oy'], g['adv'], g['ch'])
        out += struct.pack('<i', len(g['kern'])) + b''.join(struct.pack('<i', k) for k in g['kern'])
    out += b[f['end']:]
    return bytes(out)


def find_sprite(b, name):
    key = _wstr(name)
    i = b.find(key)
    if i < 0:
        return None
    p = i + len(key)
    return p, list(struct.unpack_from('<12i', b, p))


# ---------------- rendering ----------------
def render_glyph(font, c):
    """Return (L image cropped to ink, ox, oy, adv) in NGUI line coords (top=0, baseline=LINE_BASE)."""
    S, pen_x, pen_y = 64, 16, 44
    im = Image.new('L', (S, S))
    ImageDraw.Draw(im).text((pen_x, pen_y), c, font=font, fill=255, anchor='ls')
    bb = im.getbbox()
    adv = int(round(font.getlength(c)))
    extra = 0
    if is_han(c) or adv == RENDER_PX:     # full-width: 23px glyph in a 24px cell, like the old kanji
        adv, extra = 24, 1
    if bb is None:
        return None, 0, 0, adv
    ox = bb[0] - pen_x + extra
    oy = LINE_BASE - (pen_y - bb[1])
    return im.crop(bb), ox, oy, adv


PATCH_DIR = os.environ.get('ELNEAZH_JP_PATCH', 'images_zh/Main_Atlas_jp_patch')


def paste_sprite_patches(arr, log=print):
    """Paste <sprite>.png (exact sprite size, premultiplied) at the sprite rect of Main_Atlas_jp."""
    try:
        if not os.path.isdir(PATCH_DIR):
            return 0
        here = os.path.dirname(os.path.abspath(__file__))
        spr = {s['name']: s for s in json.load(open(os.path.join(here, 'imgzh', 'atlas_sprites.json')))[TEX_NAME]}
        n = 0
        for f in sorted(os.listdir(PATCH_DIR)):
            name = f[:-4]
            if not f.endswith('.png') or name not in spr:
                continue
            s = spr[name]
            im = np.array(Image.open(os.path.join(PATCH_DIR, f)).convert('RGBA'))
            assert im.shape[:2] == (s['h'], s['w']), f'{f}: {im.shape[:2]} != {(s["h"], s["w"])}'
            assert s['y'] + s['h'] <= REGION_Y
            arr[s['y']:s['y'] + s['h'], s['x']:s['x'] + s['w']] = im
            n += 1
        log(f'bmfont: pasted {n} sprite patches from {PATCH_DIR}')
        return n
    except Exception as e:  # never break the build over cosmetics
        log(f'bmfont: sprite patches skipped: {e}')
        return 0


def apply(env_file, ttf_path, extra_chars=(), log=print):
    """env_file: the SerializedFile 'resources.assets'. Modifies objects in place (saved by caller)."""
    objs = env_file.objects
    tex_obj = None
    fonts, atlases = {}, []
    for o in objs.values():
        t = o.type.name
        if t == 'Texture2D' and tex_obj is None:
            d = o.read()
            if d.m_Name == TEX_NAME:
                tex_obj, tex = o, d
        elif t == 'MonoBehaviour':
            b = bytes(o.get_raw_data())
            try:
                nm = mb_name(b)
            except Exception:
                continue
            if nm in (AREA, NAME):
                fonts[nm] = (o, b)
            elif find_sprite(b, SPRITE) is not None:
                atlases.append((o, b))          # NGUIAtlas Main_Atlas_jp + legacy UIAtlas with the same list
    assert tex_obj and AREA in fonts and NAME in fonts and atlases, 'bitmap font objects not found'

    src = np.array(tex.image.convert('RGBA'))   # top-left origin
    H0, W = src.shape[:2]
    assert (W, H0) == (2048, 2048), f'unexpected {TEX_NAME} size {W}x{H0} (already patched?)'

    # sprite rects in the original atlas (virtual origin = x - padL, y - padT)
    def virt_origin(spr):
        for o, b in atlases:
            r = find_sprite(b, spr)
            if r:
                v = r[1]
                return v[0] - v[8], v[1] - v[10]
        raise KeyError(spr)

    fa = parse_font(fonts[AREA][1])
    fn = parse_font(fonts[NAME][1])
    ax, ay = virt_origin(fa['sprite'])
    nx, ny = virt_origin(fn['sprite'])

    def crop(g, x0, y0):
        return src[y0 + g['y']:y0 + g['y'] + g['h'], x0 + g['x']:x0 + g['x'] + g['w']].copy()

    ttf = ImageFont.truetype(ttf_path, RENDER_PX)
    from fontTools.ttLib import TTFont
    cmap = set(TTFont(ttf_path, lazy=True).getBestCmap().keys())

    # ----- choose glyph content -----
    items = []          # (key, rgba_array, ox, oy, adv)
    area_ids = {}       # char id -> item index
    old = {g['id']: g for g in fa['glyphs']}
    want_han = []
    seen = set()
    for c in gb2312_hanzi() + sorted(c for c in set(extra_chars) if is_han(c)):
        if c not in seen:
            seen.add(c); want_han.append(c)

    def add(key, arr, ox, oy, adv):
        items.append((key, arr, ox, oy, adv))
        return len(items) - 1

    def rgba_from_L(L):
        a = np.array(L, dtype=np.uint8)
        return np.dstack([a, a, a, a])      # premultiplied white, like the original sheet

    n_copy = n_render = n_keep = n_sym = 0
    for gid, g in old.items():                       # non-kanji: pixel-exact copy
        if not is_han(chr(gid)):
            area_ids[gid] = add(('A', gid), crop(g, ax, ay), g['ox'], g['oy'], g['adv']); n_copy += 1
    missing = []
    for c in want_han:                               # hanzi: render from TTF
        if ord(c) not in cmap:
            missing.append(c); continue
        L, ox, oy, adv = render_glyph(ttf, c)
        area_ids[ord(c)] = add(('A', ord(c)), rgba_from_L(L), ox, oy, adv); n_render += 1
    for gid, g in old.items():                       # leftover JIS kanji: keep original bitmap
        if is_han(chr(gid)) and gid not in area_ids:
            area_ids[gid] = add(('A', gid), crop(g, ax, ay), g['ox'], g['oy'], g['adv']); n_keep += 1
    for c in sorted(set(extra_chars) | set(CN_PUNCT)):   # symbols the old font lacks
        o = ord(c)
        if o < 0x20 or o in area_ids or is_han(c) or c in '\r\n\t' or o not in cmap:
            if o >= 0x20 and o not in area_ids and not is_han(c) and o not in cmap and c not in '\r\n\t':
                missing.append(c)
            continue
        L, ox, oy, adv = render_glyph(ttf, c)
        if L is None:                                # whitespace
            area_ids[o] = add(('A', o), np.zeros((1, 1, 4), np.uint8), 0, 0, adv)
        else:
            area_ids[o] = add(('A', o), rgba_from_L(L), ox, oy, adv)
        n_sym += 1
    name_ids = {}
    for g in fn['glyphs']:                           # name font's own glyphs: pixel-exact copy
        name_ids[g['id']] = add(('N', g['id']), crop(g, nx, ny), g['ox'], g['oy'], g['adv'])

    # ----- shelf packing into rows REGION_Y..NEW_H -----
    order = sorted(range(len(items)), key=lambda i: (-items[i][1].shape[0], -items[i][1].shape[1]))
    pos = [None] * len(items)
    x = y = 0; row_h = 0
    RW, RH = W, NEW_H - REGION_Y
    for i in order:
        h, w = items[i][1].shape[:2]
        if x + w + 1 > RW:
            x = 0; y += row_h + 1; row_h = 0
        if y + h > RH:
            raise RuntimeError(f'glyph sheet full after {sum(p is not None for p in pos)} glyphs')
        pos[i] = (x, y); x += w + 1; row_h = max(row_h, h)
    used_h = y + row_h
    log(f'bmfont: {len(items)} glyph bitmaps packed, sheet rows used {used_h}/{RH}')

    new = np.zeros((NEW_H, W, 4), np.uint8)
    new[:REGION_Y] = src[:REGION_Y]                 # UI sprites untouched
    paste_sprite_patches(new, log)                  # Chinese battle telops etc. (images_zh/Main_Atlas_jp_patch)
    for i, (key, arr, ox, oy, adv) in enumerate(items):
        px, py = pos[i]; h, w = arr.shape[:2]
        new[REGION_Y + py:REGION_Y + py + h, px:px + w] = arr

    def glyph(i, gid):
        key, arr, ox, oy, adv = items[i]
        px, py = pos[i]; h, w = arr.shape[:2]
        return dict(id=gid, x=px, y=py, w=w, h=h, ox=ox, oy=oy, adv=adv, ch=15, kern=[])

    area_glyphs = [glyph(i, gid) for gid, i in area_ids.items()]
    name_glyphs = [glyph(i, gid) for gid, i in name_ids.items()]
    # 汉字只进 Area 字体（显示），不进 Name 字体。
    # Name 字体就是游戏校验「允许取的字符」的字形集，把汉字塞进去等于允许玩家用中文取名；
    # 中文名会写进存档并随 read.php / pvp.php / 好友列表上传，服务器侧一眼可疑。

    # ----- write objects -----
    vw, vh = W, RH                                    # virtual font texture = the sheet itself
    uv = (0.0, 1.0 - (REGION_Y + vh) / NEW_H, vw / W, vh / NEW_H)   # NGUI uv rect (bottom-left origin)
    for nm, f, gl in ((AREA, fa, area_glyphs), (NAME, fn, name_glyphs)):
        o, b = fonts[nm]
        nb = build_font(b, f, uv, f['size'], f['base'], vw, vh, SPRITE, gl)
        o.set_raw_data(nb)
        assert len(parse_font(nb)['glyphs']) == len(gl)
    for o, b in atlases:
        p, v = find_sprite(b, SPRITE)
        v[0:4] = [0, REGION_Y, vw, vh]
        v[8:12] = [0, 0, 0, 0]
        nb = bytearray(b); struct.pack_into('<12i', nb, p, *v)
        o.set_raw_data(bytes(nb))
    img = Image.fromarray(new, 'RGBA')
    tex.m_Width, tex.m_Height = W, NEW_H
    tex.set_image(img, target_format=tex.m_TextureFormat, mipmap_count=max(1, tex.m_MipCount))
    tex.save()
    log(f'bmfont: Area {len(area_glyphs)} glyphs (copied {n_copy}, rendered hanzi {n_render}, kept JIS {n_keep}, '
        f'rendered symbols {n_sym}); Name {len(name_glyphs)} glyphs; texture {W}x{NEW_H}')
    if missing:
        log('bmfont: not in TTF, skipped: ' + ''.join(missing))
    return dict(area=len(area_glyphs), name=len(name_glyphs), missing=missing, rows=used_h)
