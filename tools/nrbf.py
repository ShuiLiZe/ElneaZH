"""Minimal MS-NRBF (BinaryFormatter) reader. parse(data) -> (objects dict id->obj, root id)
obj: {'cls':name,'members':{name:value}} ; strings -> ('S', recid, text, start, end); refs -> ('R', id)"""
import struct

PRIM = {1: '?', 2: 'B', 3: 'c', 5: 'd', 6: 'd', 7: 'h', 8: 'i', 9: 'q', 10: 'b', 11: 'f', 12: 'q', 13: 'q', 14: 'H', 15: 'I', 16: 'Q'}


class R:
    def __init__(s, d): s.d = d; s.i = 0
    def u8(s): v = s.d[s.i]; s.i += 1; return v
    def i32(s): v = struct.unpack_from('<i', s.d, s.i)[0]; s.i += 4; return v
    def lps(s):
        n = sh = 0
        while True:
            c = s.u8(); n |= (c & 0x7f) << sh; sh += 7
            if c < 0x80: break
        v = s.d[s.i:s.i + n].decode('utf-8'); s.i += n; return v
    def prim(s, t):
        if t == 18: return s.lps()
        if t == 3:  # char utf8
            c = s.d[s.i]; n = 1 if c < 0x80 else 2 if c < 0xE0 else 3 if c < 0xF0 else 4
            v = s.d[s.i:s.i + n].decode(); s.i += n; return v
        f = PRIM[t]; v = struct.unpack_from('<' + f, s.d, s.i)[0]; s.i += struct.calcsize(f); return v


def parse(data):
    r = R(data); objs = {}; classes = {}; root = None

    def read_classinfo():
        oid = r.i32(); name = r.lps(); n = r.i32()
        names = [r.lps() for _ in range(n)]
        return oid, name, names

    def read_typeinfo(n):
        bts = [r.u8() for _ in range(n)]; extra = []
        for bt in bts:
            if bt == 0 or bt == 7: extra.append(r.u8())
            elif bt == 3: extra.append(r.lps())
            elif bt == 4: extra.append((r.lps(), r.i32()))
            else: extra.append(None)
        return bts, extra

    def read_values(meta):
        name, names, bts, extra = meta
        m = {}
        for nm, bt, ex in zip(names, bts, extra):
            if bt == 0: m[nm] = r.prim(ex)
            else: m[nm] = record()
        return m

    nulls = [0]

    def record():
        nonlocal root
        if nulls[0]:
            nulls[0] -= 1; return None
        st = r.i; t = r.u8()
        if t == 0: r.i += 16; return record()
        if t == 12: r.i32(); r.lps(); return record()
        if t in (5, 4):
            oid, name, names = read_classinfo()
            bts, extra = read_typeinfo(len(names))
            if t == 5: r.i32()
            meta = (name, names, bts, extra); classes[oid] = meta
            o = {'cls': name, 'id': oid}; objs[oid] = o
            o['members'] = read_values(meta); return ('R', oid)
        if t == 1:
            oid = r.i32(); mid = r.i32(); meta = classes[mid]; classes[oid] = meta
            o = {'cls': meta[0], 'id': oid}; objs[oid] = o
            o['members'] = read_values(meta); return ('R', oid)
        if t == 6:
            oid = r.i32(); s = r.lps(); v = ('S', oid, s, st, r.i); objs[oid] = v; return v
        if t == 9: return ('R', r.i32())
        if t == 10: return None
        if t == 13: nulls[0] = r.u8() - 1; return None
        if t == 14: nulls[0] = r.i32() - 1; return None
        if t == 8: return r.prim(r.u8())
        if t == 15:
            oid = r.i32(); n = r.i32(); pt = r.u8(); v = [r.prim(pt) for _ in range(n)]; objs[oid] = v; return ('R', oid)
        if t in (16, 17):
            oid = r.i32(); n = r.i32(); v = []
            while len(v) < n:
                x = record(); v.append(x)
                if nulls[0]: v.extend([None] * nulls[0]); nulls[0] = 0
            objs[oid] = v; return ('R', oid)
        if t == 7:
            oid = r.i32(); at = r.u8(); rank = r.i32(); lens = [r.i32() for _ in range(rank)]
            if at in (3, 4, 5): [r.i32() for _ in range(rank)]
            bts, extra = read_typeinfo(1); n = 1
            for L in lens: n *= L
            v = []
            while len(v) < n:
                if bts[0] == 0: v.append(r.prim(extra[0]))
                else:
                    x = record(); v.append(x)
                    if nulls[0]: v.extend([None] * nulls[0]); nulls[0] = 0
            objs[oid] = v; return ('R', oid)
        if t == 11: return 'END'
        raise ValueError(f'record type {t} at {st}')

    hdr = data[0]; assert hdr == 0
    r.i = 1; root = struct.unpack_from('<i', data, r.i)[0]; r.i = 17
    while r.i < len(data):
        x = record()
        if x == 'END': break
    return objs, root, r.i
