import json, sys, os
import numpy as np
from scipy import ndimage as ndi
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
A = json.load(open(ROOT + '/work/atlas_sprites.json'))
S = {s['name']: s for s in A['Main_Atlas_en']}

# icon + label under it (white fill, brown outline)
MENU = {
    'icon_anim_config': '速度设置',
    'icon_auto_battle': '自动战斗',
    'icon_auto_search': '自动',
    'icon_dungeon_item': '道具',
    'menu_character': '角色',
    'menu_combat': '战斗',
    'menu_exit': '退出',
    'menu_fast': '自动移动',
    'menu_friend': '好友',
    'menu_giftbox': '礼物箱',
    'menu_guide': '指南',
    'menu_kingdom': '王国',
    'menu_list': '摆放列表',
    'menu_lottery': '抽签',
    'menu_notice': '公告',
    'menu_quest': '任务',
    'menu_remove': '全部收回',
    'menu_setting': '设置',
    'menu_shop': '商店',
    'menu_support': '支援',
    'menu_warp': '转移魔法',
}


def crop(img, n):
    s = S[n]
    return s['x'], s['y'], s['w'], s['h']


def is_white(c):
    return (c[..., 3] > 200) & (c[..., 0] > 235) & (c[..., 1] > 230) & (c[..., 2] > 215)


def text_rows(F):
    """lower contiguous block of rows containing the label"""
    rows = F.sum(1)
    h = len(rows)
    ys = [y for y in range(h) if rows[y] > 3]
    last = ys[-1]
    # walk up while rows are dense
    top = last
    while top - 1 >= 0 and rows[top - 1] > 3:
        top -= 1
    # include light rows (ascender tips) just above / below
    while top - 1 >= 0 and rows[top - 1] > 0 and top > last - 24:
        top -= 1
    bot = last
    while bot + 1 < h and rows[bot + 1] > 0:
        bot += 1
    return top, bot


def do_menu(img, n, text, log):
    x, y, w, h = crop(img, n)
    c = img[y:y + h, x:x + w].copy()
    W = is_white(c)
    top, bot = text_rows(W)
    F = W.copy(); F[:top] = False
    prof = measure_profile(c, F, ring_max=4)
    ow = 2.75  # outline width taken from the original (~2-3px brown)
    # erase old label: text + its outline
    E = ndi.binary_dilation(F, iterations=3)
    E[:max(top - 3, 0)] = False
    c[E] = 0
    # drop leftover specks of the old label inside the band
    lab, k = ndi.label(c[..., 3] > 0)
    for i in range(1, k + 1):
        comp = lab == i
        ys_ = np.nonzero(comp)[0]
        if comp.sum() < 15 and ys_.min() >= top - 3:
            c[comp] = 0
    lines = text.count('\n') + 1
    avail_w = w - 2 * ow - 1
    cap = bot - top + 1
    target_h = min(cap + 3, h - 2 * ow)
    m, px = fit_mask(text, 'black', avail_w, target_h, spacing=0.0)
    r, pad = render(m, prof, ring_cut=ow)
    rh, rw = r.shape[:2]
    # bottom of ink sits on original label bottom; centred on sprite
    ox = (w - rw) // 2
    mh = int(np.ceil(m.shape[0] / SS))
    ink_bot = min(bot, h - 1 - int(np.ceil(ow)))
    oy = ink_bot - (pad + mh - 1)
    over(c, r, ox, oy, clip=(0, 0, w, h))
    img[y:y + h, x:x + w] = c
    log.append(f'{n}: {text} {px}px ink {m.shape[1]//SS}x{m.shape[0]//SS} (label rows {top}-{bot}, sprite {w}x{h})')


def do_event(img, log):
    n = 'menu_event'
    x, y, w, h = crop(img, n)
    c = img[y:y + h, x:x + w].copy()
    F = is_white(c)
    prof = measure_profile(c, F, ring_max=4)
    ow = 2.75
    m, px = fit_mask('限时\n活动', 'black', w - 2 * ow - 1, h - 2 * ow, lines_gap=0.06)
    r, pad = render(m, prof, ring_cut=ow)
    c[:] = 0
    over(c, r, (w - r.shape[1]) // 2, (h - r.shape[0]) // 2, clip=(0, 0, w, h))
    img[y:y + h, x:x + w] = c
    log.append(f'{n}: 限时/活动 {px}px')


def pill(img, n, text, pred, log, rotate=0.0, region=None, ring_cut=0.8, dil=2, fname='black', boxpad=(0, 0), scale_h=1.0,
         fill_rgba=None, valid=None, valid_fn=None, fixbox=None):
    """Erase text matched by pred inside region (sprite coords) by row interpolation; draw new text."""
    x, y, w, h = crop(img, n)
    c = img[y:y + h, x:x + w].copy()
    reg = np.zeros((h, w), bool)
    if region is None:
        reg[:] = True
    else:
        rx0, ry0, rx1, ry1 = region; reg[ry0:ry1, rx0:rx1] = True
    F = pred(c) & reg
    # drop specks
    lab, k = ndi.label(F)
    if k:
        sz = ndi.sum(np.ones_like(lab), lab, range(1, k + 1))
        F = np.isin(lab, 1 + np.nonzero(sz >= 3)[0])
    prof = measure_profile(c, F, ring_max=4)
    if fill_rgba is not None:
        prof['fill'][:] = np.array(fill_rgba, np.float32)
    E = ndi.binary_dilation(F, iterations=dil) & reg
    if valid_fn is not None:
        valid = valid_fn(c)
    c = row_inpaint(c, E, valid=valid)
    ys, xs = np.nonzero(F)
    bx0, by0, bx1, by1 = xs.min(), ys.min(), xs.max(), ys.max()
    bw = bx1 - bx0 + 1 + boxpad[0]; bh = (by1 - by0 + 1) * scale_h + boxpad[1]
    if fixbox:
        bw, bh = fixbox
        bx0, bx1 = (w - bw) // 2, (w - bw) // 2 + bw - 1
    m, px = fit_mask(text, fname, bw, bh, rotate=rotate)
    r, pad = render(m, prof, ring_cut=ring_cut)
    cx = (bx0 + bx1 + 1) / 2; cy = (by0 + by1 + 1) / 2
    over(c, r, int(round(cx - r.shape[1] / 2)), int(round(cy - r.shape[0] / 2)), clip=(0, 0, w, h))
    img[y:y + h, x:x + w] = c
    log.append(f'{n}: {text} {px}px ink {m.shape[1]//SS}x{m.shape[0]//SS} in box {bw}x{bh:.0f}')


def band_text(img, n, text, log, fname='bold'):
    """Sprites with ornament on top and a separate text band at the bottom (blank rows between)."""
    x, y, w, h = crop(img, n)
    c = img[y:y + h, x:x + w].copy()
    rows = (c[..., 3] > 10).sum(1)
    yb = h - 1
    while rows[yb] <= 8: yb -= 1          # skip faint bottom padding
    while rows[yb] > 0: yb -= 1           # walk up through the text
    top = yb + 1
    F = (c[..., 3] > c[top:, :, 3].max() * 0.6); F[:top] = False
    prof = measure_profile(c, F, ring_max=3)
    c[top:] = 0
    ys, xs = np.nonzero(F)
    bh = ys.max() - ys.min() + 1
    m, px = fit_mask(text, fname, w - 6, min(h - top - 1, bh + 4), spacing=0.12)
    r, pad = render(m, prof, ring_cut=1.0)
    over(c, r, (w - r.shape[1]) // 2, int(round((top + h) / 2 - r.shape[0] / 2)), clip=(0, top, w, h))
    img[y:y + h, x:x + w] = c
    log.append(f'{n}: {text} {px}px rows {top}-{h}')


def main(src, dst, log):
    img = load(src)
    for n, t in MENU.items():
        do_menu(img, n, t, log)
    do_event(img, log)
    white = lambda c: (c[..., 0] > 200) & (c[..., 1] > 200) & (c[..., 2] > 200) & (c[..., 3] > 100)

    def dark(c):
        s, v = hsv(c); return (v < 0.45) & (c[..., 3] > 100)

    def darkish(c):
        s, v = hsv(c); return (v < 0.72) & (c[..., 3] > 100)

    def yellow(c):  # yellow/orange letter fill
        return (c[..., 0] > 170) & (c[..., 1] > 120) & (c[..., 2] < 110) & (c[..., 3] > 100)

    # round orange button
    pill(img, 'button_all', '全部', white, log, region=(8, 18, 64, 54), boxpad=(4, 0))
    # skill range pills (pink, white text)
    for n, t in [('icon_skill_long', '远距'), ('icon_skill_middle', '中距'), ('icon_skill_short', '近距'),
                 ('icon_skill_single', '单体'), ('icon_skill_multi', '群体')]:
        pill(img, n, t, lambda c: (hsv(c)[0] < 0.42) & (hsv(c)[1] > 0.7) & (c[..., 3] > 200), log,
             region=(0, 0, 64, 27), dil=2, valid_fn=lambda c: c[..., 3] > 250, fixbox=(42, 17))
    # tilted banners
    pill(img, 'deliver_ptup', '点数 +', white, log, rotate=7, region=(8, 4, 145, 46), boxpad=(-6, 0))
    def in_pill(c):
        s, v = hsv(c)
        col = (s > 0.45) & (c[..., 3] > 200)
        body = ndi.binary_fill_holes(ndi.binary_closing(col, iterations=3))
        return white(c) & ndi.binary_erosion(body, iterations=1)

    for n in ('discount_off', 'discount_on'):
        pill(img, n, '折扣', in_pill, log, rotate=3, region=(4, 4, 148, 36), boxpad=(0, 2))
        pill(img, n, '购买', darkish, log, region=(60, 37, 151, 64), boxpad=(0, 0), ring_cut=0.5, fname='bold', dil=1)
    for n in ('priceup_off', 'priceup_on'):
        pill(img, n, '售价 +', in_pill, log, rotate=3, region=(4, 2, 148, 38), boxpad=(-6, 2))
        pill(img, n, '出售', darkish, log, region=(60, 39, 151, 64), boxpad=(0, 0), ring_cut=0.5, fname='bold', dil=1)
    pill(img, 'premium_plate', '尊享', lambda c: (hsv(c)[1] < 0.72) & (c[..., 3] > 100), log, region=(6, 4, 78, 20), boxpad=(4, 3), ring_cut=0.5, dil=2, fill_rgba=(56, 44, 40, 255))
    # glossy plates with outlined gradient text
    out_or_yel = lambda c: yellow(c) | (dark(c) & False)
    pill(img, 'icon_bonus', '附赠品', yellow, log, region=(12, 1, 120, 31), ring_cut=2.2, dil=5, boxpad=(-6, 6))
    pill(img, 'icon_sale', '特卖中!', yellow, log, region=(12, 1, 122, 31), ring_cut=2.2, dil=5, boxpad=(-2, 6))
    band_text(img, 'tap2go_icon', '点击继续', log)
    band_text(img, 'figure_rotate', '点击旋转', log, fname='black')
    save(img, dst)


if __name__ == '__main__':
    log = []
    main(ROOT + '/repo/base/images_en/Main_Atlas_en.png', sys.argv[1] if len(sys.argv) > 1 else ROOT + '/repo/images_zh/Main_Atlas_en.png', log)
    print('\n'.join(log))
