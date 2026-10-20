#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline round-trip test for the upload name filter (inj/.../UploadNames.java).

The game uploads each save block as  ws_*  ->  base64(zlib(binary)), wrapped in

    {"user_id":"..","crypt_key":"..","savedata":"{\\"ws_profiles\\":\\"<base64>\\", ..}","lang":"en"}

Inside the binary a name is stored as `0x01 + length(UTF-8 bytes) + UTF-8 bytes`, e.g.
01 06 E5 87 AF E6 81 A9 = "凯恩".

This script builds that exact shape with Chinese names, runs the *same algorithm* as
UploadNames.filterNames / filterOne / filterJsonBlobs (ported 1:1 to Python), and checks:

  1. every Chinese name becomes the expected fixed pinyin,
  2. every other byte is preserved (ids, 25-byte appearance, links, noise runs),
  3. the length byte equals the new UTF-8 byte count,
  4. the repacked zlib inflates back to exactly the filtered bytes,
  5. a replacement that would exceed 255 bytes is refused (bytes kept as-is),
  6. junk input (bad base64, non-zlib, truncated, ASCII-only body) is returned unchanged,
  7. the whole request body still parses as JSON and both ws_* values still decode.

    python tools/verify_upload_names.py            # checks only
    python tools/verify_upload_names.py --fixture  # also print the Java fixture hex
"""

import base64
import json
import sys
import zlib

# Chinese name -> fixed pinyin (first letter upper case, no tones, no spaces).
# Same name must always give the same ASCII name, otherwise the server sees a rename.
MAP = {
    "凯恩": "KaiEn",
    "蕾娜塔": "LeiNaTa",
    "约尔格": "YueErGe",
    "铁蛋": "TieDan",
    "王小明": "WangXiaoMing",
}

B64_CHARS = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=_-")


def has_han(text):
    return any(0x4E00 <= ord(c) <= 0x9FFF or 0x3400 <= ord(c) <= 0x4DBF for c in text)


# ------------------------------------------------------------------ binary layer

def slot_ok(raw, i):
    """Return (length, name) if a valid `0x01 + len + UTF-8` name slot starts at i, else None."""
    if i + 2 > len(raw) or raw[i] != 0x01:
        return None
    ln = raw[i + 1]
    if ln < 1 or ln > 64 or i + 2 + ln > len(raw):
        return None
    chunk = raw[i + 2:i + 2 + ln]
    if any(b < 0x20 or b == 0x7F for b in chunk):
        return None
    try:
        return ln, chunk.decode("utf-8")
    except UnicodeDecodeError:
        return None


def filter_names(raw, mapping=None):
    """Port of UploadNames.filterNames. Returns (new_bytes, changes)."""
    mapping = MAP if mapping is None else mapping
    out = bytearray()
    i = 0
    changes = 0
    while i < len(raw):
        got = slot_ok(raw, i)
        if got is None:
            out.append(raw[i])
            i += 1
            continue
        ln, name = got
        if not has_han(name):
            out += raw[i:i + 2 + ln]
            i += 2 + ln
            continue
        asc = mapping.get(name)
        if not asc:
            out += raw[i:i + 2 + ln]
            i += 2 + ln
            continue
        nb = asc.encode("utf-8")
        if len(nb) > 255:                       # too long -> leave the slot exactly as it was
            out += raw[i:i + 2 + ln]
            i += 2 + ln
            continue
        out += bytes([0x01, len(nb)]) + nb
        changes += 1
        i += 2 + ln
    return bytes(out), changes


# ------------------------------------------------------------------ base64(zlib) layer

def looks_like_zlib_b64(value):
    """base64 candidate whose first decoded byte is 0x78 (zlib header), i.e. a save block."""
    if not value or len(value) < 24 or len(value) % 4:
        return False
    if not all(c in B64_CHARS for c in value):
        return False
    try:
        first = base64.b64decode(value[:4].replace("-", "+").replace("_", "/"))[0]
    except Exception:
        return False
    return first == 0x78


def filter_one(b64):
    """Port of UploadNames.filterOne: never make the value longer, never throw."""
    try:
        packed = base64.b64decode(b64, validate=False)
    except Exception:
        return b64
    if len(packed) < 2 or packed[0] != 0x78:
        return b64
    try:
        raw = zlib.decompress(packed)
    except Exception:
        return b64
    fixed, changes = filter_names(raw)
    if not changes:
        return b64
    repacked = zlib.compress(fixed, 9)
    out = base64.b64encode(repacked).decode()
    return out if len(out) <= len(b64) else b64


# ------------------------------------------------------------------ JSON layer

def _str_end(plain, i):
    """Index of the closing quote of the JSON string that starts at i (i points at the quote)."""
    n = len(plain)
    j = i + 1
    while j < n:
        c = plain[j]
        if c == "\\":
            # Only \" and \\ continue the string. A base64 `\/` is *not* a quote escape: the
            # inner JSON of `savedata` wraps values in \" so `\/` sits inside a nested literal.
            if j + 1 < n and plain[j + 1] in '"\\':
                j += 2
                continue
            j += 1
            continue
        if c == '"':
            return j
        j += 1
    return -1


def filter_json_blobs(plain):
    """Port of UploadNames.filterJsonBlobs. Returns (new_text, hits)."""
    out = []
    i = 0
    n = len(plain)
    hits = 0
    while i < n:
        if plain[i] != '"':
            out.append(plain[i])
            i += 1
            continue
        j = _str_end(plain, i)
        if j < 0:
            out.append(plain[i:])
            break
        lit = plain[i + 1:j]
        k = i - 1
        while k >= 0 and plain[k] in " \t\r\n":
            k -= 1
        if k >= 0 and plain[k] == ":":
            # savedata is a *stringified* JSON: its inner quotes are escaped (`\"ws_profiles\":`).
            # The base64 value therefore lives inside a nested literal. Scan this literal for an
            # inner `":"` followed by a base64(zlib) value and rewrite just that value in place.
            new_lit, sub = rewrite_nested(lit)
            if sub:
                out.append('"' + new_lit + '"')
                i = j + 1
                hits += sub
                continue
        out.append(plain[i:j + 1])
        i = j + 1
    return "".join(out), hits


def rewrite_nested(lit):
    """Rewrite every escaped-string value inside one JSON string literal. Returns (new, count)."""
    out = []
    i = 0
    n = len(lit)
    hits = 0
    while i < n:
        q = lit.find('\\"', i)
        if q < 0:
            out.append(lit[i:])
            break
        out.append(lit[i:q + 2])          # keep the opening \"
        start = q + 2
        e = start
        while True:
            e = lit.find('\\"', e)
            if e < 0:
                break
            # an escaped quote at `e` closes the nested literal unless it is itself part of `\\`
            bs = 0
            b = e - 1
            while b >= start and lit[b] == "\\":
                bs += 1
                b -= 1
            if bs % 2 == 0:
                break
            e += 2
        if e < 0:
            out.append(lit[start - 2:])
            break
        val = lit[start:e]
        rep = val
        if looks_like_zlib_b64(val):
            cand = filter_one(val)
            if cand != val:
                rep = cand.replace("/", "\\/")
                hits += 1
        out.append(rep)
        out.append('\\"')
        i = e + 2
    return "".join(out), hits


# ------------------------------------------------------------------ samples

def slot(name):
    nb = name.encode("utf-8")
    assert 1 <= len(nb) <= 255
    return bytes([0x01, len(nb)]) + nb


def sample_profiles():
    """ws_profiles-like blob: 4 named records + noise runs that must survive untouched."""
    b = bytearray()
    b += bytes.fromhex("0000000101000000")
    b += bytes.fromhex("ffffffff02000000")
    b += slot("凯恩") + bytes(25) + (1).to_bytes(4, "little")
    b += slot("蕾娜塔") + bytes(25) + (2).to_bytes(4, "little")
    b += bytes.fromhex("7f80010203")
    b += slot("约尔格") + bytes(25) + (3).to_bytes(4, "little")
    b += slot("铁蛋") + bytes(25) + (4).to_bytes(4, "little")
    b += bytes.fromhex("deadbeef")
    return bytes(b)


def sample_familys():
    b = bytearray()
    b += bytes.fromhex("0000000201000000")
    b += slot("王小明") + bytes(25) + (7).to_bytes(4, "little")
    b += b"\x00"
    b += slot("凯恩") + bytes(25) + (8).to_bytes(4, "little")
    return bytes(b)


def savedata_body(blobs):
    """Build the real request body: savedata is a *stringified* JSON of ws_* -> base64(zlib)."""
    inner = {k: base64.b64encode(zlib.compress(v, 9)).decode() for k, v in blobs.items()}
    body = {
        "user_id": "12345",
        "crypt_key": "abcdef0123456789",
        "savedata": json.dumps(inner, separators=(",", ":")),
        "lang": "en",
    }
    return json.dumps(body, separators=(",", ":"))


# ------------------------------------------------------------------ checks

def main(argv):
    ok = True
    report = []

    def check(name, cond, extra=""):
        nonlocal ok
        ok = ok and bool(cond)
        report.append("  [%s] %s%s" % ("OK " if cond else "FAIL", name, (" -- " + extra) if extra else ""))

    raw = sample_profiles()
    fixed, changes = filter_names(raw)
    check("4 name slots replaced", changes == 4, "changes=%d" % changes)
    for zh, asc in (("凯恩", "KaiEn"), ("蕾娜塔", "LeiNaTa"), ("约尔格", "YueErGe"), ("铁蛋", "TieDan")):
        check("%s -> %s" % (zh, asc), slot(asc) in fixed and slot(zh) not in fixed)
    check("no CJK left in filtered blob",
          not any(0x4E00 <= b <= 0x9FFF for b in fixed) if fixed else True)

    def skeleton(b):
        """The blob with all name slots blanked out - before/after must be identical."""
        s = bytearray()
        i = 0
        while i < len(b):
            got = slot_ok(b, i)
            if got:
                s += b"\x00SLOT\x00"
                i += 2 + got[0]
                continue
            s.append(b[i])
            i += 1
        return bytes(s)

    check("all non-name bytes preserved", skeleton(raw) == skeleton(fixed),
          "%d bytes" % len(skeleton(raw)))
    for k in (1, 2, 3, 4):
        check("record link %d preserved" % k, k.to_bytes(4, "little") in fixed)
    check("noise runs preserved", bytes.fromhex("ffffffff02000000") in fixed
          and bytes.fromhex("7f80010203") in fixed and bytes.fromhex("deadbeef") in fixed)

    i, bad_len = 0, []
    while i < len(fixed):
        got = slot_ok(fixed, i)
        if got:
            if got[0] != len(got[1].encode("utf-8")):
                bad_len.append(got[0])
            i += 2 + got[0]
            continue
        i += 1
    check("length byte == utf-8 byte count", not bad_len, str(bad_len))

    packed = zlib.compress(raw, 9)
    b64 = base64.b64encode(packed).decode()
    b64b = filter_one(b64)
    check("base64(zlib) value changed", b64b != b64)
    check("zlib round trip == filtered bytes",
          zlib.decompress(base64.b64decode(b64b)) == fixed, "%d bytes" % len(fixed))
    check("repacked value not longer", len(b64b) <= len(b64), "%d -> %d" % (len(b64), len(b64b)))

    body = savedata_body({"ws_profiles": raw, "ws_familys": sample_familys()})
    new_body, hits = filter_json_blobs(body)
    check("both ws_* values rewritten", hits == 2, "hits=%d" % hits)
    check("body not longer", len(new_body) <= len(body), "%d -> %d" % (len(body), len(new_body)))
    try:
        parsed = json.loads(new_body)
        savedata2 = json.loads(parsed["savedata"])
        check("ws_profiles decodes to filtered bytes",
              zlib.decompress(base64.b64decode(savedata2["ws_profiles"])) == fixed)
        fam_fixed, _ = filter_names(sample_familys())
        check("ws_familys decodes to filtered bytes",
              zlib.decompress(base64.b64decode(savedata2["ws_familys"])) == fam_fixed)
        check("plain fields untouched", parsed["user_id"] == "12345"
              and parsed["crypt_key"] == "abcdef0123456789" and parsed["lang"] == "en")
    except Exception as e:  # noqa: BLE001
        check("new body still parses", False, repr(e))

    # replacement that would be > 255 bytes must be refused
    long_name = bytes([0x01, 63]) + ("长" * 21).encode("utf-8")     # 63 bytes, valid UTF-8
    over_map = {"长" * 21: "A" * 300}                               # 300 > 255
    kept, ch = filter_names(long_name, over_map)
    check("replacement over 255 bytes refused", ch == 0 and kept == long_name)
    shorter_map = {"长" * 21: "A" * 10}
    kept2, ch2 = filter_names(long_name, shorter_map)
    check("short replacement applied", ch2 == 1 and kept2 == bytes([0x01, 10]) + b"A" * 10)

    check("bad base64 unchanged", filter_one("!!!!not-base64!!!!") == "!!!!not-base64!!!!")
    nonz = base64.b64encode(b"plain text, not zlib").decode()
    check("non-zlib unchanged", filter_one(nonz) == nonz)
    trunc = base64.b64encode(zlib.compress(raw)[:10]).decode()
    check("truncated zlib unchanged", filter_one(trunc) == trunc)
    ascii_body = '{"user_id":"1","lang":"en"}'
    nb, h = filter_json_blobs(ascii_body)
    check("ASCII-only body untouched", nb == ascii_body and h == 0)
    check("idempotent (second pass changes nothing)", filter_names(fixed)[1] == 0)

    print("upload name filter: offline round-trip test")
    print("  python %s" % sys.version.split()[0])
    print("\n".join(report))
    print("RESULT:", "PASS" if ok else "FAIL")

    if "--fixture" in argv:
        fx = (bytes.fromhex("0000000101000000") + slot("凯恩") + bytes.fromhex("ffffffff02000000")
              + slot("蕾娜塔") + b"\x00" + slot("约尔格") + bytes.fromhex("7f80010203")
              + slot("铁蛋") + bytes.fromhex("0101ff") + bytes.fromhex("deadbeef"))
        fxf, _ = filter_names(fx)
        print()
        print("RAW_HEX    = %s" % fx.hex())
        print("EXPECT_HEX = %s" % fxf.hex())
        print("lengths    : raw=%d filtered=%d" % (len(fx), len(fxf)))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
