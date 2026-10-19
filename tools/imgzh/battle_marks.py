#!/usr/bin/env python3
"""Redraw the English battle telops that live in Main_Atlas_jp (the EN atlas does not override them).

  python tools/imgzh/battle_marks.py base/images_en/Main_Atlas_jp.png images_zh/Main_Atlas_jp_patch [preview.png]

Writes one PNG per sprite (exact sprite size, premultiplied alpha like the atlas).
tools/bmfont_zh.py pastes them into the rebuilt Main_Atlas_jp (rows 0..1001 hold the UI sprites).
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
FONT = os.path.join(ROOT, 'fonts', 'zh.ttf')
SPR = {s['name']: s for s in json.load(open(os.path.join(HERE, 'atlas_sprites.json')))['Main_Atlas_jp']}

RED = dict(top=(150, 10, 10), bot=(240, 90, 20), line=(70, 8, 0), glow=(255, 165, 40))
YEL = dict(top=(255, 250, 110), bot=(245, 185, 0), line=(110, 75, 0), glow=(255, 250, 215))
SILVER = dict(top=(255, 255, 255), mid=(120, 120, 125), bot=(235, 235, 240), line=(50, 50, 55), glow=None)
GOLD = dict(top=(255, 246, 200), bot=(150, 150, 70), line=(105, 90, 45), glow=(255, 248, 220))

# sprite: (lines, style, x where the text box starts (icon kept on the left))
JOBS = {
    'break_mark':   (['破防！'], RED, 61),
    'cbreak_mark':  (['破解', '反击！'], RED, 62),
    'counter_mark': (['反击', '伤害！'], YEL, 34),
    'guard_mark':   (['格挡'], SILVER, 0),
    'miss_mark':    (['落空！'], GOLD, 0),
}


def unpremul(a):
    a = a.astype(np.float32)
    al = a[..., 3:4]
    rgb = np.where(al > 0, a[..., :3] * 255.0 / np.maximum(al, 1), 0)
    return np.concatenate([np.clip(rgb, 0, 255), al], -1).astype(np.uint8)


def premul(a):
    a = a.astype(np.float32)
    al = a[..., 3:4] / 255.0
    return np.concatenate([a[..., :3] * al, a[..., 3:4]], -1).round().astype(np.uint8)


def text_mask(lines, w, h, stroke):
    """largest font size whose block (with outline room) fits w x h; returns L mask at 4x"""
    S = 4
    best = None
    for px in range(8, 80):
        f = ImageFont.truetype(FONT, px * S)
        boxes = [f.getbbox(t, stroke_width=0) for t in lines]
        lw = max(b[2] - b[0] for b in boxes)
        lh = [b[3] - b[1] for b in boxes]
        gap = int(px * S * 0.06)
        th = sum(lh) + gap * (len(lines) - 1)
        pad = 2 * stroke * S
        if lw + pad > w * S or th + pad > h * S:
            break
        best = (f, boxes, lw, lh, gap, th)
    f, boxes, lw, lh, gap, th = best
    m = Image.new('L', (w * S, h * S), 0)
    d = ImageDraw.Draw(m)
    y = (h * S - th) // 2
    for t, b, hh in zip(lines, boxes, lh):
        x = (w * S - (b[2] - b[0])) // 2 - b[0]
        d.text((x, y - b[1]), t, font=f, fill=255, stroke_width=int(S * 1.0), stroke_fill=255)  # a touch bolder
        y += hh + gap
    return m.resize((w, h), Image.LANCZOS)


def dilate(m, r):
    return m.filter(ImageFilter.MaxFilter(2 * r + 1)) if r > 0 else m


def grad(h, w, st):
    t = np.linspace(0, 1, h)[:, None, None]
    top, bot = np.array(st['top'], float), np.array(st['bot'], float)
    if 'mid' in st:
        mid = np.array(st['mid'], float)
        g = np.where(t < 0.55, top + (mid - top) * (t / 0.55), mid + (bot - mid) * ((t - 0.55) / 0.45))
    else:
        g = top + (bot - top) * t
    return np.broadcast_to(g, (h, w, 3))


def draw(lines, st, w, h):
    line_w, glow_w = 2, (2 if st['glow'] else 0)
    m = text_mask(lines, w, h, line_w + glow_w)
    mf = np.asarray(m, float) / 255
    ml = np.asarray(dilate(m, line_w), float) / 255
    mg = np.asarray(dilate(m, line_w + glow_w).filter(ImageFilter.GaussianBlur(0.6)), float) / 255 if glow_w else ml * 0
    out = np.zeros((h, w, 4), float)
    def comp(rgb, a):
        a = a[..., None]
        out[..., :3] = rgb * a + out[..., :3] * (1 - a)
        out[..., 3:] = a + out[..., 3:] * (1 - a)
    if glow_w:
        comp(np.broadcast_to(np.array(st['glow'], float), (h, w, 3)), mg)
    comp(np.broadcast_to(np.array(st['line'], float), (h, w, 3)), ml)
    # fill + soft highlight band on the upper part of the strokes
    g = grad(h, w, st).copy()
    comp(g, mf)
    rgb = np.where(out[..., 3:] > 0, out[..., :3] / np.maximum(out[..., 3:], 1e-6), 0)
    return np.dstack([rgb, out[..., 3] * 255]).clip(0, 255).astype(np.uint8)


def main(atlas, outdir, preview=None):
    src = np.array(Image.open(atlas).convert('RGBA'))
    os.makedirs(outdir, exist_ok=True)
    tiles = []
    for n, (lines, st, x0) in JOBS.items():
        s = SPR[n]
        x, y, w, h = s['x'], s['y'], s['w'], s['h']
        orig = unpremul(src[y:y + h, x:x + w])
        new = orig.copy()
        if x0:
            # keep the icon: everything left of x0, minus stray letter pixels touching it
            new[:, x0:] = 0
            for i in range(4):  # feather the cut through the icon's glow
                new[:, x0 - 1 - i, 3] = (new[:, x0 - 1 - i, 3] * (i + 1) / 5).astype(np.uint8)
        else:
            new[:] = 0
        txt = draw(lines, st, w - x0, h)
        reg = new[:, x0:].astype(float)
        a = txt[..., 3:4] / 255.0
        reg[..., :3] = txt[..., :3] * a + reg[..., :3] * (1 - a)
        reg[..., 3:] = txt[..., 3:4] + reg[..., 3:] * (1 - a)
        new[:, x0:] = reg.clip(0, 255).astype(np.uint8)
        Image.fromarray(premul(new), 'RGBA').save(os.path.join(outdir, n + '.png'))
        tiles.append((orig, new))
        print(n, '->', ''.join(lines))
    if preview:
        W = max(o.shape[1] for o, _ in tiles) * 2 + 30
        H = sum(o.shape[0] + 8 for o, _ in tiles)
        p = Image.new('RGBA', (W, H), (40, 90, 40, 255)); yy = 0
        for o, nw in tiles:
            p.alpha_composite(Image.fromarray(o), (0, yy))
            p.alpha_composite(Image.fromarray(nw), (W // 2 + 10, yy))
            yy += o.shape[0] + 8
        p.resize((W * 2, H * 2), Image.NEAREST).save(preview)


if __name__ == '__main__':
    main(*sys.argv[1:4])
