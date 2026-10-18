"""Helpers for redrawing Chinese text into Elnea Kingdom textures.

Style model: a text is a binary glyph mask B.  Its look is captured as a *profile*
measured from the original English sprite:
  - fill colour as a function of (normalised x across the text, distance inside the glyph edge)
  - ring colour (outline, glow, shadow) as a function of distance outside the glyph edge
The new Chinese mask is rendered with the same profile at 4x supersampling, then box-downsampled.
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage as ndi

SS = 4
FONT_DIR = __import__('os').path.join(__import__('os').path.dirname(__file__), '..', 'fonts_img')
FONTS = {
    'black': FONT_DIR + '/NotoSansSC-Black.ttf',
    'bold': FONT_DIR + '/NotoSansSC-Bold.ttf',
    'serif': FONT_DIR + '/NotoSerifSC-SemiBold.ttf',
    'serifblack': FONT_DIR + '/NotoSerifSC-Black.ttf',
    'cute': FONT_DIR + '/ZCOOLKuaiLe-Regular.ttf',
    'medium': FONT_DIR + '/../../repo/fonts/zh.ttf',
}
_fc = {}


def font(name, px):
    k = (name, int(px))
    if k not in _fc:
        _fc[k] = ImageFont.truetype(FONTS.get(name, name), int(px))
    return _fc[k]


def load(path):
    return np.array(Image.open(path).convert('RGBA')).astype(np.float32)


def save(arr, path):
    Image.fromarray(np.clip(arr + 0.5, 0, 255).astype(np.uint8), 'RGBA').save(path)


def hsv(a):
    rgb = a[..., :3] / 255.0
    mx = rgb.max(-1); mn = rgb.min(-1)
    s = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    return s, mx


# ---------------------------------------------------------------- text masks
_cm = {}


def has_glyph(fname, ch):
    if fname not in _cm:
        from fontTools.ttLib import TTFont
        _cm[fname] = set(TTFont(FONTS.get(fname, fname), lazy=True).getBestCmap().keys())
    return ord(ch) in _cm[fname]


def text_mask_ss(text, fname, px, spacing=0.0, lines_gap=0.1, rotate=0.0, align='center'):
    """Binary mask at SSx resolution, cropped tight to ink. Multi-line with '\n'."""
    f = font(fname, px * SS)
    fb = font('black', px * SS)
    lines = text.split('\n')
    imgs = []
    for ln in lines:
        # per-char drawing to support letter spacing
        fs_ = [f if has_glyph(fname, c) else fb for c in ln]
        widths = [ff.getlength(c) for ff, c in zip(fs_, ln)]
        W = int(sum(widths) + spacing * SS * px * max(len(ln) - 1, 0) + px * SS * 2)
        H = int(px * SS * 2)
        im = Image.new('L', (W, H), 0)
        d = ImageDraw.Draw(im)
        x = px * SS * 0.5
        for c, w, ff in zip(ln, widths, fs_):
            d.text((x, px * SS * 0.3), c, font=ff, fill=255)
            x += w + spacing * SS * px
        a = np.array(im) > 127
        imgs.append(a)
    # crop each line to ink vertically using the font's full line box so lines align
    out = []
    for a in imgs:
        ys, xs = np.nonzero(a)
        if len(xs) == 0:
            out.append(np.zeros((1, 1), bool)); continue
        out.append(a[ys.min():ys.max() + 1, xs.min():xs.max() + 1])
    if len(out) == 1:
        m = out[0]
    else:
        gap = int(lines_gap * px * SS)
        W = max(o.shape[1] for o in out)
        H = sum(o.shape[0] for o in out) + gap * (len(out) - 1)
        m = np.zeros((H, W), bool); y = 0
        for o in out:
            x = (W - o.shape[1]) // 2 if align == 'center' else 0
            m[y:y + o.shape[0], x:x + o.shape[1]] = o; y += o.shape[0] + gap
    if rotate:
        im = Image.fromarray(m.astype(np.uint8) * 255).rotate(rotate, resample=Image.BICUBIC, expand=True)
        m = np.array(im) > 127
        ys, xs = np.nonzero(m); m = m[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return m


def fit_mask(text, fname, maxw, maxh, spacing=0.0, rotate=0.0, lines_gap=0.1, hmax_px=None, minpx=6):
    """Largest font size whose ink fits maxw x maxh (1x pixels). Returns (mask_ss, px)."""
    lo, hi = minpx, int((hmax_px or maxh) * 2.5) + 2
    best = None
    while lo <= hi:
        mid = (lo + hi) // 2
        m = text_mask_ss(text, fname, mid, spacing, lines_gap, rotate)
        if m.shape[1] <= maxw * SS and m.shape[0] <= maxh * SS:
            best = (m, mid); lo = mid + 1
        else:
            hi = mid - 1
    if best is None:
        best = (text_mask_ss(text, fname, minpx, spacing, lines_gap, rotate), minpx)
    return best


def squeeze_mask(m, maxw_ss):
    """Horizontally compress a mask (condensed lettering) to at most maxw_ss."""
    if m.shape[1] <= maxw_ss:
        return m
    im = Image.fromarray(m.astype(np.uint8) * 255).resize((int(maxw_ss), m.shape[0]), Image.BILINEAR)
    return np.array(im) > 127


# ---------------------------------------------------------------- style profile
RING_STEP = 0.5


def measure_profile(rgba, F, nx=3, nd=5, ring_max=10.0, ignore=None):
    """rgba: HxWx4 float (straight alpha). F: bool fill mask. ignore: bool mask excluded from ring sampling."""
    din = ndi.distance_transform_edt(F) - 0.5
    dout = ndi.distance_transform_edt(~F) - 0.5
    ys, xs = np.nonzero(F)
    x0, x1 = xs.min(), xs.max()
    y0, y1 = ys.min(), ys.max()
    prem = rgba.copy(); prem[..., :3] *= rgba[..., 3:4] / 255.0
    # fill table
    xn = np.clip((np.arange(rgba.shape[1]) - x0) / max(x1 - x0, 1), 0, 1)
    XN = np.broadcast_to(xn[None, :], F.shape)
    fill = np.zeros((nx, nd, 4), np.float32)
    dmax = max(din[F].max(), 1)
    for i in range(nx):
        for j in range(nd):
            sel = F & (XN >= i / nx) & (XN <= (i + 1) / nx) & (din >= j * dmax / nd) & (din <= (j + 1) * dmax / nd + 0.01)
            if sel.sum() < 2:
                sel = F & (din >= j * dmax / nd) & (din <= (j + 1) * dmax / nd + 0.01)
            if sel.sum() < 1:
                sel = F
            fill[i, j] = np.median(rgba[sel], axis=0)
    nb = int(ring_max / RING_STEP)
    ring = np.zeros((nb, 4), np.float32)
    ok = ~F if ignore is None else (~F & ~ignore)
    for k in range(nb):
        sel = ok & (dout > k * RING_STEP - 0.25) & (dout <= (k + 1) * RING_STEP + 0.25)
        if sel.sum() == 0:
            ring[k] = ring[k - 1] if k else 0; continue
        p = np.median(prem[sel], axis=0)
        ring[k] = p
    return dict(fill=fill, dmax=float(dmax), ring=ring, height=int(y1 - y0 + 1), width=int(x1 - x0 + 1),
                bbox=(int(x0), int(y0), int(x1), int(y1)))


def ring_extent(prof, alpha_thr=12):
    r = prof['ring']
    k = len(r)
    while k > 0 and r[k - 1, 3] < alpha_thr:
        k -= 1
    return k * RING_STEP


def render(Bss, prof, ring_cut=None, fill_override=None, pad=None):
    """Render SS-res mask with profile -> straight-alpha RGBA float at 1x, plus (offset) of mask origin.
    Returns (img, pad) where img includes `pad` 1x pixels around the mask box."""
    ring = prof['ring'].copy()
    if ring_cut is not None:
        n = int(ring_cut / RING_STEP)
        ring[n:] = 0
    ext = len(ring) * RING_STEP
    if pad is None:
        pad = int(np.ceil(ext)) + 1
    # pad mask so dims are multiples of SS
    h, w = Bss.shape
    H = int(np.ceil((h + 2 * pad * SS) / SS) * SS); W = int(np.ceil((w + 2 * pad * SS) / SS) * SS)
    B = np.zeros((H, W), bool)
    oy, ox = pad * SS, pad * SS
    B[oy:oy + h, ox:ox + w] = Bss
    din = ndi.distance_transform_edt(B) / SS
    dout = ndi.distance_transform_edt(~B) / SS
    out = np.zeros((H, W, 4), np.float32)
    # inside
    fill = prof['fill'] if fill_override is None else fill_override
    nx, nd = fill.shape[:2]
    xs = np.nonzero(B.any(0))[0]
    x0, x1 = xs.min(), xs.max()
    xn = np.clip((np.arange(W) - x0) / max(x1 - x0, 1), 0, 0.9999)
    XN = np.broadcast_to(xn[None, :], B.shape)
    fi = XN * nx - 0.5
    i0 = np.clip(np.floor(fi).astype(int), 0, nx - 1); i1 = np.clip(i0 + 1, 0, nx - 1); ti = np.clip(fi - np.floor(fi), 0, 1)
    dm = max(np.percentile(din[B], 97), 0.5) if B.any() else 1
    fd = np.clip(din / dm, 0, 0.9999) * nd - 0.5
    j0 = np.clip(np.floor(fd).astype(int), 0, nd - 1); j1 = np.clip(j0 + 1, 0, nd - 1); tj = np.clip(fd - np.floor(fd), 0, 1)
    c = (fill[i0, j0] * ((1 - ti) * (1 - tj))[..., None] + fill[i1, j0] * (ti * (1 - tj))[..., None]
         + fill[i0, j1] * ((1 - ti) * tj)[..., None] + fill[i1, j1] * (ti * tj)[..., None])
    prem_in = c.copy(); prem_in[..., :3] *= c[..., 3:4] / 255.0
    # outside
    fk = dout / RING_STEP - 0.5
    k0 = np.clip(np.floor(fk).astype(int), 0, len(ring) - 1); k1 = np.clip(k0 + 1, 0, len(ring) - 1); tk = np.clip(fk - np.floor(fk), 0, 1)[..., None]
    prem_out = ring[k0] * (1 - tk) + ring[k1] * tk
    prem_out[dout > ext] = 0
    prem = np.where(B[..., None], prem_in, prem_out)
    # downsample
    prem = prem.reshape(H // SS, SS, W // SS, SS, 4).mean((1, 3))
    a = prem[..., 3:4]
    rgb = np.where(a > 0, prem[..., :3] * 255.0 / np.maximum(a, 1e-6), 0)
    return np.concatenate([np.clip(rgb, 0, 255), a], -1), pad


def over(dst, src, x, y, clip=None):
    """Alpha-composite src (straight) onto dst (straight) at (x,y). clip=(x0,y0,x1,y1) exclusive bounds."""
    H, W = dst.shape[:2]
    cx0, cy0, cx1, cy1 = clip if clip else (0, 0, W, H)
    h, w = src.shape[:2]
    ax0, ay0 = max(x, cx0), max(y, cy0)
    ax1, ay1 = min(x + w, cx1), min(y + h, cy1)
    if ax1 <= ax0 or ay1 <= ay0:
        return
    s = src[ay0 - y:ay1 - y, ax0 - x:ax1 - x]
    d = dst[ay0:ay1, ax0:ax1]
    sa = s[..., 3:4] / 255.0; da = d[..., 3:4] / 255.0
    oa = sa + da * (1 - sa)
    orgb = np.where(oa > 0, (s[..., :3] * sa + d[..., :3] * da * (1 - sa)) / np.maximum(oa, 1e-6), 0)
    dst[ay0:ay1, ax0:ax1] = np.concatenate([orgb, oa * 255.0], -1)


def ink_box(img, thr=8):
    ys, xs = np.nonzero(img[..., 3] > thr)
    return xs.min(), ys.min(), xs.max(), ys.max()


def row_inpaint(rgba, E, valid=None, passes=1):
    """Fill masked pixels by per-row linear interpolation between nearest valid neighbours,
    blended with a per-column pass; good for pills/gradients. Alpha untouched."""
    out = rgba.copy()
    H, W = E.shape
    valid = ~E if valid is None else (valid & ~E)
    resr = out.copy(); wr = np.zeros((H, W), np.float32)
    for y in range(H):
        row = E[y]
        if not row.any():
            continue
        x = 0
        while x < W:
            if row[x]:
                x1 = x
                while x1 < W and row[x1]:
                    x1 += 1
                l = x - 1; r = x1
                while l >= 0 and not valid[y, l]: l -= 1
                while r < W and not valid[y, r]: r += 1
                for xx in range(x, x1):
                    if l >= 0 and r < W:
                        t = (xx - l) / (r - l)
                        resr[y, xx, :3] = out[y, l, :3] * (1 - t) + out[y, r, :3] * t
                    elif l >= 0:
                        resr[y, xx, :3] = out[y, l, :3]
                    elif r < W:
                        resr[y, xx, :3] = out[y, r, :3]
                x = x1
            else:
                x += 1
    out[E, :3] = resr[E, :3]
    # light smoothing inside mask only
    for _ in range(passes):
        bl = np.stack([ndi.uniform_filter(out[..., c], 3) for c in range(3)], -1)
        out[E, :3] = bl[E]
    return out
