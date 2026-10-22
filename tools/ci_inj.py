#!/usr/bin/env python3
"""本地编译校验收到的 inj/*.java（与 CI 的 classes3.dex 步骤一致）。

CI 在 build.yml 里是：
  javac --release 8 -cp $PLAT/android.jar -d build/inj $(find inj -name '*.java')
  d8 --min-api 21 --lib $PLAT/android.jar --output build/inj build/inj/**/*.class
但 CI 只在 main 上跑，功能分支只能本地这样验。

用法: python tools/ci_inj.py [--sdk ANDROID_SDK_ROOT]
找 android.jar：ANDROID_SDK_ROOT / ANDROID_HOME / %LOCALAPPDATA%\\Android\\Sdk 下的 platforms\\android-33|31|30…
"""
import argparse
import glob
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, '.work', 'injbuild')


def android_jar(sdk=None):
    cands = []
    if sdk:
        cands.append(sdk)
    for k in ('ANDROID_SDK_ROOT', 'ANDROID_HOME', 'ANDROID_SDK'):
        if os.environ.get(k):
            cands.append(os.environ[k])
    cands.append(os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk'))
    cands.append(os.path.expanduser('~/Android/Sdk'))
    for c in cands:
        if not c:
            continue
        plat = os.path.join(c, 'platforms')
        if not os.path.isdir(plat):
            continue
        vers = sorted(os.listdir(plat), reverse=True)
        for v in vers:
            j = os.path.join(plat, v, 'android.jar')
            if os.path.isfile(j):
                return j
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--sdk', default=None)
    a = ap.parse_args()
    jar = android_jar(a.sdk)
    if not jar:
        print('android.jar not found; set ANDROID_SDK_ROOT', file=sys.stderr)
        return 2
    os.makedirs(OUT, exist_ok=True)
    srcs = sorted(p for p in glob.glob(os.path.join(ROOT, 'inj', '**', '*.java'), recursive=True))
    if not srcs:
        print('no java sources', file=sys.stderr)
        return 2
    cmd = ['javac', '-encoding', 'UTF-8', '--release', '8', '-nowarn', '-cp', jar, '-d', OUT] + srcs
    print('android.jar:', jar)
    print('sources:', len(srcs))
    p = subprocess.run(cmd, capture_output=True)
    sys.stdout.write(p.stdout.decode('utf-8','replace'))
    sys.stderr.write((p.stderr or b'').decode('utf-8','replace'))
    if p.returncode != 0:
        print('javac FAILED', file=sys.stderr)
        return 1
    n = len(glob.glob(os.path.join(OUT, '**', '*.class'), recursive=True))
    print('javac OK, %d classes' % n)
    return 0


if __name__ == '__main__':
    sys.exit(main())


