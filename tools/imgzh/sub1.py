import json, sys, os
import numpy as np
from scipy import ndimage as ndi
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
A = json.load(open(ROOT + '/work/atlas_sprites.json'))
S = {s['name']: s for s in A['Sub1_Atlas_en']}

BANNERS = {
    'clear_message_area': '区域通关!',
    'clear_message_stage': '关卡通关!',
    'fishing_alert': '注意\n钓鱼槽!',
    'message_avoid': '击退了敌人!',
    'message_bigfish': '是大家伙?!',
    'message_caravan': '商品到货了!',
    'message_enchant': '神珠炼成了!',
    'message_enemy_appear': '怪物出现!',
    'message_enemy_sign': '强敌就在附近!',
    'message_exloginbonus': '额外登录奖励',
    'message_fomos': '召唤圣兽佛莫斯',
    'message_getaway': '敌人逃走了!',
    'message_lost': '战斗失败了…',
    'message_mission': '任务达成',
    'message_skllvup': '技能升级!',
    'message_smith': '武器强化成功',
    'message_traning': '技能锻炼完成!',
    'message_wlvup': '武器升级!',
    'message_won': '胜利!',
}


def white_outline_fill(c):
    s, v = hsv(c)
    return (c[..., 3] > 150) & ~((s < 0.22) & (v > 0.72))


def do_banner(img, name, text, fname='black', log=None):
    s = S[name]
    x, y, w, h = s['x'], s['y'], s['w'], s['h']
    c = img[y:y + h, x:x + w].copy()
    F = white_outline_fill(c)

    prof = measure_profile(c, F, ring_max=8)
    ext = ring_extent(prof, 20)
    lines = text.count('\n') + 1
    maxw = w - 2 * ext - 2
    maxh = h - 2 * ext - 1
    m, px = fit_mask(text, fname, maxw * 1.25, maxh, spacing=0.02, lines_gap=0.08)
    m = squeeze_mask(m, maxw * SS)  # allow mild horizontal condensing (<=20%) for long phrases
    r, pad = render(m, prof)
    img[y:y + h, x:x + w] = 0
    ox = x + (w - r.shape[1]) // 2
    oy = y + (h - r.shape[0]) // 2
    over(img, r, ox, oy, clip=(x, y, x + w, y + h))
    if log is not None:
        log.append(f'{name}: "{text}" font={fname} {px}px ink={m.shape[1]//SS}x{m.shape[0]//SS} in {w}x{h} ring={ext:.1f}')


def do_logo(img, log=None):
    s = S['logo']
    x, y, w, h = s['x'], s['y'], s['w'], s['h']
    c = img[y:y + h, x:x + w].copy()
    alpha = c[..., 3] > 20
    lab, n = ndi.label(ndi.binary_dilation(alpha, iterations=1))
    sizes = ndi.sum(np.ones_like(lab), lab, range(1, n + 1))
    main = lab == (1 + int(np.argmax(sizes)))
    ys, xs = np.nonzero(main)
    my0, my1 = ys.min(), ys.max()
    # --- subtitle: everything below the main logo art
    sub_reg = np.zeros_like(alpha); sub_reg[my1 + 2:, :] = True
    any_a = c[..., 3] > 0
    mainx = ndi.binary_dilation(main, iterations=2)
    sub = any_a & sub_reg & ~mainx
    # --- arc text: components above / not part of main art, in upper half
    arc = any_a & ~mainx & ~sub_reg
    arc[(np.arange(h) > h * 0.55)[:, None].repeat(w, 1)] = False
    if log is not None:
        log.append(f'logo: main art rows {my0}-{my1}; arc px {arc.sum()}, subtitle px {sub.sum()}')
    sv, vv = hsv(c)
    # subtitle profile: fill = dark brown, outline light
    Fs = sub & (vv < 0.55) & (c[..., 3] > 150)
    prof_s = measure_profile(c, Fs, ring_max=5, ignore=main)
    # arc profile: fill = light cream, outline dark green
    Fa = arc & (vv > 0.75) & (sv < 0.45) & (c[..., 3] > 150)
    prof_a = measure_profile(c, Fa, ring_max=5, ignore=main)
    # erase (with a 1px margin so AA fringes go too, but never touch main art)
    er = ndi.binary_dilation(sub | arc, iterations=2) & ~main
    c[er] = 0
    # subtitle text
    sys_ = np.nonzero(sub)
    sy0, sy1 = sys_[0].min(), sys_[0].max()
    sx0, sx1 = sys_[1].min(), sys_[1].max()
    ext = ring_extent(prof_s, 20)
    avail_h = (h - 1 - ext) - (my1 + 2 + ext)
    m, px = fit_mask('艾尔尼亚王国的日常', 'serifblack', min(w - 2 * ext - 8, (sx1 - sx0) * 1.0), avail_h, spacing=0.06)
    r, pad = render(m, prof_s)
    ox = x + (w - r.shape[1]) // 2
    oy = y + int(round((my1 + 2 + h) / 2 - r.shape[0] / 2))
    canvas = np.zeros_like(img); canvas[y:y + h, x:x + w] = c
    over(canvas, r, ox, oy, clip=(x, y, x + w, y + h))
    if log is not None:
        log.append(f'logo subtitle: 艾尔尼亚王国的日常 serif {px}px ink {m.shape[1]//SS}x{m.shape[0]//SS}')
    # arc text: fit circle to arc component centroids
    ay, ax = np.nonzero(arc)
    A_ = np.c_[ax, ay, np.ones_like(ax)]
    bvec = -(ax ** 2 + ay ** 2)
    sol = np.linalg.lstsq(A_, bvec, rcond=None)[0]
    cx, cy = -sol[0] / 2, -sol[1] / 2
    R = np.sqrt(cx ** 2 + cy ** 2 - sol[2])
    ang = np.degrees(np.arctan2(ay - cy, ax - cx))
    a0, a1 = ang.min(), ang.max()
    # glyph height: thickness of arc band
    rr = np.sqrt((ax - cx) ** 2 + (ay - cy) ** 2)
    band = np.percentile(rr, 98) - np.percentile(rr, 2)
    text = '另一种人生冒险'
    gpx = int(band * 1.2)
    n = len(text)
    rmid = np.median(rr)
    amid = (a0 + a1) / 2
    step = np.degrees(gpx * 1.18 / rmid)
    span = min(step * n, a1 - a0); a0 = amid - span / 2
    layer = np.zeros_like(img)
    for i, ch in enumerate(text):
        t = a0 + span * (i + 0.5) / n
        ms = text_mask_ss(ch, 'black', gpx)
        # rotate so the glyph's up points away from centre
        rot = -(t + 90)
        im = Image.fromarray(ms.astype(np.uint8) * 255).rotate(rot, resample=Image.BICUBIC, expand=True)
        ms2 = np.array(im) > 127
        rg, pad = render(ms2, prof_a)
        px_ = cx + rmid * np.cos(np.radians(t)); py_ = cy + rmid * np.sin(np.radians(t))
        over(layer, rg, int(round(x + px_ - rg.shape[1] / 2)), int(round(y + py_ - rg.shape[0] / 2)), clip=(x, y, x + w, y + h))
    # arc chars must not overlap main art: put behind it
    over(layer, canvas, 0, 0)
    img[y:y + h, x:x + w] = layer[y:y + h, x:x + w]
    if log is not None:
        log.append(f'logo arc: circle c=({cx:.0f},{cy:.0f}) R={R:.0f}, angles {a0:.0f}..{a1:.0f}, glyph {gpx}px')


def main(src, dst, log):
    img = load(src)
    for n, t in BANNERS.items():
        do_banner(img, n, t, log=log)
    do_logo(img, log)
    save(img, dst)


if __name__ == '__main__':
    log = []
    main(ROOT + '/repo/base/images_en/Sub1_Atlas_en.png', sys.argv[1] if len(sys.argv) > 1 else ROOT + '/repo/images_zh/Sub1_Atlas_en.png', log)
    print('\n'.join(log))
