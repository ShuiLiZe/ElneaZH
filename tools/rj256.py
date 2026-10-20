#!/usr/bin/env python3
"""Decrypt Elnea acc= bodies: Rijndael-256 block (game Cipher::EncryptRJ256).
usage: rj256.py --key K --iv IV [--mode cbc|ecb] [--pad pkcs7|zero] (--acc X | --samples acc_samples.json)
K/IV: utf8 text, hex: or b64: prefix."""
import argparse, base64, json, re, sys, urllib.parse
from py3rijndael import RijndaelCbc, Rijndael, Pkcs7Padding, ZeroPadding
CJK = re.compile(r'[\u3040-\u30ff\u4e00-\u9fff]')
def kb(s):
    if s.startswith('hex:'): return bytes.fromhex(s[4:])
    if s.startswith('b64:'): return base64.b64decode(s[4:])
    return s.encode('utf-8')
def dec(ct, key, iv, mode):
    if mode == 'ecb':
        r = Rijndael(key, 32); out = b''.join(r.decrypt(ct[i:i+32]) for i in range(0, len(ct), 32))
    else:
        r = RijndaelCbc(key, iv, ZeroPadding(32), 32); out = r.decrypt(ct)
    p = out[-1]
    if 0 < p <= 32 and out.endswith(bytes([p]) * p): out = out[:-p]
    return out.rstrip(b'\0')
a = argparse.ArgumentParser(); a.add_argument('--key', required=True); a.add_argument('--iv', default='')
a.add_argument('--mode', default='cbc'); a.add_argument('--acc'); a.add_argument('--samples')
o = a.parse_args(); key = kb(o.key); iv = kb(o.iv) if o.iv else b'\0' * 32
items = {}
if o.acc: items['acc'] = base64.b64decode(urllib.parse.unquote(o.acc))
if o.samples: items = {k: bytes.fromhex(v) for k, v in json.load(open(o.samples)).items()}
for k, ct in items.items():
    t = dec(ct, key, iv, o.mode).decode('utf-8', 'replace')
    print(f'== {k} ({len(ct)}B) cjk={bool(CJK.search(t))}\n{t}\n')
