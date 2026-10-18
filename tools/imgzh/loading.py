"""now_loading_en (640x256 RGB24): rebuild the clean background, draw Chinese."""
import sys, os
import numpy as np
import cv2
from scipy import ndimage as ndi
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
REGION = (130, 78, 510, 210)   # x0,y0,x1,y1 – area holding the text in both versions

HEAD = '正在切换区域'
BODY = '首次进入或人多的区域时，\n读取时间可能会变长。'


def lum(a):
    return a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114


RECTS = [(140, 84, 448, 128), (150, 142, 482, 204)]   # heading / body lines (both versions); avoids the hand at right


def text_mask(a, grow, thr=150):
    m = np.zeros(a.shape[:2], bool)
    for x0, y0, x1, y1 in RECTS:
        m[y0:y1, x0:x1] = lum(a[y0:y1, x0:x1]) > thr
    m = ndi.binary_dilation(m, iterations=grow)
    lim = np.zeros_like(m)
    for x0, y0, x1, y1 in RECTS:
        lim[y0 - 6:y1 + 6, x0 - 6:x1 + 6] = True
    return m & lim


def clean_background(en, jp):
    Me = text_mask(en, 4)
    Mj = text_mask(jp, 7, thr=110)
    out = en.copy()
    take = Me & ~Mj
    out[take] = jp[take]
    hole = Me & Mj
    img8 = np.clip(out, 0, 255).astype(np.uint8)
    res = cv2.inpaint(img8, hole.astype(np.uint8) * 255, 6, cv2.INPAINT_TELEA)
    return res.astype(np.float32), Me, Mj, hole


def draw(bg, text, fname, px_max, maxw, cy, log, spacing=0.04, lines_gap=0.45):
    m, px = fit_mask(text, fname, maxw, px_max * (text.count('\n') + 1) * 1.6, spacing=spacing, lines_gap=lines_gap, hmax_px=px_max)
    # cap size at px_max
    if px > px_max:
        m = text_mask_ss(text, fname, px_max, spacing, lines_gap); px = px_max
    H, W = bg.shape[:2]
    h, w = m.shape
    pad = 8
    big = np.zeros((h + 2 * pad * SS + (-h) % SS, w + 2 * pad * SS + (-w) % SS), np.float32)
    big[pad * SS:pad * SS + h, pad * SS:pad * SS + w] = m
    cov = big.reshape(big.shape[0] // SS, SS, big.shape[1] // SS, SS).mean((1, 3))
    shadow = np.clip(ndi.gaussian_filter(ndi.grey_dilation(cov, size=3), 2.2) * 1.6, 0, 1) * 0.75
    ox = (W - cov.shape[1]) // 2
    oy = int(round(cy - cov.shape[0] / 2))
    reg = bg[oy:oy + cov.shape[0], ox:ox + cov.shape[1]]
    reg[:] = reg * (1 - shadow[..., None])
    white = np.array([255, 252, 244], np.float32)
    reg[:] = reg * (1 - cov[..., None]) + white * cov[..., None]
    log.append(f'now_loading: {text!r} {fname} {px}px ink {w//SS}x{h//SS} at y~{cy}')


def main(dst, log):
    en = np.array(Image.open(ROOT + '/repo/base/images_en/now_loading_en.png').convert('RGB')).astype(np.float32)
    jp = np.array(Image.open(ROOT + '/work/img/ref_now_loading_jp.png').convert('RGB')).astype(np.float32)
    bg, Me, Mj, hole = clean_background(en, jp)
    log.append(f'now_loading: en-text px {Me.sum()}, filled from jp {(Me & ~Mj).sum()}, inpainted {hole.sum()}')
    Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8)).save(ROOT + '/work/img/now_loading_clean.png')
    draw(bg, HEAD, 'medium', 32, 300, 106, log, spacing=0.08)
    draw(bg, BODY, 'medium', 17, 310, 173, log, spacing=0.03)
    Image.fromarray(np.clip(bg + 0.5, 0, 255).astype(np.uint8), 'RGB').save(dst)


if __name__ == '__main__':
    log = []
    main(sys.argv[1] if len(sys.argv) > 1 else ROOT + '/repo/images_zh/now_loading_en.png', log)
    print('\n'.join(log))
