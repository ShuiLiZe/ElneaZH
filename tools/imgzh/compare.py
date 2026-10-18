import json,sys
from PIL import Image, ImageDraw
A=json.load(open('atlas_sprites.json'))
def sheet(atlas, before, after, out, names=None, scale=1, bgc=(60,120,70,255)):
    S=A[atlas]; b=Image.open(before).convert('RGBA'); a=Image.open(after).convert('RGBA')
    rows=[s for s in S if not names or s['name'] in names]
    W=max(s['w'] for s in rows)*2*scale+30; H=sum(s['h']*scale+16 for s in rows)
    sh=Image.new('RGBA',(W,H),(255,255,255,255)); d=ImageDraw.Draw(sh); y=0
    for s in rows:
        box=(s['x'],s['y'],s['x']+s['w'],s['y']+s['h'])
        for i,im in enumerate((b,a)):
            c=im.crop(box); bg=Image.new('RGBA',c.size,bgc); bg.alpha_composite(c)
            if scale!=1: bg=bg.resize((c.width*scale,c.height*scale),Image.NEAREST)
            sh.paste(bg,(i*(s['w']*scale+20),y+14))
        d.text((2,y+1),s['name'],fill=(0,0,0,255)); y+=s['h']*scale+16
    sh.save(out)
if __name__=='__main__':
    atlas,before,after,out=sys.argv[1:5]; names=sys.argv[5].split(',') if len(sys.argv)>5 and sys.argv[5] else None
    sc=int(sys.argv[6]) if len(sys.argv)>6 else 1
    sheet(atlas,before,after,out,names,sc)
