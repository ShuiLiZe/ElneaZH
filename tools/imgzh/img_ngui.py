import UnityPy,struct,json,sys
sys.path.insert(0,'work')
env=UnityPy.load('repo/base/data.unity3d')
ra=env.files['repo/base/data.unity3d'].files['resources.assets']
def rd_str(b,p):
    n=struct.unpack_from('<i',b,p)[0]; s=b[p+4:p+4+n].decode('utf-8'); p+=4+n; p=(p+3)&~3; return s,p
def parse(b,start):
    p=start
    for hl in range(0,64,4):
        n=struct.unpack_from('<i',b,p+hl)[0]; m=struct.unpack_from('<i',b,p+hl+4)[0]
        if 0<n<5000 and 0<m<64 and all(32<c<127 for c in b[p+hl+8:p+hl+8+m]): break
    print(' header',b[p:p+hl].hex())
    p+=hl+4; sp=[]
    for i in range(n):
        nm,p=rd_str(b,p); v=struct.unpack_from('<12i',b,p); p+=48
        sp.append(dict(name=nm,x=v[0],y=v[1],w=v[2],h=v[3],border=v[4:8],padding=v[8:12]))
    print(' rest',b[p:].hex()); return sp
out={}
for o in ra.objects.values():
    if o.type.name=='MonoBehaviour':
        d=o.read()
        if d.m_Name in ('Main_Atlas_en','Main_Atlas_jp','Sub1_Atlas_en','Sub1_Atlas_jp'):
            b=bytes(o.get_raw_data())
            # name is at 28
            n=struct.unpack_from('<i',b,28)[0]; p=(28+4+n+3)&~3
            print(o.path_id,d.m_Name,len(b))
            out[d.m_Name]=parse(b,p)
json.dump(out,open('work/atlas_sprites.json','w'),indent=1)
for k in ('Main_Atlas_en','Sub1_Atlas_en'):
    for s in out[k]: print(k,s['name'],s['x'],s['y'],s['w'],s['h'],s['padding'])
