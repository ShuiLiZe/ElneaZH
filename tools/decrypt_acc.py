#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Decrypt an Elnea Kingdom upload body (acc=<base64(ciphertext)>&lang=en).

Pure local tool: no network access, no third-party service.

Dependencies (install ONE of these):
    pip install pycryptodome          # preferred: `from Crypto.Cipher import AES`
    pip install cryptography          # fallback: AES-CBC / AES-ECB via `cryptography`

Usage
-----
    python tools/decrypt_acc.py --key <hex|b64> --iv <hex|b64> --body 'acc=...&lang=en'
    python tools/decrypt_acc.py --key <hex|b64> --iv <hex|b64> --acc 'U2FsdGVkX1...'
    python tools/decrypt_acc.py --key <hex|b64> --iv <hex|b64> --file captured.txt
    python tools/decrypt_acc.py --key ... --iv ... --mode ecb --acc ...
    python tools/decrypt_acc.py --selftest

The `acc` pipeline assumed here (as observed in the game client):
    plaintext  --AES/Rijndael-CBC, PKCS7-->  ciphertext  --base64-->  --URL-encode-->  acc
so decryption is: URL-decode -> base64-decode -> AES decrypt -> strip PKCS7 -> utf-8.

Block size is 128 bit. Rijndael with a 128-bit block *is* AES, so any AES
implementation decrypts it; key length 16/24/32 is auto-detected.
"""

import argparse
import base64
import binascii
import re
import sys
import urllib.parse


# ---------------------------------------------------------------- crypto glue

def _load_backend():
    """Return ('pycryptodome', module) or ('cryptography', module) or (None, error)."""
    try:
        from Crypto.Cipher import AES as _AES  # noqa: N813  (pycryptodome)
        return 'pycryptodome', _AES
    except Exception as e_pycrypto:  # noqa: BLE001
        try:
            from cryptography.hazmat.primitives.ciphers import (  # noqa: F401
                Cipher, algorithms, modes,
            )
            return 'cryptography', None
        except Exception as e_crypto:  # noqa: BLE001
            return None, (e_pycrypto, e_crypto)


BACKEND, BACKEND_OBJ = _load_backend()


def _pad(data, block=16):
    n = block - len(data) % block
    return data + bytes([n]) * n


def _unpad(data, block=16):
    """Strip PKCS7 padding; return (data, True) or (original, False) if it does not look valid."""
    if not data or len(data) % block:
        return data, False
    n = data[-1]
    if n < 1 or n > block or data[-n:] != bytes([n]) * n:
        return data, False
    return data[:-n], True


def _aes_decrypt(key, iv, ct, mode):
    """Return raw AES-decrypted bytes (padding still attached). Raises on bad input."""
    if BACKEND == 'pycryptodome':
        try:
            if mode == 'ecb':
                cipher = BACKEND_OBJ.new(key, BACKEND_OBJ.MODE_ECB)
            else:
                cipher = BACKEND_OBJ.new(key, BACKEND_OBJ.MODE_CBC, iv=iv)
            return cipher.decrypt(ct)
        except Exception:  # noqa: BLE001
            # older pycryptodome wants the mode object, and CBC without iv= is legal there
            if mode == 'cbc' and iv:
                cipher = BACKEND_OBJ.new(key, BACKEND_OBJ.MODE_CBC, iv)
                return cipher.decrypt(ct)
            raise
    # cryptography fallback
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    m = modes.ECB() if mode == 'ecb' else modes.CBC(iv)
    dec = Cipher(algorithms.AES(key), m).decryptor()
    return dec.update(ct) + dec.finalize()


def _aes_encrypt(key, iv, pt, mode):
    """Only used by --selftest."""
    if BACKEND == 'pycryptodome':
        try:
            if mode == 'ecb':
                cipher = BACKEND_OBJ.new(key, BACKEND_OBJ.MODE_ECB)
            else:
                cipher = BACKEND_OBJ.new(key, BACKEND_OBJ.MODE_CBC, iv=iv)
            return cipher.encrypt(pt)
        except Exception:  # noqa: BLE001
            if mode == 'cbc' and iv:
                cipher = BACKEND_OBJ.new(key, BACKEND_OBJ.MODE_CBC, iv)
                return cipher.encrypt(pt)
            raise
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    m = modes.ECB() if mode == 'ecb' else modes.CBC(iv)
    enc = Cipher(algorithms.AES(key), m).encryptor()
    return enc.update(pt) + enc.finalize()


# ---------------------------------------------------------------- key / iv parsing

_HEX_RE = re.compile(rb'^(?:0x)?[0-9a-fA-F]+$')
_B64_CHARS = set(b'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=')


def parse_key_or_iv(text, what, blockmust=False):
    """Accept hex (with or without 0x / spaces / colons) or base64. Raises SystemExit on failure."""
    if text is None:
        return None
    raw = text.strip()
    candidates = []

    # 1) hex
    cleaned = raw.replace(' ', '').replace(':', '').replace('-', '').replace('_', '')
    body = cleaned[2:] if cleaned.lower().startswith('0x') else cleaned
    if body and len(body) % 2 == 0 and _HEX_RE.match(body.encode()):
        candidates.append(('hex', bytes.fromhex(body)))

    # 2) base64 (standard and url-safe)
    if raw:
        for norm in (raw, raw.replace('-', '+').replace('_', '/')):
            padded = norm + '=' * (-len(norm) % 4)
            try:
                if set(padded.encode()) <= _B64_CHARS:
                    candidates.append(('base64', base64.b64decode(padded, validate=False)))
            except (binascii.Error, ValueError):
                pass

    # prefer a candidate whose length is a legal AES key length, else the longest one
    legal = [c for c in candidates if len(c[1]) in (16, 24, 32)]
    if legal:
        return legal[0][1]
    if candidates:
        hint = ', '.join('%s=%dB' % (k, len(v)) for k, v in candidates)
        raise SystemExit('%s must be 16/24/32 bytes when given in hex or base64 (got %s)' % (what, hint))
    raise SystemExit('%s is neither hex nor base64: %r' % (what, text))


# ---------------------------------------------------------------- body / acc extraction

_ACC_RE = re.compile(r'(?:^|[&\s])acc=([^&\s]+)')


def extract_acc(args):
    """Return the raw (still URL-encoded) `acc` value from --acc / --body / --file."""
    if args.acc:
        val = args.acc.strip()
        # tolerate someone pasting "acc=..." into --acc
        if val.startswith('acc='):
            val = val[4:]
        return val
    text = None
    if args.body:
        text = args.body
    elif args.file:
        with open(args.file, 'r', encoding='utf-8', errors='replace') as f:
            text = f.read()
    if text is None:
        raise SystemExit('need one of --acc / --body / --file')
    text = text.strip()
    m = _ACC_RE.search(text)
    if m:
        return m.group(1)
    # a bare body dump may just be the encoded value with no key name
    if re.match(r'^[A-Za-z0-9+/=_\-\%\.~]+$', text) and '=' not in text.split('&')[0]:
        return text
    raise SystemExit('no "acc=" field found in the given input')


def decode_acc_value(acc_value):
    """URL-decode then base64-decode the acc value. Returns (ciphertext, notes[])."""
    notes = []
    s = acc_value.strip().strip('"\'')
    if s.lower().startswith('acc='):
        s = s[4:]
    url_decoded = urllib.parse.unquote(s)
    if url_decoded != s:
        notes.append('url-decoded (%d -> %d chars)' % (len(s), len(url_decoded)))

    b64 = url_decoded.strip()
    # some stacks encode with url-safe base64 + omit padding
    if '-' in b64 or '_' in b64:
        b64 = b64.replace('-', '+').replace('_', '/')
        notes.append('url-safe base64 normalised')
    b64 = b64.replace(' ', '+')
    if len(b64) % 4:
        notes.append('base64 padding added')
        b64 += '=' * (-len(b64) % 4)
    try:
        ct = base64.b64decode(b64, validate=False)
    except (binascii.Error, ValueError) as e:
        raise SystemExit('base64 decode failed: %s' % e)
    if len(ct) % 16:
        notes.append('WARNING ciphertext is %d bytes, not a multiple of 16 '
                     '(not plain AES-CBC?)' % len(ct))
    return ct, notes


# ---------------------------------------------------------------- plaintext analysis

_CJK_RE = re.compile('[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef]')
_NAME_KEY_RE = re.compile(
    r'(name|nick|nickname|playername|player_name|username|user_name|rename|'
    r'firstname|lastname|familyname|profilename|kname|uname|accname|cn_)',
    re.IGNORECASE,
)


def _ctx_around(text, start, end, pad=60):
    a = max(0, start - pad)
    b = min(len(text), end + pad)
    return text[a:b]


def analyse(plain):
    """Print CJK / name-field hints. Returns number of findings."""
    findings = 0
    cjk = list(_CJK_RE.finditer(plain))
    print('--- plaintext analysis ---')
    if cjk:
        findings += 1
        chars = ''.join(m.group(0) for m in cjk)
        print('CJK characters: %d (unique %d)' % (len(cjk), len(set(chars))))
        print('  CJK sample   : %s' % chars[:80].replace('\n', '\\n'))
        for m in cjk[:6]:
            print('  ...%s...' % _ctx_around(plain, m.start(), m.end()).replace('\n', '\\n'))
    else:
        print('CJK characters: 0  (upload body looks ASCII-only)')

    print()
    # structured probes first: key=value pairs and "key":"value" / "key":"value" json-ish
    hits = 0
    for m in re.finditer(r'([A-Za-z0-9_\-\.]{1,32})\s*[=:]\s*"?([^&",\s]{0,80})', plain):
        k, v = m.group(1), m.group(2)
        if _NAME_KEY_RE.search(k):
            hits += 1
            star = ' <== NAME FIELD' if _CJK_RE.search(v) else ''
            print('  field %-16s = %r%s' % (k, v, star))
            if hits >= 40:
                print('  ...(field listing truncated)')
                break
    if not hits:
        # no structured hit: fall back to raw substring probes for common spellings
        for kw in ('name=', '"name"', "'name'", 'nick', 'Nick', 'Name'):
            for m in re.finditer(re.escape(kw), plain):
                hits += 1
                print('  raw %r at %d: ...%s...' % (
                    kw, m.start(), _ctx_around(plain, m.start(), m.end() + 40, 40).replace('\n', '\\n')))
                if hits >= 10:
                    break
            if hits >= 10:
                break
    if hits:
        findings += 1
        print('name-like fields found: %d' % hits)
    else:
        print('name-like fields found: 0')

    if '\x01' in plain or any(ord(c) < 9 for c in plain):
        print('note: plaintext contains control bytes -> probably binary (maybe still encrypted '
              'with the wrong key, or a save blob rather than a form body).')
    return findings


# ---------------------------------------------------------------- selftest

def selftest():
    print('backend: %s' % (BACKEND or 'NONE'))
    if BACKEND is None:
        print('no crypto backend available; install pycryptodome or cryptography')
        return 2
    ok = True
    BLOCK = '00112233445566778899aabbccddeeff'
    samples = [
        # (mode, key hex, iv hex, plaintext)
        ('cbc', BLOCK,
         '0102030405060708090a0b0c0d0e0f10',
         'acc=Zm9vYmFy&lang=en'),                                # AES-128 key
        ('cbc', BLOCK * 2,
         'ffeeddccbbaa99887766554433221100',
         'name=\u94c1\u86cb&uuid=abcd-1234&lang=en'),           # AES-256 key, 铁蛋
        ('cbc', BLOCK + BLOCK[:16],                            # 24 bytes -> AES-192
         '0f0e0d0c0b0a09080706050403020100',
         'ffid=1&name=\u738b\u5c0f\u660e&lang=en'),             # 王小明, raw UTF-8
        ('ecb', BLOCK,
         None,
         'pvp.php mode=ecb-16B-key'),
        ('cbc', BLOCK,
         '00112233445566778899aabbccddeeff',
         'exactly16bytes!!'),                                  # input already block-aligned
    ]
    for mode, key_hex, iv_hex, plain in samples:
        assert len(bytes.fromhex(key_hex)) in (16, 24, 32), key_hex
    for mode, key_hex, iv_hex, plain in samples:
        key = bytes.fromhex(key_hex)
        iv = bytes.fromhex(iv_hex) if iv_hex else b''
        pt = plain.encode('utf-8')
        ct = _aes_encrypt(key, iv, _pad(pt), mode)
        # now go through the *same* path the CLI uses: base64 -> urlencode -> parse -> decrypt
        acc = urllib.parse.quote(base64.b64encode(ct).decode())
        body = 'acc=%s&lang=en' % acc
        try:
            acc_val = extract_acc(argparse.Namespace(acc=None, body=body, file=None))
            ct2, _notes = decode_acc_value(acc_val)
            raw = _aes_decrypt(key, iv, ct2, mode)
            out, unpadded = _unpad(raw)
            text = out.decode('utf-8', errors='replace')
            good = (text == plain and unpadded and ct2 == ct)
        except Exception as e:  # noqa: BLE001
            good = False
            text = 'EXC %s' % e
        print('  [%s] %s key=%dB iv=%dB roundtrip=%s  -> %r' % (
            'OK ' if good else 'FAIL', mode, len(key), len(iv), good, text))
        ok = ok and good

    # negative control: wrong key must not round-trip
    key = bytes(range(16))
    ct = _aes_encrypt(key, bytes(range(16)), _pad(b'secret'), 'cbc')
    wrong = _aes_decrypt(bytes(range(1, 17)), bytes(range(16)), ct, 'cbc')
    bad = _unpad(wrong)[0].decode('utf-8', 'replace') == 'secret'
    print('  [OK ] wrong-key does not decrypt (sanity)')
    ok = ok and not bad

    # hex/base64 key parsing both work
    k1 = parse_key_or_iv('00112233445566778899aabbccddeeff', 'key')
    k2 = parse_key_or_iv(base64.b64encode(k1).decode(), 'key')
    print('  [%s] key parsing hex==b64 (%s)' % ('OK ' if k1 == k2 else 'FAIL', k1.hex()))
    ok = ok and k1 == k2
    k3 = parse_key_or_iv('0x00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff', 'key')
    print('  [%s] key parsing spaced-hex' % ('OK ' if k3 == k1 else 'FAIL'))
    ok = ok and k3 == k1

    print('SELFTEST', 'PASS' if ok else 'FAIL')
    return 0 if ok else 1


# ---------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(
        description='Decrypt an Elnea Kingdom upload body (acc=base64(cipher)&lang=en).',
        epilog='examples:\n'
               "  python tools/decrypt_acc.py --key 00112233... --iv 0102... --body 'acc=U2Fs...&lang=en'\n"
               "  python tools/decrypt_acc.py --key <b64> --iv <b64> --file capture.txt\n"
               '  python tools/decrypt_acc.py --selftest',
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument('--key', help='AES key: hex (with/without 0x, spaces or colons ok) or base64')
    ap.add_argument('--iv', help='AES IV (16 bytes): hex or base64. Not needed for --mode ecb')
    ap.add_argument('--body', help="full request body, e.g. 'acc=...&lang=en'")
    ap.add_argument('--acc', help='only the acc value (still URL-encoded / base64)')
    ap.add_argument('--file', help='file containing the request body')
    ap.add_argument('--mode', choices=('cbc', 'ecb'), default='cbc')
    ap.add_argument('--selftest', action='store_true', help='run built-in round-trip tests')
    ap.add_argument('--raw', action='store_true', help='dump plaintext as hex too')
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    if BACKEND is None:
        print('ERROR: no crypto backend. Run one of:\n'
              '  pip install pycryptodome\n  pip install cryptography', file=sys.stderr)
        return 2
    if not args.key:
        ap.error('--key is required (or use --selftest)')

    key = parse_key_or_iv(args.key, 'key')
    iv = parse_key_or_iv(args.iv, 'iv') if args.iv else b''
    if args.mode == 'cbc' and len(iv) != 16:
        ap.error('--iv must be 16 bytes for CBC (got %d)' % len(iv))

    acc_val = extract_acc(args)
    ct, notes = decode_acc_value(acc_val)
    print('acc value      : %d chars' % len(acc_val))
    print('ciphertext     : %d bytes (%d blocks)' % (len(ct), len(ct) // 16))
    print('key            : %dB  iv: %s  mode: %s' % (
        len(key), ('%dB %s' % (len(iv), iv.hex())) if iv else '-', args.mode.upper()))
    for n in notes:
        print('note           : %s' % n)

    try:
        raw = _aes_decrypt(key, iv, ct, args.mode)
    except Exception as e:  # noqa: BLE001
        print('ERROR: AES decrypt failed: %s' % e, file=sys.stderr)
        return 1
    plain, unpadded = _unpad(raw)
    if not unpadded:
        print('note           : PKCS7 padding not valid, printing raw bytes as-is')
    try:
        text = plain.decode('utf-8')
        enc = 'utf-8'
    except UnicodeDecodeError:
        text = plain.decode('latin-1')
        enc = 'latin-1'
    print('padding        : %s' % ('PKCS7 stripped' if unpadded else 'not stripped'))
    print('decoded as     : %s' % enc)
    print()
    print('--- plaintext ---')
    print(text)
    print('--- end ---')
    if args.raw:
        print()
        print('hex:', plain.hex())
    print()
    n = analyse(text)
    return 0 if n >= 0 else 1


if __name__ == '__main__':
    sys.exit(main())
