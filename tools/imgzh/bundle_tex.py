"""Chinese versions of the English-only textures that live in downloaded AssetBundles
(UnityCache/Shared/<name>.android/<hash>/__data, inventoried in _hark/bundles/cache_inventory.tsv).

usage: python tools/imgzh/bundle_tex.py <src_dir> <out_dir> [png_dir]
  src_dir : original bundles named <bundle>~<hash>.data (+ .info), copied from the phone's UnityCache
  out_dir : content_zh/bundles/  -> <bundle>~<hash>.data (Chinese texture written back) + .info
  png_dir : optional, writes <tex>_zh.png previews

Each label: (x0, y0, x1, y1) box of the English text in image coords (top-left origin), the Chinese text and a
style.  English strokes inside the box are found as pixels that differ from a median-blurred background,
inpainted (OpenCV Telea), then the Chinese text is drawn fitted into the box with a stroke/outline.
Fill/stroke 'L' / 'D' = take the lightest / darkest ink colour measured in the box (keeps dimmed screenshots dim).
Fonts (tools/fonts_img/, not in git): NotoSansSC[wght] instanced at 900/700 -> NotoSansSC-Black/Bold.ttf,
NotoSerifSC[wght] at 900 -> NotoSerifSC-Black.ttf (fontTools.varLib.instancer), or set ZH_FONT_DIR.
Terms follow the game's own translations (Main_Atlas_en buttons, c_weapon, c_item, SystemText).
"""
import os, sys, glob, shutil
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FD = os.environ.get('ZH_FONT_DIR', os.path.join(HERE, '..', 'fonts_img'))
FONTS = {'black': 'NotoSansSC-Black.ttf', 'bold': 'NotoSansSC-Bold.ttf', 'serif': 'NotoSerifSC-Black.ttf'}

W = (255, 255, 255)
# x0,y0,x1,y1, text, style
LABELS = {
 'fig_000_en': [
  (212, 46, 292, 72, '博杜', dict(fill=(60, 60, 60), stroke='L', sw=1, font='bold', thr=30)),
  (4, 330, 64, 366, '限时\n活动', dict(fill=W, stroke=(110, 20, 40), sw=1, font='black')),
  (44, 410, 100, 430, '任务', dict(fill=(215, 215, 215), stroke='D', sw=2, font='black')),
  (6, 472, 64, 494, '支援', dict(fill=(215, 215, 215), stroke='D', sw=2, font='black')),
  (84, 470, 132, 492, '公告', dict(fill=(215, 215, 215), stroke='D', sw=2, font='black')),
  (152, 470, 210, 494, '礼物箱', dict(fill=(215, 215, 215), stroke='D', sw=2, font='black')),
  (302, 472, 364, 492, '转移魔法', dict(fill=(215, 215, 215), stroke='D', sw=2, font='black')),
  (376, 470, 436, 492, '好友', dict(fill=(215, 215, 215), stroke='D', sw=2, font='black')),
  (454, 472, 500, 494, '指南', dict(fill=(215, 215, 215), stroke='D', sw=2, font='black')),
  (408, 408, 472, 430, '自动移动', dict(fill=W, stroke='D', sw=2, font='black')),
 ],
 'fig_001_en': [
  (178, 72, 468, 108, '1. 点一下屏幕。', dict(fill=(70, 40, 45), stroke=W, sw=3, thr=30, grow=3)),
  (26, 310, 498, 374, '2. 手指莫松开，\n往想去的方向滑动。', dict(fill=(70, 40, 45), stroke=W, sw=3, thr=30, grow=3)),
  (78, 458, 506, 496, '3. 手指离开屏幕就停下。', dict(fill=(70, 40, 45), stroke=W, sw=3, thr=30, grow=3)),
 ],
 'fig_009_en': [
  (36, 0, 186, 22, '巴西亚斯浴场', dict(fill='D', sw=0, font='bold', thr=25)),
  (346, 0, 468, 22, '学者协会', dict(fill='D', sw=0, font='bold', thr=25)),
  (72, 60, 188, 84, '喷泉广场', dict(fill='D', sw=0, font='bold', thr=25)),
  (314, 60, 438, 84, '希兹妮神殿', dict(fill='D', sw=0, font='bold', thr=25)),
  (80, 118, 180, 142, '喷泉大街', dict(fill='D', sw=0, font='bold', thr=25)),
  (300, 118, 400, 142, '亚诺市场', dict(fill='D', sw=0, font='bold', thr=25)),
  (42, 176, 180, 200, '城门广场', dict(fill='D', sw=0, font='bold', thr=25)),
  (352, 176, 462, 200, '旧城区大街', dict(fill='D', sw=0, font='bold', thr=25)),
  (68, 234, 154, 258, '郊外大街', dict(fill='D', sw=0, font='bold', thr=25)),
  (328, 234, 486, 258, '药师森林', dict(fill='D', sw=0, font='bold', thr=25)),
  (146, 292, 232, 312, '王立斗技场', dict(fill='D', sw=0, font='bold', thr=25)),
  (104, 348, 148, 372, '大街', dict(fill='D', sw=0, font='bold', thr=25)),
  (336, 348, 402, 372, '牧场', dict(fill='D', sw=0, font='bold', thr=25)),
  (46, 404, 94, 432, '自家', dict(fill='D', sw=0, font='bold', thr=25)),
  (308, 404, 372, 432, '图书室', dict(fill='D', sw=0, font='bold', thr=25)),
  (436, 404, 498, 432, '酒馆', dict(fill='D', sw=0, font='bold', thr=25)),
  (230, 468, 286, 498, '关闭', dict(fill='L', sw=0, font='bold', thr=20)),
  (4, 274, 510, 372, '点一下想去的地方，\n角色就会自己走过去。', dict(fill=W, stroke=(60, 40, 30), sw=3, align='left')),
 ],
 'fig_010_en': [
  (148, 24, 388, 58, '动作按钮', dict(fill=W, stroke=(40, 30, 30), sw=2)),
  (218, 174, 322, 193, '爬上', dict(fill=(30, 25, 20), sw=0, font='bold', thr=30, expand=(0, 5))),
  (224, 234, 292, 258, '塞缪尔', dict(fill=W, sw=0, font='bold', thr=30)),
  (4, 436, 508, 498, '※双击屏幕也能使用\n动作按钮的功能。', dict(fill=W, stroke=(40, 30, 30), sw=2, align='left')),
 ],
 'fig_062_en': [
  (196, 138, 326, 157, '摘花', dict(fill=(30, 25, 20), sw=0, font='bold', thr=30, expand=(0, 5))),
  (204, 192, 294, 220, '博杜', dict(fill=W, sw=0, font='bold', thr=30)),
 ],
 'amber1_1705_en': [
  (10, 126, 250, 160, '琥珀兑换所', dict(fill=W, stroke=(30, 60, 150), sw=3, align='left')),
  (10, 160, 142, 194, '可以兑换', dict(fill=W, stroke=(30, 60, 150), sw=3, align='left')),
  (10, 192, 472, 240, '服装和装备！', dict(fill=(30, 90, 200), stroke=W, sw=3, grow=1, thr=50, align='left')),
 ],
 'amber2_1912_en': [
  (8, 122, 252, 158, '琥珀兑换所', dict(fill=(210, 50, 30), stroke=W, sw=3, align='left')),
  (8, 158, 142, 196, '可以兑换', dict(fill=(210, 50, 30), stroke=W, sw=3, align='left')),
  (8, 194, 278, 244, '道具和染料！', dict(fill=(200, 30, 30), stroke=W, sw=3, align='left')),
 ],
 'event_title_gold_en': [
  (4, 20, 162, 56, '限时活动', dict(fill=(255, 225, 120), stroke=(70, 40, 0), sw=2)),
  (236, 164, 508, 246, '淘金热！', dict(grad=((255, 245, 170), (215, 150, 20)), stroke=(70, 35, 0), sw=3, font='serif')),
 ],
 'gb_interior_2410_en': [
  (8, 4, 214, 32, '点这里查看详情！', dict(fill=W, sw=0, font='bold', thr=30, align='left', maxpx=19)),
  (10, 194, 504, 250, '十连抽更容易抽到\n超稀有家具！', dict(fill=(255, 235, 110), stroke=(60, 40, 0), sw=2, align='right')),
 ],
 'gb_tailor_2011_ihm_en': [
  (8, 4, 214, 32, '点这里查看详情！', dict(fill=W, sw=0, font='bold', thr=30, align='left', maxpx=19)),
  (8, 150, 382, 194, '伊姆和婴儿服装', dict(fill=(40, 160, 70), stroke=W, sw=3, grow=1, thr=50, font='serif', align='left')),
  (200, 192, 500, 248, '十连抽稀有度保底\n★★★★以上！', dict(fill=(255, 235, 110), stroke=(60, 40, 0), sw=2, align='right')),
 ],
 'gb_tailor_2608_ad_en': [
  (8, 4, 214, 32, '点这里查看详情！', dict(fill=W, sw=0, font='bold', thr=30, align='left', maxpx=19)),
  (8, 150, 314, 194, '成人服装', dict(fill=(30, 70, 170), stroke=W, sw=3, grow=1, thr=50, font='serif', align='left')),
  (200, 192, 500, 248, '十连抽稀有度保底\n★★★★以上！', dict(fill=(255, 235, 110), stroke=(60, 40, 0), sw=2, align='right')),
 ],
 'gb_tailor_2608_ch_en': [
  (8, 4, 214, 32, '点这里查看详情！', dict(fill=W, sw=0, font='bold', thr=30, align='left', maxpx=19)),
  (8, 148, 272, 202, '儿童服装', dict(fill=(170, 20, 30), stroke=W, sw=3, grow=1, thr=50, font='serif', align='left')),
  (200, 192, 500, 248, '十连抽稀有度保底\n★★★★以上！', dict(fill=(255, 235, 110), stroke=(60, 40, 0), sw=2, align='right')),
 ],
 'gb_tailor_2608_eq_en': [
  (8, 4, 214, 32, '点这里查看详情！', dict(fill=W, sw=0, font='bold', thr=30, align='left', maxpx=19)),
  (8, 152, 252, 202, '装备', dict(grad=((255, 240, 160), (210, 150, 40)), stroke=W, sw=3, grow=1, thr=50, font='serif', align='left')),
  (200, 192, 500, 248, '十连抽稀有度保底\n★★★★以上！', dict(fill=(255, 235, 110), stroke=(60, 40, 0), sw=2, align='right')),
 ],
 'gb_weapon_2609_en': [
  (8, 4, 214, 32, '点这里查看详情！', dict(fill=W, sw=0, font='bold', thr=30, align='left', maxpx=19)),
  (8, 30, 292, 60, '限定 强化值30%', dict(grad=((255, 240, 160), (210, 150, 40)), stroke=(50, 30, 0), sw=2, font='serif', align='left')),
  (348, 6, 498, 80, '限定\n强化值10%', dict(fill=(220, 220, 230), stroke=(40, 40, 50), sw=2, font='serif', thr=22, grow=3)),
  (4, 58, 116, 92, '零度冰', dict(fill=W, stroke=(40, 90, 200), sw=2, font='serif', align='left')),
  (272, 60, 414, 94, '冰晶', dict(fill=W, stroke=(110, 60, 200), sw=2, font='serif', align='left')),
  (4, 198, 404, 252, '还能抽到「贝戈拉银闪管」\n这种买不到的好道具！', dict(fill=(240, 60, 170), stroke=W, sw=2, align='left')),
 ],
}


def LS(px):
    return int(-px * 0.12)


def font(name, px):
    return ImageFont.truetype(os.path.join(FD, FONTS.get(name, name)), int(px))


def ink_mask(rgb, box, thr=40, grow=2):
    x0, y0, x1, y1 = box
    bg = cv2.medianBlur(rgb, 31)
    d = np.abs(rgb.astype(int) - bg.astype(int)).max(-1)
    m = np.zeros(rgb.shape[:2], np.uint8)
    sub = (d[y0:y1, x0:x1] > thr).astype(np.uint8)
    m[y0:y1, x0:x1] = sub
    if grow:
        m = cv2.dilate(m, np.ones((2 * grow + 1, 2 * grow + 1), np.uint8))
        clip = np.zeros_like(m); clip[max(0, y0 - grow):y1 + grow, max(0, x0 - grow):x1 + grow] = 1
        m &= clip
    return m


def pick(rgb, m, which):
    px = rgb[m > 0].reshape(-1, 3).astype(float)
    if len(px) < 4:
        return (255, 255, 255) if which == 'L' else (0, 0, 0)
    lum = px @ [0.299, 0.587, 0.114]
    q = np.percentile(lum, 88 if which == 'L' else 8)
    sel = px[lum >= q] if which == 'L' else px[lum <= q]
    return tuple(int(v) for v in np.median(sel, 0))


def fit(text, fname, bw, bh, sw, spacing=0, maxpx=None):
    best = None
    for px in range(6, int(maxpx or bh * 1.3) + 1):
        f = font(fname, px)
        im = Image.new('L', (bw * 3 + 50, bh * 3 + 50)); d = ImageDraw.Draw(im)
        d.multiline_text((20, 20), text, font=f, fill=255, stroke_width=sw, spacing=LS(px), align='center')
        bb = im.getbbox()
        if not bb: continue
        if bb[2] - bb[0] <= bw and bb[3] - bb[1] <= bh:
            best = px
        else:
            break
    return best or 6


def draw_label(img, rgba, box, text, st):
    x0, y0, x1, y1 = box
    rgb = np.ascontiguousarray(rgba[..., :3])
    al_ch = np.ascontiguousarray(rgba[..., 3])
    m = ink_mask(rgb, box, st.get('thr', 40), st.get('grow', 2))
    fill = st.get('fill', W); stroke = st.get('stroke', (0, 0, 0))
    if isinstance(fill, str): fill = pick(rgb, m, fill)
    if isinstance(stroke, str): stroke = pick(rgb, m, stroke)
    er = st.get('erase', 'ip')
    if er == 'box':
        m[:] = 0; m[y0:y1, x0:x1] = 1
        rgb[:] = cv2.inpaint(rgb, m, 6, cv2.INPAINT_TELEA)
        al_ch[:] = cv2.inpaint(al_ch, m, 6, cv2.INPAINT_TELEA)
    elif er == 'ip':
        rgb[:] = cv2.inpaint(rgb, m, 4, cv2.INPAINT_TELEA)
        al_ch[:] = cv2.inpaint(al_ch, m, 4, cv2.INPAINT_TELEA)
    elif isinstance(er, tuple):
        rgb[y0:y1, x0:x1] = er
    ex = st.get('expand', (0, 0))
    bw, bh = x1 - x0 + 2 * ex[0], y1 - y0 + 2 * ex[1]
    sw = st.get('sw', 2); fname = st.get('font', 'black')
    px = fit(text, fname, bw, bh, sw, maxpx=st.get('maxpx'))
    f = font(fname, px)
    layer = Image.new('RGBA', (bw * 3 + 60, bh * 3 + 60), (0, 0, 0, 0)); d = ImageDraw.Draw(layer)
    al = st.get('align', 'center')
    if st.get('shadow'):
        d.multiline_text((32, 32), text, font=f, fill=st['shadow'], stroke_width=sw, stroke_fill=st['shadow'],
                         spacing=LS(px), align=al)
    grad = st.get('grad')
    if grad:
        d.multiline_text((30, 30), text, font=f, fill=(0, 0, 0, 0), stroke_width=sw, stroke_fill=stroke,
                         spacing=LS(px), align=al)
        mk = Image.new('L', layer.size); ImageDraw.Draw(mk).multiline_text((30, 30), text, font=f, fill=255,
                                                                          spacing=LS(px), align=al)
        bb = mk.getbbox()
        g = np.zeros((layer.size[1], layer.size[0], 4), np.uint8)
        t = np.clip((np.arange(layer.size[1]) - bb[1]) / max(bb[3] - bb[1], 1), 0, 1)[:, None]
        for c in range(3):
            g[..., c] = (grad[0][c] * (1 - t) + grad[1][c] * t).astype(np.uint8)
        g[..., 3] = np.array(mk)
        layer.alpha_composite(Image.fromarray(g, 'RGBA'))
    else:
        d.multiline_text((30, 30), text, font=f, fill=fill, stroke_width=sw, stroke_fill=stroke,
                         spacing=LS(px), align=al)
    bb = layer.getbbox(); layer = layer.crop(bb)
    lw, lh = layer.size
    if al == 'left':
        ox = x0 - ex[0]
    elif al == 'right':
        ox = x1 + ex[0] - lw
    else:
        ox = x0 + (x1 - x0 - lw) // 2
    oy = y0 + (y1 - y0 - lh) // 2
    rgba[..., :3] = rgb; rgba[..., 3] = al_ch
    base = Image.fromarray(rgba, 'RGBA'); base.alpha_composite(layer, (int(ox), int(oy)))
    rgba[:] = np.array(base)
    return px


def zh_image(name, im):
    rgba = np.array(im.convert('RGBA'))
    log = []
    for (x0, y0, x1, y1, text, st) in LABELS[name]:
        px = draw_label(None, rgba, (x0, y0, x1, y1), text, st)
        log.append(f'{name}: {text!r} {px}px')
    return Image.fromarray(rgba, 'RGBA'), log


def main(src, out, pngdir=None):
    import UnityPy
    os.makedirs(out, exist_ok=True)
    n = 0
    for f in sorted(glob.glob(os.path.join(src, '*.data'))):
        env = UnityPy.load(f)
        changed = False
        for o in env.objects:
            if o.type.name != 'Texture2D': continue
            t = o.read()
            if t.m_Name not in LABELS: continue
            zh, log = zh_image(t.m_Name, t.image)
            print('\n'.join(log))
            if pngdir:
                os.makedirs(pngdir, exist_ok=True); zh.save(os.path.join(pngdir, t.m_Name + '_zh.png'))
            # RGBA32 (format 4): what 13 of the 14 originals already use; UnityPy cannot encode ASTC losslessly
            from UnityPy.enums import TextureFormat
            t.set_image(zh, target_format=TextureFormat.RGBA32)
            t.save(); changed = True; n += 1
        if changed:
            base = os.path.basename(f)
            open(os.path.join(out, base), 'wb').write(env.file.save(packer='lz4'))
            info = f[:-5] + '.info'
            if os.path.isfile(info): shutil.copy(info, os.path.join(out, base[:-5] + '.info'))
    print('textures localized:', n)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
