"""Decrypt/encrypt Elnea master tables (files/Contents/c_*).
Format: Rijndael-256 (32-byte block) ECB, key from global-metadata literal, payload = .NET BinaryFormatter List<MasterX>.
Strings are BinaryObjectString records: 0x06 <int32 id> <7bit-len> <utf8>. Replacing them in place (with new length prefix) keeps the stream valid."""
import re, struct, sys, json
from py3rijndael import Rijndael
KEY = b'7+22#bbtrm898j8z5q14lkirwf89q=45'
_R = Rijndael(KEY, block_size=32)

def decrypt(b):
    d = b''.join(_R.decrypt(b[i:i+32]) for i in range(0, len(b), 32))
    return d

def encrypt(d):
    pad = (-len(d)) % 32
    d = d + b'\0' * pad
    return b''.join(_R.encrypt(d[i:i+32]) for i in range(0, len(d), 32))

def _rd7(d, i):
    n = s = 0
    while True:
        c = d[i]; i += 1
        n |= (c & 0x7f) << s; s += 7
        if c < 0x80: return n, i

def _wr7(n):
    o = bytearray()
    while True:
        c = n & 0x7f; n >>= 7
        if n: o.append(c | 0x80)
        else: o.append(c); return bytes(o)

def strings(d):
    """yield (start, end, id, text) for every BinaryObjectString record (heuristic scan, validated by utf8 + plausible id)."""
    i = 0; out = []
    while True:
        i = d.find(b'\x06', i)
        if i < 0 or i + 6 > len(d): break
        rid = struct.unpack_from('<i', d, i+1)[0]
        if 0 < rid < 10_000_000:
            try:
                n, j = _rd7(d, i+5)
                if j + n <= len(d) and n < 100000:
                    t = d[j:j+n].decode('utf-8')
                    out.append((i, j+n, rid, t)); i = j + n; continue
            except Exception: pass
        i += 1
    return out

def replace(d, mapping):
    """mapping: record id -> new text"""
    out = bytearray(); last = 0
    for s, e, rid, t in strings(d):
        if rid in mapping and mapping[rid] != t:
            out += d[last:s+5] + _wr7(len(mapping[rid].encode())) + mapping[rid].encode(); last = e
    out += d[last:]
    return bytes(out)

if __name__ == '__main__':
    d = decrypt(open(sys.argv[1], 'rb').read())
    for s, e, rid, t in strings(d)[:int(sys.argv[2]) if len(sys.argv) > 2 else 50]:
        print(rid, repr(t))
