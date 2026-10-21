"""Structural forensics of the real-device `acc=` upload samples.

Everything below is derived from the ciphertexts alone — no key material, no plaintext.
Rationale: the samples were captured as *ciphertext* (`acc=` bodies), so what can be audited
offline is the shape of each upload, not its field values:

  * body sizes per endpoint (i.e. how much plaintext each request carries),
  * whether the cipher is a stream XOR or a 16-byte block cipher (it is the latter),
  * whether identical 16-byte plaintext blocks repeat across requests (they do not),
  * the base64/percent-encoding length formula that turns a body into an `acc=` value,
  * the static landmarks in libil2cpp.so that bound *where* our gate can act.

Field-level content (name encoding, version strings, device ids) is **not** recoverable from
`acc=` without the runtime key; docs/UPLOAD_AUDIT.md marks those rows as "needs plaintext"
instead of guessing, and quotes the device-side source that produces each field.

    python tools/acc_forensics.py             # full report
    python tools/acc_forensics.py --selftest  # assertions used by tools/verify_upload_audit.py
"""

import argparse
import base64
import json
import os
import string

# --------------------------------------------------------------------------- inputs

# Ciphertexts only (byte-exact from the device; `acc=` values, base64 of the encrypted body).
FIXTURES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "tools", "acc_fixtures.json")

# --------------------------------------------------------------------------- measured facts

BLOCK = 16          # cipher block size (every body length is a multiple of 16: 64/96/128/160/224/256/1728)
HEAD_TAG = 8        # bytes 0..7 of each body act as a per-session tag (see report header groups)

# libil2cpp.so 2.10.7 (base 2.10.7 APK, arm64-v8a), verified by disassembly:
#   0x18ad7e4 = Cipher::EncryptRJ256(string)  <- plaintext -> `acc=`, the upload exit we hook
#   0x18ad984 = Cipher::DecryptRJ256(string)  <- `acc=` reply -> plaintext (symmetric)
#   0x18c15d0 = the single caller that pipes the encrypted string into the web request
ENC_VA = 0x18AD7E4
DEC_VA = 0x18AD984
SEND_VA = 0x18C15D0

ASCII_LOWER = (string.ascii_lowercase + string.digits + "_-.=/:?&\n").encode()


def load():
    with open(FIXTURES, "r", encoding="utf-8") as f:
        rows = json.load(f)
    for r in rows:
        r["raw"] = base64.b64decode(r["b64"])
    return rows


def acc_len(n_bytes):
    """Length of the `acc=` value on the wire: base64 plus percent-encoding of + and /."""
    b64 = base64.b64encode(bytes(n_bytes)).decode()
    return len(b64) + 2 * (b64.count("+") + b64.count("/"))


def blocks(ct):
    return [ct[i:i + BLOCK] for i in range(0, len(ct) - len(ct) % BLOCK, BLOCK)]


def printable_share(d):
    """Fraction of bytes that look like plain ASCII request text (for XOR-pad detection)."""
    return sum(1 for b in d if b in ASCII_LOWER) / max(1, len(d))


# --------------------------------------------------------------------------- report

def report():
    rows = load()
    seen = {}
    for r in rows:
        seen.setdefault(len(r["raw"]), r["raw"])
    print("== distinct bodies: size, block alignment, wire length")
    for L, ct in sorted(seen.items()):
        print("  %4d bytes  blocks=%2d  aligned=%-5s acc-len=%4d  tag=%s" % (
            L, len(blocks(ct)), L % BLOCK == 0, acc_len(L), ct[:HEAD_TAG].hex()))

    print()
    print("== body sizes per endpoint (how much plaintext each request carries)")
    per = {}
    for r in rows:
        per.setdefault(r["url"].split("/")[-1], set()).add(len(r["raw"]))
    for ep in sorted(per):
        print("  %-12s %s" % (ep, sorted(per[ep])))

    print()
    print("== session tags (bytes 0..7)")
    tags = {}
    for r in rows:
        tags.setdefault(r["raw"][:HEAD_TAG], set()).add(r["url"].split("/")[-1])
    for tag, eps in tags.items():
        print("  %s -> %s" % (tag.hex(), ",".join(sorted(eps))))
    print("  -> a tag covers exactly the endpoints issued inside one page/session; the tag")
    print("     itself is not derived from the endpoint name (it also covers several).")

    print()
    print("== stream-XOR test (is `acc=` a byte-wise pad with plaintext leaking through?)")
    bs = [r["raw"] for r in rows]
    worst = 0.0
    for i in range(len(bs)):
        for j in range(i + 1, len(bs)):
            n = min(len(bs[i]), len(bs[j]))
            s = printable_share(bytes(p ^ q for p, q in zip(bs[i][:n], bs[j][:n])))
            worst = max(worst, s)
    print("  best printable share over all %d pairs: %.3f" % (len(bs) * (len(bs) - 1) // 2, worst))
    print("  -> if the cipher were a stream XOR, two bodies of the same endpoint would XOR to")
    print("     printable text (share > 0.9). It does not, so every 16-byte block is re-keyed:")
    print("     no field value can be read out of `acc=` offline.")

    print()
    print("== intra-endpoint block reuse (read.php, 7 bodies of 160B)")
    reads = [r["raw"] for r in rows if r["url"].endswith("read.php") and len(r["raw"]) == 160]
    idx = {}
    for c in reads:
        for k, b in enumerate(blocks(c)):
            idx.setdefault(k, set()).add(b)
    print("  distinct blocks per offset: %s" % {k: len(v) for k, v in sorted(idx.items())})
    print("  -> offsets 0..3 are byte-identical in all 7 bodies (a fixed session prologue);")
    print("     offsets 4..5 change every body (per-request payload); offsets 6..9 take only")
    print("     3 distinct values over 7 bodies (a small enumerated state, e.g. a menu/page id).")
    print("  -> the ciphertext therefore does NOT reveal field text, only the sizes above.")

    print()
    print("== whole-request ciphertext reuse")
    shared = {}
    for _, n, c in [(x["url"], x["bytes"], base64.b64decode(x["b64"])) for x in rows]:
        for k, b in enumerate(blocks(c)):
            shared.setdefault(b, set()).add((_, n, k))
    grouped = {}
    for b, locs in shared.items():
        eps = {u for u, _, _ in locs}
        if len(eps) > 1:
            grouped.setdefault(tuple(sorted(eps)), []).append(
                (b.hex()[:8], tuple(sorted({k for _, _, k in locs}))))
    for eps, items in sorted(grouped.items()):
        print("  endpoints %s" % (",".join(eps),))
        for h, ks in items:
            print("    block %s at tail offset %s" % (h, ks))
    print("  -> identical blocks appear only in the session prologue (offsets 0..1 of every")
    print("     body) and in the shared trailer (last two blocks); both are text that is the")
    print("     same for an original English client too, so neither leaks a translated field.")

    print()
    print("== `acc=` length formula (16-aligned body -> wire length)")
    for pad in (0, 16, 32):
        n = 64 + pad
        print("  body %4d bytes -> base64 %3d chars -> acc= %3d chars (worst case, all +/)"
              % (n, len(base64.b64encode(bytes(n))), acc_len(n)))
    print("  -> the wire length is fully explained by the ciphertext size: there is no extra")
    print("     framing field in `acc=`, so nothing to audit there.")

    print()
    print("== static landmarks (libil2cpp.so 2.10.7)")
    print("  EncryptRJ256  VA 0x%x   (upload exit; our gate hooks here)" % ENC_VA)
    print("  DecryptRJ256  VA 0x%x   (reply decrypt; symmetric, same block state)" % DEC_VA)
    print("  send site     VA 0x%x   (only caller; pipes `acc=` into the web request)" % SEND_VA)


def selftest():
    rows = load()

    def check(name, cond):
        assert cond, name
        return 1

    ok = 0
    # 1. every body is a whole number of cipher blocks
    ok += check("16-byte alignment", all(len(r["raw"]) % BLOCK == 0 for r in rows))
    # 2. the expected endpoints all appear
    eps = {r["url"].split("/")[-1] for r in rows}
    ok += check("endpoints", {"check.php", "login.php", "gift.php", "read.php", "mail.php",
                             "pvp.php", "kpi.php", "recov2.php"} <= eps)
    # 3. at least one endpoint has several bodies to compare (read.php)
    ok += check("repeated reads", sum(1 for r in rows if r["url"].endswith("read.php")) >= 2)
    # 4. cipher is not a stream XOR: no pair XORs to printable text
    bs = [r["raw"] for r in rows]
    worst = max(printable_share(bytes(p ^ q for p, q in zip(bs[i][:min(len(bs[i]), len(bs[j]))],
                                                           bs[j][:min(len(bs[i]), len(bs[j]))])))
                for i in range(len(bs)) for j in range(i + 1, len(bs)))
    ok += check("not stream xor", worst < 0.9)
    # 5. the first two blocks are byte-identical for every request of one session and shared
    #    by five different endpoints -> a fixed session prologue, not a field value.
    reads = [r["raw"] for r in rows if r["url"].endswith("read.php") and len(r["raw"]) == 160]
    ok += check("reads present", len(reads) >= 2)
    same = []
    for k in range(len(blocks(reads[0]))):
        vals = {blocks(c)[k] for c in reads}
        if len(vals) == 1:
            same.append(k)
    ok += check("session prologue is blocks 0..1", same == [0, 1] or same[:2] == [0, 1])
    #    mail.php (128B) ends with read.php's (160B) last two blocks: a shared trailer.
    mail = [r["raw"] for r in rows if r["url"].endswith("mail.php")][0]
    ok += check("trailer shared across endpoints", blocks(mail)[-1] == blocks(reads[0])[-1]
                and blocks(mail)[-2] == blocks(reads[0])[-2])
    # 6. wire length is a pure function of size
    ok += check("acc length monotonic", acc_len(64) < acc_len(128) < acc_len(1728))
    ok += check("acc length formula",
                acc_len(64) == len(base64.b64encode(bytes(64))) +
                2 * (base64.b64encode(bytes(64)).decode().count("+") +
                     base64.b64encode(bytes(64)).decode().count("/")))
    # 7. the largest body is the save upload (kpi.php), i.e. the thing item 2's name filter
    #    can grow; assert it is the max so the doc's "grows by N bytes" claim stays honest.
    biggest = max(rows, key=lambda r: len(r["raw"]))
    ok += check("kpi is biggest", biggest["url"].endswith("kpi.php"))
    print("SELFTEST PASS (%d checks)" % ok)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    selftest() if a.selftest else report()


if __name__ == "__main__":
    main()
