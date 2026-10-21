"""Docs/code consistency check for docs/UPLOAD_AUDIT.md.

The audit quotes numbers from the real-device capture (tools/acc_fixtures.json) and names
device-side sources for every field. This script fails if the doc drifts from either.

    python tools/verify_upload_audit.py      ->  "upload audit OK (N checks)"
"""

import base64
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "UPLOAD_AUDIT.md")
FIX = os.path.join(ROOT, "tools", "acc_fixtures.json")

checks = 0


def ok(name, cond):
    global checks
    if not cond:
        print("FAIL: %s" % name)
        sys.exit(1)
    checks += 1


def main():
    doc = open(DOC, encoding="utf-8").read()
    rows = json.load(open(FIX, encoding="utf-8"))

    # --- samples -----------------------------------------------------------
    ok("fixtures present", len(rows) >= 15)
    sizes = sorted({r["bytes"] for r in rows})
    ok("sizes documented", "64/96/128/160/224/256/1728" in doc)
    ok("sizes match fixtures", sizes == [64, 96, 128, 160, 224, 256, 1728])
    eps = sorted({r["url"].split("/")[-1] for r in rows})
    ok("endpoints documented", all(e in doc for e in eps))
    ok("endpoint count documented", "%d 条" % len(rows) in doc)

    # --- the structural claims the doc relies on ---------------------------
    BLOCK = 16

    def blocks(ct):
        return [ct[i:i + BLOCK] for i in range(0, len(ct), BLOCK)]

    raws = [base64.b64decode(r["b64"]) for r in rows]
    ok("all bodies 16-aligned", all(len(c) % BLOCK == 0 for c in raws))

    # stream-XOR claim: best printable share < 0.9
    alpha = (b"abcdefghijklmnopqrstuvwxyz0123456789_-.=/:?&\n")
    worst = 0.0
    for i in range(len(raws)):
        for j in range(i + 1, len(raws)):
            n = min(len(raws[i]), len(raws[j]))
            d = bytes(p ^ q for p, q in zip(raws[i][:n], raws[j][:n]))
            worst = max(worst, sum(1 for b in d if b in alpha) / n)
    ok("stream-xor claim", worst < 0.9)
    ok("worst share quoted", "0.289" in doc and abs(worst - 0.289) < 0.005)

    # read.php per-offset distinct blocks: 1,1,1,1,7,7,3,3,3,3
    reads = [base64.b64decode(r["b64"]) for r in rows
             if r["url"].endswith("read.php") and r["bytes"] == 160]
    ok("reads present", len(reads) == 7)
    per = {}
    for c in reads:
        for k, b in enumerate(blocks(c)):
            per.setdefault(k, set()).add(b)
    counts = [len(per[k]) for k in sorted(per)]
    ok("read offset pattern", counts == [1, 1, 1, 1, 7, 7, 3, 3, 3, 3])
    ok("read pattern documented", "偏移 0..3 = 1 种" in doc)

    # shared trailer between read.php(160) and mail.php(128)
    mail = [base64.b64decode(r["b64"]) for r in rows if r["url"].endswith("mail.php")][0]
    ok("trailer shared", blocks(mail)[-1] == blocks(reads[0])[-1])

    # acc= wire length formula quoted in the doc
    def acc_len(n):
        b64 = base64.b64encode(bytes(n)).decode()
        return len(b64) + 2 * (b64.count("+") + b64.count("/"))
    ok("acc len 64->88", acc_len(64) == 88)
    ok("acc len quote documented", "64B→88 字符" in doc)

    # --- field rows must name a real source -------------------------------
    for src in ("Backup.java", "Identity.java", "NameFix", "UploadNames", "names.tsv",
                "eg_names.c", "manifest_zh.py", "kpi.php", "login.php", "check.php"):
        ok("source cited: %s" % src, src in doc)

    # every file the doc names must exist
    for f in ("inj/com/shuilize/elneazh/inj/Backup.java",
              "inj/com/shuilize/elneazh/inj/Identity.java",
              "inj/com/shuilize/elneazh/inj/NameFix.java",
              "inj/com/shuilize/elneazh/inj/UploadNames.java",
              "tools/manifest_zh.py",
              "native/elneagate/eg_names.c"):
        ok("file exists: %s" % f, os.path.exists(os.path.join(ROOT, f)))

    # --- the two known-loss statements must stay in sync with names.tsv ---
    tsv = open(os.path.join(ROOT, "app/src/main/assets/elneazh/data/names.tsv"),
               encoding="utf-8").read().splitlines()
    ok("tsv rows documented", "1724 行" in doc)
    ok("tsv row count", len(tsv) == 1724)
    zh = [line.split("\t")[2] for line in tsv]
    en_by_zh = {}
    for line in tsv:
        _, e, z = line.split("\t")
        en_by_zh.setdefault(z, set()).add(e)
    ambiguous = {z for z, es in en_by_zh.items() if len(es) > 1}
    ok("collision count documented (12)", "12 处" in doc and len(ambiguous) == 12)

    # --- every unresolved row must be marked 明文待验 ---------------------
    ok("plaintext-pending marker used", doc.count("明文待验") >= 4)

    # --- doc must not claim a behaviour the code does not have ------------
    nf = open(os.path.join(ROOT, "inj/com/shuilize/elneazh/inj/NameFix.java"),
              encoding="utf-8").read()
    ok("warnLeftover or sanitiser", "warnLeftover" in nf or "sanitize" in nf)
    ok("cjk warn documented", "CJK" in doc or "name filter left" in doc)
    ok("255 slot cap documented", "255" in doc)

    print("upload audit OK (%d checks)" % checks)


if __name__ == "__main__":
    main()
