#!/usr/bin/env python3
"""Commit local files to ShuiLiZe/ElneaZH in one commit via the gh-relay (Git Data API).
usage: ghpush.py "commit message" local_root path1 [path2 ...]   (paths relative to local_root, dirs ok)
       ghpush.py --delete "msg" repo_path ...
"""
import sys, os, base64, json, urllib.request

R = 'https://gh-relay.aap20020709.workers.dev'
REPO = 'ShuiLiZe/ElneaZH'
BR = 'main'


def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(R + path, data=data, method=method,
                                 headers={'User-Agent': 'hark', 'Content-Type': 'application/json',
                                          'Accept': 'application/vnd.github+json'})
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            return json.loads(r.read() or b'{}')
    except urllib.error.HTTPError as e:
        raise SystemExit(f'{method} {path}: {e.code} {e.read()[:500]}')


def main():
    args = sys.argv[1:]
    delete = args[0] == '--delete'
    if delete:
        args = args[1:]
    msg = args[0]
    ref = api('GET', f'/repos/{REPO}/git/ref/heads/{BR}')
    head = ref['object']['sha']
    base_tree = api('GET', f'/repos/{REPO}/git/commits/{head}')['tree']['sha']
    tree = []
    if delete:
        for p in args[1:]:
            tree.append({'path': p, 'mode': '100644', 'type': 'blob', 'sha': None})
    else:
        root = args[1]
        files = []
        for p in args[2:]:
            full = os.path.join(root, p)
            if os.path.isdir(full):
                for d, _, fs in os.walk(full):
                    if '__pycache__' in d or '/.gradle' in d or '/build/' in d + '/':
                        continue
                    for f in fs:
                        files.append(os.path.relpath(os.path.join(d, f), root))
            else:
                files.append(p)
        for rel in files:
            b = open(os.path.join(root, rel), 'rb').read()
            blob = api('POST', f'/repos/{REPO}/git/blobs',
                       {'content': base64.b64encode(b).decode(), 'encoding': 'base64'})
            mode = '100755' if os.access(os.path.join(root, rel), os.X_OK) and rel.endswith(('.sh', 'gradlew')) else '100644'
            tree.append({'path': rel.replace(os.sep, '/'), 'mode': mode, 'type': 'blob', 'sha': blob['sha']})
            print('  +', rel, len(b))
    t = api('POST', f'/repos/{REPO}/git/trees', {'base_tree': base_tree, 'tree': tree})
    c = api('POST', f'/repos/{REPO}/git/commits', {'message': msg, 'tree': t['sha'], 'parents': [head]})
    api('PATCH', f'/repos/{REPO}/git/refs/heads/{BR}', {'sha': c['sha']})
    print('committed', c['sha'][:10], msg)


if __name__ == '__main__':
    main()
