#!/usr/bin/env python3
"""Create the app's patch (BSDIFF40, bzip2 — read by app/.../core/BsPatch.java) plus a small info json.

usage: python tools/make_patch.py <orig data.unity3d> <new data.unity3d> <out dir> [label]
writes <out dir>/elnea_zh.patch and <out dir>/elnea_zh.json
"""
import sys, os, json, hashlib
import bsdiff4


def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def main():
    orig, new, out = sys.argv[1:4]
    label = sys.argv[4] if len(sys.argv) > 4 else ''
    os.makedirs(out, exist_ok=True)
    patch = os.path.join(out, 'elnea_zh.patch')
    bsdiff4.file_diff(orig, new, patch)
    # self-check: the patch must reproduce the new file exactly
    tmp = patch + '.check'
    bsdiff4.file_patch(orig, tmp, patch)
    assert sha(tmp) == sha(new), 'bsdiff round-trip mismatch'
    os.remove(tmp)
    info = {'base_sha256': sha(orig), 'new_sha256': sha(new), 'new_size': os.path.getsize(new), 'label': label}
    json.dump(info, open(os.path.join(out, 'elnea_zh.json'), 'w'), ensure_ascii=False, indent=1)
    print('patch', os.path.getsize(patch), 'bytes;', info)


if __name__ == '__main__':
    main()
