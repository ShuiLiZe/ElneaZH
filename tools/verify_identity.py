"""复刻 Backup.wipeForIdentity 的保留/删除判定，验证不会误删汉化主表与 elneazh_* 设置。

清单来源：make() 写入 elneazh_backup.txt 的 "<path>\t<size>" 行；保留白名单 = tableNames()
（assets/elneazh/Contents 里不以 sv1_ 开头的名字）+ shared_prefs/elneazh_*。
"""
import sys
import zipfile
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def table_names(assets_contents: Path):
    return {p.name for p in assets_contents.iterdir() if p.is_file() and not p.name.startswith('sv1_')}


def decide(path: str, keep: set):
    base = path.rsplit('/', 1)[-1]
    if path.startswith('shared_prefs/'):
        if base.startswith('elneazh_'):
            return 'keep'
    else:
        if path.startswith(('ext/zh/', 'ext/Contents/', 'files/zh/', 'files/Contents/')):
            if base in keep:
                return 'keep'
    return 'delete'


def main():
    keep = table_names(ROOT / 'content_zh/Contents')
    print(f'tables kept: {len(keep)} -> {sorted(keep)}')
    sample = [
        'files/zh/' + sorted(keep)[0],
        'files/zh/sv1_ws_profiles',
        'files/zh/sv1_ws_familys',
        'files/zh/sv1_ws_profiles_org',
        'files/Contents/sv1_ws_optiondata',
        'shared_prefs/jp.co.althi.elnea.en.v2.playerprefs.xml',
        'shared_prefs/elneazh_names.xml',
        'shared_prefs/elneazh_backup.xml',
        'shared_prefs/elneazh_identity.xml',
        'ext/zh/sv1_ws_profiles',
        'ext/UnityCache/Shared/x',
        'databases/login.db',
    ]
    bad = 0
    for p in sample:
        d = decide(p, keep)
        print(f'{d:6} {p}')
        if p.endswith('playerprefs.xml') and d != 'delete':
            bad += 1
        if 'sv1_' in p and d != 'delete':
            bad += 1
        if ('elneazh_' in p or p in ('files/zh/' + sorted(keep)[0],)) and d != 'keep':
            bad += 1
    print('wipe rules OK' if bad == 0 else f'wipe rules WRONG ({bad})')
    return 0 if bad == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
