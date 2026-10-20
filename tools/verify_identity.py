"""复刻 Backup.wipeForIdentity 的最小删除集合，验证三件事：

1. 存档 sv1_* → 删
2. 登录凭证（PlayerPrefs 登录键、althi_uniq、login 库）→ 删
3. 下载缓存 / 汉化表 / elneazh_* / 资源版本键 → 必须留（删了要重新下数据包）

键名依据：global-metadata 字面量表索引（.work/p_keys2.txt）
  althi_uniq=10282 login_key=12937 uuid=15217 token=14859 user_id=15196
  user_uniq=15205 assetbundle_version=10436
  libunity.so: 'UnityCache' '__data' '__info'（原生 AssetBundle 缓存）
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 与 Backup.LOGIN_PREF_KEYS / LOGIN_PREF_FILES 保持一致
LOGIN_PREF_KEYS = {
    'uuid', 'login_key', 'althi_uniq', 'token', 'token_id',
    'user_id', 'user_uniq', 'user_pass', 'user_name', 'user_rename',
    'account', 'session', 'session_id', 'device_id',
}
LOGIN_PREF_FILES = {'althi_uniq', 'login_key', 'uuid', 'token'}


def pref_key_action(key, prefs_name):
    if key.lower().startswith('elneazh_'):
        return 'keep'
    base = prefs_name.lower()
    if base.endswith('.xml'):
        base = base[:-4]
    if base in LOGIN_PREF_FILES:
        return 'delete'
    return 'delete' if key.lower().strip() in LOGIN_PREF_KEYS else 'keep'


def file_action(path):
    """返回 delete / keep。"""
    p = path.replace('\\', '/')
    low = p.lower()
    name = low.rsplit('/', 1)[-1]
    # 缓存一律留
    if 'unitycache' in low or '/il2cpp' in low or '/code_cache' in low or '/cache/' in low:
        return 'keep'
    # 汉化表
    if name.startswith('c_'):
        return 'keep'
    # 我们的东西
    if name.startswith('elneazh_'):
        return 'keep'
    # 存档
    if name.startswith('sv1_') or name.endswith('.ntmp') or name.endswith('.rtmp'):
        return 'delete'
    # 无扩展名的登录凭证（files/althi_uniq）
    stem = name[:-4] if name.endswith('.xml') else name
    if stem in LOGIN_PREF_FILES:
        return 'delete'
    # 账号库（databases/login.db 这种没有目录前缀的也要能判出来）
    if any(k in name for k in ('login', 'account', 'token', 'session', 'althi')) and (
            '/databases/' in low or name.endswith(('.db', '.db-wal', '.db-shm', '.db-journal'))):
        return 'delete'
    if name.startswith('user') and name.endswith(('.db', '.db-wal', '.db-shm')):
        return 'delete'
    return 'keep'


def main():
    bad = 0
    print('--- files ---')
    files = [
        ('files/zh/sv1_ws_profiles', 'delete'),
        ('files/zh/sv1_ws_familys', 'delete'),
        ('files/Contents/sv1_ws_optiondata', 'delete'),
        ('external_files/UnityCache/Shared/fig_000_en.android~abc/__data', 'keep'),
        ('external_files/UnityCache/Shared/fig_000_en.android~abc/__info', 'keep'),
        ('files/zh/c_item', 'keep'),
        ('files/zh/c_quest', 'keep'),
        ('files/althi_uniq', 'delete'),
        ('shared_prefs/althi_uniq.xml', 'delete'),
        ('shared_prefs/jp.co.althi.elnea.en.v2.playerprefs.xml', 'keep'),  # 只删键，不删文件
        ('shared_prefs/elneazh_names.xml', 'keep'),
        ('databases/login.db', 'delete'),
        ('files/elneazh_selfcheck.txt', 'keep'),
    ]
    for p, want in files:
        got = file_action(p)
        flag = 'OK ' if got == want else 'BAD'
        if got != want:
            bad += 1
        print(f'{flag} {got:6} want={want:6} {p}')

    print('--- prefs keys (主 PlayerPrefs) ---')
    name = 'jp.co.althi.elnea.en.v2.playerprefs'
    keys = [
        ('uuid', 'delete'), ('login_key', 'delete'), ('althi_uniq', 'delete'),
        ('token', 'delete'), ('user_id', 'delete'), ('user_uniq', 'delete'),
        ('assetbundle_version', 'keep'), ('volume_bgm', 'keep'), ('elneazh_names_x', 'keep'),
    ]
    for k, want in keys:
        got = pref_key_action(k, name)
        flag = 'OK ' if got == want else 'BAD'
        if got != want:
            bad += 1
        print(f'{flag} {got:6} want={want:6} {k}')

    print('minimal wipe OK' if bad == 0 else f'minimal wipe WRONG ({bad})')
    return 0 if bad == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
