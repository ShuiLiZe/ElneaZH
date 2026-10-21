"""复刻 Backup.wipeForIdentity 的「宽匹配」删除集合，验证四件事：

1. 存档 sv1_* + 存档兄弟文件（version.txt / *.tmp / *.org）+ 登录目录 → 删
2. 登录凭证（PlayerPrefs 登录键·宽匹配、althi_uniq、login 库、unity.cloud_userid）→ 删
3. 下载缓存 / 汉化表 / elneazh_* / 资源版本键 / 音量画面设置 → 必须留（删了要重新下数据包）
4. 真机事故复现：上一版只删 14 个固定键名 → 仍卡登录（'already login!'），
   所以这里把「真机上真实存在、旧版漏掉」的键名逐个列出来要求 delete。

依据（global-metadata 字面量 + 真机 prefs）：
  uuid login_key althi_uniq token token_id user_id user_uniq user_pass user_name
  user_rename account session session_id device_id migrant_id udid ulid
  unity.cloud_userid first_launch clientDevice clientVersion chnl
  SaveData 字段：_loginDirectory GetLoginDirectory saveFolder contentsFolder
  isBrokenSave WriteVersionText ReadVersionText orgFileKey tmpSaveFolder
  libunity.so: 'UnityCache' '__data' '__info'（原生 AssetBundle 缓存）与 '.v2.playerprefs'
  LoginRequest/AccountInputPanel：'already login!' 'login.php' 'create.php'
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 与 Backup.LOGIN_PREF_KEYS / LOGIN_PREF_FILES / WIDE_KEY_PARTS / KEEP_KEY_PARTS 一致
LOGIN_PREF_KEYS = {
    'uuid', 'login_key', 'althi_uniq', 'token', 'token_id',
    'user_id', 'user_uniq', 'user_pass', 'user_name', 'user_rename',
    'account', 'session', 'session_id', 'device_id',
}
LOGIN_PREF_FILES = {'althi_uniq', 'login_key', 'uuid', 'token'}
WIDE_KEY_PARTS = (
    'uuid', 'ulid', 'udid', 'login', 'uniq', 'token', 'session', 'account',
    'migrant', 'migrate', 'transfer', 'first_launch', 'firstlaunch',
    'device', 'user_id', 'user_uniq', 'user_pass', 'user_name',
)
KEEP_KEY_PARTS = (
    'elneazh_', 'assetbundle_version', 'ab_version', 'volume', 'sound', 'bgm',
    'se_', 'quality', 'resolution', 'language', 'lang', 'brightness', 'frame',
    'graphics', 'shadow', 'vsync', 'fullscreen', 'notify', 'save_path',
    'autosave', 'font', 'scale', 'user_dict', 'user_skill', 'user_item',
    'user_weapon', 'user_mailbox', 'user_sale', 'user_right', 'user_group',
    'user_locale', 'userdata', 'player_session',
)
LOGIN_DIR_HINTS = ('login', 'account', 'uuid')
SAVE_SIBLING_EXT = ('.txt', '.tmp', '.bak', '.org')


def pref_key_action(key, prefs_name, wide=True):
    """复刻 Backup.isLoginPrefKey(k, wide) + clearLoginPrefs 的整文件规则。"""
    low = key.lower().strip()
    if low.startswith('elneazh_'):
        return 'keep'
    base = prefs_name.lower()
    if base.endswith('.xml'):
        base = base[:-4]
    if base in LOGIN_PREF_FILES:
        return 'delete'          # 凭证 xml 整份删
    if low in LOGIN_PREF_KEYS:
        return 'delete'
    if not wide:
        return 'keep'
    if any(k in low for k in KEEP_KEY_PARTS):
        return 'keep'
    if low == 'unity.cloud_userid':
        return 'delete'
    return 'delete' if any(k in low for k in WIDE_KEY_PARTS) else 'keep'


def file_action(path):
    """返回 delete / keep。"""
    p = path.replace('\\', '/')
    low = p.lower()
    name = low.rsplit('/', 1)[-1]
    parts = [x for x in low.split('/') if x]

    # 缓存一律留（UnityCache / il2cpp / cache / code_cache）
    if 'unitycache' in low or '/il2cpp' in low or '/code_cache' in low or '/cache/' in low:
        return 'keep'
    # 汉化表 / 我们自己的东西
    if name.startswith('c_') or name.startswith('elneazh_'):
        return 'keep'

    # 登录目录（整层删）：files/login、files/AccountXYZ …
    if any(h in parts for h in LOGIN_DIR_HINTS) and '/databases/' not in low:
        if not name.endswith('.db') and not name.endswith('.db-wal'):
            return 'delete'

    # 存档本体与兄弟文件
    if name.startswith('sv1_') or name.endswith('.ntmp') or name.endswith('.rtmp'):
        return 'delete'
    if any(f'/{d}/' in low for d in ('zh', 'contents', 'save', 'saves')) \
            and name.endswith(SAVE_SIBLING_EXT):
        return 'delete'

    # 无扩展名的登录凭证（files/althi_uniq）
    stem = name[:-4] if name.endswith('.xml') else name
    if stem in LOGIN_PREF_FILES:
        return 'delete'
    if '/no_backup/' in low or low.count('/') <= 1:
        if any(h in stem for h in ('althi_uniq', 'login', 'uuid', 'token', 'account')):
            return 'delete'

    # 账号库
    if any(k in name for k in ('login', 'account', 'token', 'session', 'althi', 'uuid')) and (
            '/databases/' in low or name.endswith(('.db', '.db-wal', '.db-shm', '.db-journal'))):
        return 'delete'
    if name.startswith('user') and name.endswith(('.db', '.db-wal', '.db-shm')):
        return 'delete'
    return 'keep'


def main():
    bad = 0

    def check(area, got, want, label):
        nonlocal bad
        ok = got == want
        if not ok:
            bad += 1
        print(f'{"OK " if ok else "BAD"} {area:9} {got:6} want={want:6} {label}')

    print('--- files ---')
    files = [
        # 存档
        ('files/zh/sv1_ws_profiles', 'delete'),
        ('files/zh/sv1_ws_familys', 'delete'),
        ('files/Contents/sv1_ws_optiondata', 'delete'),
        ('files/zh/sv1_ws_profiles_org', 'delete'),
        ('files/zh/sv1_ws_profiles.ntmp', 'delete'),
        # 存档兄弟文件（真机上留着会被当旧档摘要）
        ('files/zh/save_version.txt', 'delete'),
        ('files/Contents/save_version.txt', 'delete'),
        # 登录目录（旧版整层没删 → 服务器照样 already login!）
        ('files/login', 'delete'),
        ('files/LoginData/sv1_ws_profiles', 'delete'),
        ('files/account', 'delete'),
        # 必须留
        ('external_files/UnityCache/Shared/fig_000_en.android~abc/__data', 'keep'),
        ('external_files/UnityCache/Shared/fig_000_en.android~abc/__info', 'keep'),
        ('files/il2cpp/abc.dat', 'keep'),
        ('files/zh/c_item', 'keep'),
        ('files/zh/c_quest', 'keep'),
        ('files/elneazh_selfcheck.txt', 'keep'),
        ('shared_prefs/elneazh_names.xml', 'keep'),
        # 凭证
        ('files/althi_uniq', 'delete'),
        ('shared_prefs/althi_uniq.xml', 'delete'),
        ('shared_prefs/jp.co.althi.elnea.en.v2.playerprefs.xml', 'keep'),  # 只删键，不删文件
        ('databases/login.db', 'delete'),
        ('databases/AccountDB.db-wal', 'delete'),
    ]
    for p, want in files:
        check('files', file_action(p), want, p)

    print('--- prefs keys (主 PlayerPrefs, 宽匹配) ---')
    name = 'jp.co.althi.elnea.en.v2.playerprefs'
    keys = [
        # 真机上真实存在 / 旧版漏掉的
        ('uuid', 'delete'), ('login_key', 'delete'), ('althi_uniq', 'delete'),
        ('token', 'delete'), ('token_id', 'delete'), ('user_id', 'delete'),
        ('user_uniq', 'delete'), ('user_pass', 'delete'), ('user_name', 'delete'),
        ('user_rename', 'delete'), ('account', 'delete'), ('session', 'delete'),
        ('session_id', 'delete'), ('device_id', 'delete'),
        ('migrant_id', 'delete'), ('udid', 'delete'), ('ulid', 'delete'),
        ('unity.cloud_userid', 'delete'), ('first_launch_time', 'delete'),
        ('clientDevice', 'delete'), ('last_login_uuid', 'delete'), ('LoginData', 'delete'),
        # 客户端自算的版本/渠道不是身份 —— 留着（删了只会重算，且丢了要重新校验版本）
        ('client_version', 'keep'), ('chnl', 'keep'),
        # 必须留
        ('assetbundle_version', 'keep'), ('volume_bgm', 'keep'), ('elneazh_names_x', 'keep'),
        ('unity.player_session_count', 'keep'), ('Screenmanager Resolution Width', 'keep'),
        ('language', 'keep'), ('save_path', 'keep'), ('user_dict_flag', 'keep'),
    ]
    for k, want in keys:
        check('prefs', pref_key_action(k, name), want, k)

    print('--- 凭证 xml 整份删 ---')
    for k, want in [('volume', 'delete'), ('anything', 'delete')]:
        check('prefs-xml', pref_key_action(k, 'althi_uniq.xml'), want,
              f'althi_uniq.xml key={k}')

    print('--- 旧版行为必须能复现事故（防回归） ---')
    # 旧版 = 只认 14 个固定键名；migrant_id 之类会漏 → 说明宽匹配是必要的
    for k in ('migrant_id', 'unity.cloud_userid', 'clientDevice'):
        check('wide-only', pref_key_action(k, name, wide=False), 'keep',
              f'{k} 窄匹配会漏（这就是事故原因）')

    print('--- 真机贴合的 prefs 文件枚举规则 ---')
    # clearAllPrefs 必须遍历 shared_prefs 下所有 xml（/data/user_de 也生效），只跳过 elneazh_
    for f, want in [('elneazh_backup.xml', 'skip'), ('jp.co.althi.elnea.en.v2.playerprefs.xml', 'scan'),
                    ('com.google.android.gms.appid.xml', 'scan'), ('althi_uniq.xml', 'scan')]:
        got = 'skip' if f.lower().startswith('elneazh_') else 'scan'
        check('prefs-scan', got, want, f)

    # ---------------------------------------------------------------- 双恢复模式
    # 真机事故：换身份后导入老存档，restore 把备份里的 uuid/login_key 写回 ->
    # 身份又变回旧的，还是卡登录。所以恢复必须分两种，且「只恢复存档」一个身份键都不能碰。
    print('--- 恢复存档：完整 / 只恢复存档 两种模式 ---')
    import os as _os
    bj = open(_os.path.join(ROOT, 'inj', 'com', 'shuilize', 'elneazh', 'inj', 'Backup.java'),
              encoding='utf-8').read()
    got_sig = 'has-param' if ('final Uri u, final boolean withIdentity' in bj) else '旧签名'
    check('restore', got_sig, 'has-param', 'restore 带 withIdentity 参数')
    check('restore', 'has' if 'restore(a, u, true)' in bj else 'missing',
              'has', '存在「完整恢复（含身份）」入口')
    check('restore', 'has' if 'restore(a, u, false)' in bj else 'missing',
              'has', '存在「只恢复存档（保留当前身份）」入口')
    check('restore', 'has' if '完整恢复（含身份）' in bj else 'missing',
              'has', 'UI 文案「完整恢复（含身份）」')
    check('restore', 'has' if '只恢复存档（保留当前身份）' in bj else 'missing',
              'has', 'UI 文案「只恢复存档（保留当前身份）」')
    got_skip = ('has' if ('!withIdentity && p.startsWith("shared_prefs/")' in bj
                          and 'isIdentityPrefFile(' in bj) else 'missing')
    check('restore', got_skip, 'has', '只恢复存档时跳过身份 prefs 文件')
    # 关键：只恢复存档模式下 restoreIdentityFile 绝不能被无条件调用
    check('restore', 'guarded' if 'if (withIdentity) {' in bj and 'restoreIdentityFile(a, prefsXml)' in bj
              else 'unguarded', 'guarded', 'restoreIdentityFile 受 withIdentity 守卫')
    check('restore', 'has' if 'stripIdentityKeys' in bj else 'missing',
              'has', '只恢复存档时逐键剔除身份键（保留画面/音量设置）')
    check('restore', 'has' if 'isIdentityKey' in bj else 'missing',
              'has', '身份键共用同一个判定 isIdentityKey')

    # 两处必须同源：换身份删除 / 只恢复存档剔除，都用 isIdentityKey
    check('identity-key', 'single-source'
              if bj.count('isIdentityKey(') >= 4 and 'isLoginPrefKey(k, true) || isLoginPrefKey' not in
              bj.split('static boolean isIdentityKey')[1].split('}')[0] + '}'
              else 'diverged', 'single-source',
              'isIdentityKey 是唯一身份键判据（删除与恢复共用）')
    # 身份键必须真的覆盖第 6 项列出的全部键 + 设备指纹
    for k in ('uuid', 'login_key', 'althi_uniq', 'token', 'token_id', 'user_id', 'user_uniq',
              'user_pass', 'user_name', 'user_rename', 'account', 'session', 'session_id', 'device_id'):
        check('identity-key', pref_key_action(k, 'jp.co.althi.elnea.en.v2.playerprefs.xml'),
                  'delete', f'身份键 {k} 会被剔除')
    check('identity-key', pref_key_action('unity.cloud_userid', 'x.xml'), 'delete',
              '设备指纹 unity.cloud_userid 会被剔除')
    # 反向：设置键不能被误删
    for k in ('volume', 'bgm_volume', 'assetbundle_version', 'quality_level'):
        check('identity-key', pref_key_action(k, 'jp.co.althi.elnea.en.v2.playerprefs.xml'),
                  'keep', f'设置键 {k} 保留（只恢复存档不能丢设置）')

    # 身份键判定抽到 IdentityKeys（纯 JDK，单测直接测），Backup 只转发
    ik = (ROOT / 'inj/com/shuilize/elneazh/inj/IdentityKeys.java').read_text(encoding='utf-8')
    check('identity-key', 'has' if 'WIDE_KEY_PARTS' in ik else 'missing', 'has',
          'IdentityKeys 集中列出宽匹配词根')
    check('identity-key', 'has' if '"uid"' in ik else 'missing', 'has',
          'uid 也算身份键（游戏 login.php 的 uid/pas 参数）')
    check('identity-key', 'has' if 'filterIdentityKeys' in ik else 'missing', 'has',
          'IdentityKeys.filterIdentityKeys 供批量筛选复用')
    check('identity-key', 'has' if 'IdentityKeys.isLoginPrefKey' in bj else 'missing', 'has',
          'Backup 的身份判定转发到 IdentityKeys')
    pp = ROOT / 'tools/offline_test/com/shuilize/elneazh/inj/PlayerPrefsTest.java'
    check('identity-key', 'has' if pp.exists() else 'missing', 'has',
          'PlayerPrefsTest 纯 JDK 单测存在（CI 必跑）')

    # ---------------------------------------------------------------- 外部身份副本
    # 真机事故 2：清了 files/ 与 prefs 键，getExternalFilesDir(null)/althi_uniq 里那份
    # 41 字符设备码还在，重开游戏又把旧身份读了回来 —— 用户「卡在上个账号」的直接原因。
    print('--- 换身份：游戏目录之外的身份副本 ---')
    idf = ROOT / 'inj/com/shuilize/elneazh/inj/IdentityFiles.java'
    check('id-copy', 'has' if idf.exists() else 'missing', 'has',
          'IdentityFiles（纯 JDK 口径）存在')
    idt = idf.read_text(encoding='utf-8') if idf.exists() else ''
    for n in ('althi_uniq', 'ws_account', 'login_key', 'unity.cloud_userid'):
        check('id-copy', 'has' if f'"{n}"' in idt else 'missing', 'has',
              f'IdentityFiles 认 {n} 是身份副本')
    check('id-copy', 'has' if 'isRestoreIdentityCopy' in idt else 'missing', 'has',
          'IdentityFiles.isRestoreIdentityCopy 供「只恢复存档」复用')
    check('id-copy', 'has' if 'wipeIdentityCopies' in bj else 'missing', 'has',
          'wipeForIdentity 调 wipeIdentityCopies 删外部副本')
    check('id-copy', 'has' if 'getExternalFilesDir(null)' in bj else 'missing', 'has',
          '扫描 getExternalFilesDir(null)（外置 files/althi_uniq 就在这里）')
    check('id-copy', 'has' if 'getExternalFilesDirs' in bj else 'missing', 'has',
          '扫描 getExternalFilesDirs（第二张卡/多用户）')
    check('id-copy', 'has' if 'DIRECTORY_DOCUMENTS' in bj and 'DIRECTORY_DOWNLOADS' in bj
          else 'missing', 'has', '扫描 /sdcard/Documents 与 Downloads 的副本')
    check('id-copy', 'has' if 'isRestoreIdentityCopy(p)' in bj else 'missing', 'has',
          '只恢复存档时跳过备份里的身份副本')
    check('id-copy', 'has' if 'shared_prefs/elneazh_settings' in bj else 'missing', 'has',
          '单机开关不进备份（恢复不覆盖当前单机设置）')
    iw = ROOT / 'tools/offline_test/com/shuilize/elneazh/inj/IdentityWipeTest.java'
    check('id-copy', 'has' if iw.exists() else 'missing', 'has',
          'IdentityWipeTest 纯 JDK 单测存在（CI 必跑）')

    print('identity wipe OK' if bad == 0 else f'identity wipe WRONG ({bad})')
    return 0 if bad == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
