"""call_en / meal_*_en: animation frame sheets (2x2 or 2x1 frames)."""
import sys, os
import numpy as np
from scipy import ndimage as ndi
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = ROOT + '/repo/base/images_en/'


def frames(img, nx, ny):
    H, W = img.shape[:2]
    fw, fh = W // nx, H // ny
    return [(i * fw, j * fh, fw, fh) for j in range(ny) for i in range(nx)]


def outlined_word(img, box, text, rot, fname='cute', log=None, grow=1.15, fill_pred=None, ring_cut=None):
    x, y, w, h = box
    c = img[y:y + h, x:x + w].copy()
    if c[..., 3].max() == 0:
        return
    s, v = hsv(c)
    F = (c[..., 3] > 150) & ((v > 0.75) if fill_pred is None else fill_pred(c))
    if F.sum() < 5:
        return
    prof = measure_profile(c, F, ring_max=4)
    ext = ring_extent(prof, 30)
    ys, xs = np.nonzero(c[..., 3] > 20)
    bw, bh = xs.max() - xs.min() + 1, ys.max() - ys.min() + 1
    cx, cy = (xs.min() + xs.max() + 1) / 2, (ys.min() + ys.max() + 1) / 2
    maxw = min(bw * grow, w - 2 * ext - 2) - 2 * ext
    maxh = min(max(bh * 1.6, h * 0.45), h - 2 * ext - 2) - 2 * ext
    m, px = fit_mask(text, fname, maxw, maxh, rotate=rot)
    r, pad = render(m, prof, ring_cut=ring_cut if ring_cut is not None else ext)
    c[:] = 0
    over(c, r, int(round(cx - r.shape[1] / 2)), int(round(cy - r.shape[0] / 2)), clip=(0, 0, w, h))
    img[y:y + h, x:x + w] = c
    if log is not None:
        log.append(f'  frame {box}: {text} {px}px rot {rot}')


def bubble(img, box, text, bold, rot, log=None):
    """white speech bubble with black outline, black text inside"""
    x, y, w, h = box
    c = img[y:y + h, x:x + w].copy()
    s, v = hsv(c)
    op = c[..., 3] > 128
    dark = op & (v < 0.5)
    whitepx = op & (v >= 0.5)
    # bubble silhouette; outline ring = the dark band along its edge
    sil = ndi.binary_fill_holes(op)
    lab, n = ndi.label(sil)
    sizes = ndi.sum(np.ones_like(lab), lab, range(1, n + 1))
    sil = lab == 1 + int(np.argmax(sizes))
    d = ndi.distance_transform_edt(sil)
    ring_w = float(np.median(d[dark & (d < 6)])) * 2 + 0.5
    interior = sil & (d > ring_w)
    letters = interior & (op & (v < 0.92))
    # also note: glyph AA; repaint interior white
    wcol = np.median(c[whitepx & interior], axis=0)
    ys, xs = np.nonzero(letters)
    lx0, ly0, lx1, ly1 = xs.min(), ys.min(), xs.max(), ys.max()
    c[interior] = wcol
    # draw text
    F = np.zeros(op.shape, bool)
    prof = dict(fill=np.tile(np.array([40, 30, 30, 255], np.float32), (3, 5, 1)), dmax=1.0,
                ring=np.zeros((8, 4), np.float32))
    if bold:
        prof['ring'][:2] = [40 * 1.0, 30, 30, 255]  # premultiplied (alpha 255 → same)
    # available area: interior bbox shrunk
    iy, ix = np.nonzero(ndi.binary_erosion(interior, iterations=3))
    aw, ah = ix.max() - ix.min() + 1, iy.max() - iy.min() + 1
    lines = text.count('\n') + 1
    m, px = fit_mask(text, 'cute', aw * 0.92 - (2 if bold else 0), ah * 0.9 - (2 if bold else 0), rotate=rot, lines_gap=0.0)
    r, pad = render(m, prof, ring_cut=1.0 if bold else 0)
    cx, cy = (ix.min() + ix.max() + 1) / 2, (iy.min() + iy.max() + 1) / 2
    layer = np.zeros_like(c)
    over(layer, r, int(round(cx - r.shape[1] / 2)), int(round(cy - r.shape[0] / 2)))
    layer[~interior] = 0
    over(c, layer, 0, 0)
    img[y:y + h, x:x + w] = c
    if log is not None:
        log.append(f'  bubble {box}: {text!r} {px}px')


def splat(img, box, text, rot, log=None):
    x, y, w, h = box
    c = img[y:y + h, x:x + w].copy()
    red = (c[..., 0] > 120) & (c[..., 0] > c[..., 1] * 1.6) & (c[..., 3] > 100)
    if red.sum() < 5:
        return
    F = red & (c[..., 1] < 80)
    prof = measure_profile(c, F, ring_max=2)
    E = ndi.binary_dilation(red, iterations=1) & (c[..., 3] > 0)
    s, v = hsv(c)
    base = np.median(c[(c[..., 3] > 250) & ~E & (v < 0.35)], axis=0)
    c[E, :3] = base[:3]
    ys, xs = np.nonzero(red)
    bw, bh = xs.max() - xs.min() + 1, ys.max() - ys.min() + 1
    cx, cy = (xs.min() + xs.max() + 1) / 2, (ys.min() + ys.max() + 1) / 2
    m, px = fit_mask(text, 'cute', bw * 0.9, bh * 1.25, rotate=rot)
    r, pad = render(m, prof, ring_cut=0.5)
    over(c, r, int(round(cx - r.shape[1] / 2)), int(round(cy - r.shape[0] / 2)), clip=(0, 0, w, h))
    img[y:y + h, x:x + w] = c
    if log is not None:
        log.append(f'  splat {box}: {text} {px}px')


def call(log):
    img = load(SRC + 'call_en.png')
    H, W = img.shape[:2]
    for i, (x, y, w, h) in enumerate(frames(img, 2, 1)):
        c = img[y:y + h, x:x + w].copy()
        # the word sits between the side spikes: find red+white glyph block in the centre band
        s, v = hsv(c)
        redF = (c[..., 3] > 150) & (c[..., 0] > 150) & (c[..., 1] < 90) & (c[..., 2] < 110)
        colmask = np.zeros_like(redF); colmask[:, int(w * 0.25):int(w * 0.78)] = True
        F = redF & colmask
        ys, xs = np.nonzero(F)
        prof = measure_profile(c, F, ring_max=3)
        ext = ring_extent(prof, 30)
        x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
        er = np.zeros_like(F); er[max(y0 - 4, 0):y1 + 5, max(x0 - 4, 0):x1 + 5] = True
        # do not wipe the spikes: only pixels that are red text or white outline
        textish = ndi.binary_dilation(F, iterations=int(np.ceil(ext)) + 1)
        c[er & textish] = 0
        m, px = fit_mask('喂~!', 'black', (x1 - x0 + 1) * 1.0, (y1 - y0 + 1) * 1.3)
        r, pad = render(m, prof)
        over(c, r, int(round((x0 + x1 + 1) / 2 - r.shape[1] / 2)), int(round((y0 + y1 + 1) / 2 - r.shape[0] / 2)),
             clip=(max(x0 - 6, 0), 0, min(x1 + 7, w), h))
        img[y:y + h, x:x + w] = c
        log.append(f'  call frame {i}: 喂~! {px}px ink {m.shape[1]//SS}x{m.shape[0]//SS}')
    return img


def main(outdir, log):
    os.makedirs(outdir, exist_ok=True)
    log.append('call_en'); save(call(log), outdir + '/call_en.png')
    # meal_1: Sweet bubbles (row 0); sparkles row 1 untouched
    log.append('meal_1_en'); img = load(SRC + 'meal_1_en.png'); fr = frames(img, 2, 2)
    bubble(img, fr[0], '好甜', False, 0, log); bubble(img, fr[1], '好甜', True, 0, log)
    save(img, outdir + '/meal_1_en.png')
    log.append('meal_2_en'); img = load(SRC + 'meal_2_en.png'); fr = frames(img, 2, 2)
    for b, rot in zip(fr, (6, -4, 3, -6)):
        bubble(img, b, '好吃♪', False, rot, log)
    save(img, outdir + '/meal_2_en.png')
    for name, word in (('meal_3_en', '吧唧'), ('meal_4_en', '干巴巴'), ('meal_5_en', '嚼不动')):
        log.append(name); img = load(SRC + name + '.png'); fr = frames(img, 2, 2)
        for b, rot in zip(fr, (8, -3, 4, -6)):
            outlined_word(img, b, word, rot, log=log)
        save(img, outdir + '/' + name + '.png')
    log.append('meal_6_en'); img = load(SRC + 'meal_6_en.png'); fr = frames(img, 2, 2)
    for b, rot in zip(fr, (8, 4, 6, 10)):
        splat(img, b, '噗!', rot, log)
    save(img, outdir + '/meal_6_en.png')


if __name__ == '__main__':
    log = []
    main(sys.argv[1] if len(sys.argv) > 1 else ROOT + '/repo/images_zh', log)
    print('\n'.join(log))
