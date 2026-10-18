#!/usr/bin/env python3
# Check re-encoded textures: python tools/verify_textures.py  (expects data.unity3d = original, out.unity3d = built, images_zh/)
# Prints format/size/mips before vs after and mean error on visible pixels; ETC2 visible-RGB error should be < ~10.
import UnityPy, numpy as np, os
from PIL import Image
names={f[:-4] for f in os.listdir('images_zh') if f.endswith('.png')}
def info(path):
    env=UnityPy.load(path); out={}
    for o in env.objects:
        if o.type.name=='Texture2D':
            d=o.read()
            if d.m_Name in names:
                sd=d.m_StreamData
                out[d.m_Name]=(d.m_TextureFormat.name,d.m_Width,d.m_Height,d.m_MipCount,getattr(sd,'path',''),getattr(sd,'size',0),len(d.image_data or b''),d.image)
    return out
a=info('data.unity3d'); b=info('out.unity3d')
for n in sorted(names):
    oa,ob=a[n],b[n]
    png=np.asarray(Image.open(f'images_zh/{n}.png').convert('RGBA')).astype(int)
    dec=np.asarray(ob[7].convert('RGBA')).astype(int)
    # image property returns flipped? compare both orientations
    e1=np.abs(dec-png); e2=np.abs(dec[::-1]-png)
    e=e1 if e1.mean()<e2.mean() else e2
    vis=png[...,3]>8
    print(n,'orig',oa[:7],'new',ob[:7])
    print('   meanerr all %.2f  visible-RGB %.2f  alpha %.2f  max-vis %d'%(e.mean(),e[vis][:,:3].mean(),e[...,3].mean(),e[vis][:,:3].max()))
