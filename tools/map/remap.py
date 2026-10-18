"""Redraw Chinese labels on the original English large map (map_large_en).
in : map_large_en original (1237x1062)   out: map_zh.png
Labels: box = English text bbox (x0,y0,x1,y1), zh text, style d(dark)/r(red)/l(legend, no halo)/t(title).
Names follow c_dungeon / table usage."""
import sys, cv2, numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SRC, OUT = sys.argv[1], sys.argv[2]
FONT = sys.argv[3] if len(sys.argv) > 3 else 'LXGWWenKai-Bold.ttf'
TFONT = sys.argv[4] if len(sys.argv) > 4 else 'MaShanZheng-Regular.ttf'

L = [
 # NW forests
 ((225, 92, 358, 112), '盖纳森林', 'r'),
 ((135, 123, 265, 145), '瘴气森林', 'r'),
 ((230, 150, 356, 176), '狩猎小径', 'd'),
 ((35, 165, 190, 188), '魔兽森林', 'r'),
 ((459, 172, 549, 196), '瀑布', 'd'),
 ((96, 203, 226, 228), '森林之路', 'd'),
 ((237, 230, 352, 252), '深林', 'r'),
 ((401, 260, 566, 285), '河畔大道', 'd'),
 ((88, 258, 250, 283), '北方森林', 'd'),
 ((265, 288, 370, 310), '森林小径', 'r'),
 ((194, 337, 355, 362), '河畔森林', 'd'),
 ((265, 383, 413, 408), '木桥', 'd'),
 ((545, 330, 637, 355), '水道桥', 'd'),
 ((554, 388, 672, 412), '城堡码头', 'd'),
 ((402, 442, 523, 466), '艾尔涅亚城', 'd'),
 ((110, 434, 266, 460), '练兵场', 'd'),
 ((393, 485, 490, 510), '城堡大街', 'd'),
 ((67, 492, 192, 518), '幸运之塔', 'd'),
 ((558, 482, 622, 505), '学校', 'd'),
 ((612, 515, 678, 540), '神殿', 'd'),
 ((482, 528, 548, 552), '酒馆', 'd'),
 ((305, 528, 370, 548), '巴西亚斯', 'd'),
 ((282, 550, 393, 572), '浴场', 'd'),
 # NE mountains
 ((783, 87, 855, 109), '尼夫之丘', 'd'),
 ((705, 147, 872, 170), '多尔梅坑道', 'd'),
 ((965, 162, 1097, 186), '塔南高炉', 'd'),
 ((673, 227, 905, 252), '山地兵团村', 'd'),
 ((975, 268, 1167, 292), '兵团演武场', 'd'),
 ((952, 405, 1123, 430), '多尔梅山路', 'd'),
 ((810, 465, 957, 490), '北码头', 'd'),
 ((815, 515, 968, 540), '学者协会', 'd'),
 ((1027, 512, 1193, 536), '旧城区遗迹', 'r'),
 # town / south
 ((588, 588, 728, 612), '喷泉广场', 'd'),
 ((582, 590, 708, 614), '亚诺市场', 'd'),
 ((305, 628, 422, 652), '喷泉大街', 'd'),
 ((146, 688, 312, 712), '城门广场', 'd'),
 ((211, 792, 312, 816), '郊外大街', 'd'),
 ((386, 775, 506, 800), '王立斗技场', 'd'),
 ((572, 810, 648, 832), '牧场', 'd'),
 ((166, 870, 250, 894), '农田大街', 'd'),
 ((157, 975, 238, 1000), '果园', 'd'),
 ((690, 560, 855, 585), '大帝桥', 'd'),
 ((1043, 572, 1197, 598), '遗迹森林', 'd'),
 ((930, 648, 1055, 673), '旧城区大街', 'd'),
 ((708, 668, 832, 693), '艾尔涅亚码头', 'd'),
 ((663, 702, 790, 727), '奶酪小屋', 'd'),
 ((814, 723, 1012, 748), '药师森林', 'd'),
 ((903, 767, 1067, 790), '旧城遗迹', 'r'),
 ((680, 782, 793, 807), '拉达小屋', 'd'),
 ((1041, 812, 1192, 836), '禁断遗迹', 'r'),
 ((837, 827, 998, 853), '旧城区森林', 'd'),
 ((936, 860, 1103, 885), '禁断森林', 'd'),
 ((980, 910, 1132, 934), '卡尔内遗迹', 'r'),
 # legend
 ((570, 905, 680, 928), '钓鱼点', 'l'),
 ((570, 945, 683, 970), '补给小屋', 'l'),
 ((570, 993, 690, 1017), '传送树', 'l'),
]
L = [l for l in L if l[1] != '喷泉广场'] + [((398, 588, 538, 612), '喷泉广场', 'd')]
TITLE = ((22, 22, 448, 74), '艾尔涅亚王国')

img = cv2.imread(SRC)
H, W = img.shape[:2]
g = img.mean(2)
mask = np.zeros((H, W), np.uint8)
for (x0, y0, x1, y1), zh, st in L:
    X0, Y0, X1, Y1 = x0 - 3, y0 - 3, x1 + 3, y1 + 3
    sub = img[Y0:Y1, X0:X1].astype(int)
    if st == 'l':
        m = (g[Y0:Y1, X0:X1] < 150)
    else:
        bright = sub.sum(2) > np.percentile(sub.sum(2), 70)
        bg = np.median(sub[bright], 0)
        m = np.abs(sub - bg).sum(2) > 75
    m = cv2.dilate(m.astype(np.uint8), np.ones((3, 3), np.uint8), 1)
    mask[Y0:Y1, X0:X1] |= m
(x0, y0, x1, y1), _ = TITLE
tm = (g[y0:y1, x0:x1] < 110).astype(np.uint8)
mask[y0:y1, x0:x1] |= cv2.dilate(tm, np.ones((3, 3), np.uint8), 2)
cv2.imwrite('mask.png', mask * 255)
clean = cv2.inpaint(img, mask, 4, cv2.INPAINT_TELEA)
cv2.imwrite('clean.png', clean)

base = Image.fromarray(cv2.cvtColor(clean, cv2.COLOR_BGR2RGB)).convert('RGBA')
DARK, RED, LEG = (52, 32, 20), (128, 50, 34), (78, 56, 32)
HALO = (242, 222, 192)
S = 3  # supersample

def put(text, cx, cy, size, color, halo=True, font=FONT, halo_r=2, span=0):
    f = ImageFont.truetype(font, size * S)
    l, t, r, b = f.getbbox(text)
    w, h = r - l, b - t
    pad = 12 * S
    a = Image.new('L', (w + 2 * pad, h + 2 * pad), 0)
    if span and len(text) > 1:
        cw = [f.getlength(c) for c in text]
        gap = max(0, min(size * S * (0.8 if len(text) == 2 else 0.5), (span * S - sum(cw)) / (len(text) - 1)))
        w = int(sum(cw) + gap * (len(text) - 1))
        a = Image.new('L', (w + 2 * pad, h + 2 * pad), 0); d = ImageDraw.Draw(a); x = pad
        for c, k in zip(text, cw):
            d.text((x, pad - t), c, font=f, fill=255); x += k + gap
    else:
        ImageDraw.Draw(a).text((pad - l, pad - t), text, font=f, fill=255)
    a = a.resize((a.width // S, a.height // S), Image.LANCZOS)
    ox, oy = int(round(cx - a.width / 2)), int(round(cy - a.height / 2))
    if halo:
        hm = a.filter(ImageFilter.MaxFilter(2 * halo_r + 1)).filter(ImageFilter.GaussianBlur(2.2))
        hm = hm.point(lambda v: min(255, int(v * 1.25)))
        layer = Image.new('RGBA', a.size, HALO + (0,)); layer.putalpha(hm)
        base.alpha_composite(layer, (ox, oy))
    layer = Image.new('RGBA', a.size, color + (0,)); layer.putalpha(a)
    base.alpha_composite(layer, (ox, oy))
    return w // S

for (x0, y0, x1, y1), zh, st in L:
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    if st == 'l':
        f = ImageFont.truetype(FONT, 19)
        w = f.getlength(zh)
        put(zh, x0 + w / 2, cy, 19, LEG, halo=False)
        continue
    size = 20
    put(zh, cx, cy, size, RED if st == 'r' else DARK, span=(x1 - x0) * 0.82)
(x0, y0, x1, y1), zh = TITLE
put(zh, (x0 + x1) / 2, (y0 + y1) / 2 + 1, 42, (24, 18, 14), halo=False, font=TFONT)
base.convert('RGB').save(OUT)
print('ok', OUT)
