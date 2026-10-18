#!/usr/bin/env python3
"""CI check of the built data.unity3d + metadata.  usage: python tools/ci_verify.py base/data.unity3d build/data.unity3d images_zh build/preview
- every images_zh/*.png texture: same format/size/mip count as original, mean RGB error on visible pixels < 12
- writes side-by-side previews (drawn PNG | decoded from build) and the bitmap-font atlas to build/preview
"""
import sys, os
import numpy as np, UnityPy
from PIL import Image

orig, built, imgdir, out = sys.argv[1:5]
os.makedirs(out, exist_ok=True)
names = {f[:-4] for f in os.listdir(imgdir) if f.endswith('.png')}


def texs(p):
    r = {}
    for o in UnityPy.load(p).objects:
        if o.type.name == 'Texture2D':
            d = o.read()
            if d.m_Name in names or d.m_Name == 'Main_Atlas_jp':
                r[d.m_Name] = d
    return r


A, B = texs(orig), texs(built)
bad = []
for n in sorted(names):
    a, b = A[n], B[n]
    if (a.m_TextureFormat, a.m_Width, a.m_Height, a.m_MipCount) != (b.m_TextureFormat, b.m_Width, b.m_Height, b.m_MipCount):
        bad.append(f'{n}: header changed {a.m_TextureFormat.name},{a.m_MipCount} -> {b.m_TextureFormat.name},{b.m_MipCount}')
    png = Image.open(f'{imgdir}/{n}.png').convert('RGBA')
    dec = b.image.convert('RGBA')
    p, d = np.asarray(png).astype(int), np.asarray(dec).astype(int)
    vis = p[..., 3] > 8
    err = float(np.abs(p - d)[vis][:, :3].mean()) if vis.any() else 0.0
    print(f'{n}: {b.m_TextureFormat.name} mips {b.m_MipCount} visible-RGB err {err:.2f}')
    if err > 12:
        bad.append(f'{n}: visible RGB error {err:.1f} (>12) - colour/encoder problem?')
    bg = Image.new('RGBA', png.size, (128, 128, 128, 255))
    c = Image.new('RGBA', (png.width * 2 + 4, png.height), (255, 0, 255, 255))
    c.paste(Image.alpha_composite(bg, png), (0, 0)); c.paste(Image.alpha_composite(bg, dec), (png.width + 4, 0))
    c.save(f'{out}/{n}.png')
if 'Main_Atlas_jp' in B:
    B['Main_Atlas_jp'].image.save(f'{out}/Main_Atlas_jp_built.png')
if bad:
    print('\n'.join(bad)); sys.exit(1)
print('textures OK')
