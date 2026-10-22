package com.shuilize.elneazh.inj;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PlayerPrefs / 身份键纯逻辑单测（纯 JDK，不需要手机）：
 *   - 哪些 prefs 键算「身份 / 登录」，哪些必须保留（设置、资源包版本、汉化自身）
 *   - 换身份删除、「只恢复存档」跳过，用的是同一套判定口径
 *   - URL 编码键名（PlayerPrefs 里 key 可能被 %XX 转义）也要认出来
 *   - 宽匹配 + 白名单的优先级
 */
public final class PlayerPrefsTest {

    private static int n = 0;
    private static int fail = 0;

    private static void ok(boolean c, String msg) {
        n++;
        if (!c) {
            fail++;
            System.out.println("  FAIL " + msg);
        } else {
            System.out.println("  ok   " + msg);
        }
    }

    /** 用户点名的身份键：全部必须判成身份。 */
    private static final String[] IDENTITY_KEYS = {
        "uuid", "uid", "user_id", "token", "token_id", "session", "session_id",
        "login_key", "loginKey", "althi_uniq", "user_uniq", "user_pass", "account",
        "device_id", "device_uuid", "migrant_id", "migrate_token", "transfer_code",
        "first_launch_uuid", "unity.cloud_userid", "ulid", "udid", "user_rename"
    };

    /** 用户点名必须保留的：汉化自身、资源包、设置、存档数据。 */
    private static final String[] KEEP_KEYS = {
        "elneazh_offline", "elneazh_share_toast", "assetbundle_version", "ab_version",
        "volume", "bgm_volume", "quality_level", "language", "lang", "resolution_scale",
        "sound_on", "bgm_on", "shadow_quality", "userdata_cache", "player_session_cache",
        "user_dict", "user_skill", "save_path", "autosave"
    };

    public static void main(String[] a) {
        System.out.println("PlayerPrefs 离线单测（IdentityKeys）");

        // ---- 1. 身份键判定 ----
        for (String k : IDENTITY_KEYS) {
            ok(IdentityKeys.isIdentityKey(k), "identity key detected: " + k);
        }
        ok(!IdentityKeys.isIdentityKey(null), "null key is not identity");
        ok(!IdentityKeys.isIdentityKey(""), "empty key is not identity");

        // ---- 2. 必须保留 ----
        for (String k : KEEP_KEYS) {
            ok(!IdentityKeys.isIdentityKey(k), "kept (setting/asset/hanhua): " + k);
        }

        // ---- 3. 大小写不敏感 ----
        ok(IdentityKeys.isIdentityKey("UUID"), "case-insensitive: UUID");
        ok(IdentityKeys.isIdentityKey(" Login_Key "), "trim + case: ' Login_Key '");

        // ---- 4. URL 编码键名 ----
        ok(IdentityKeys.isIdentityKey("login%5Fkey"), "percent-decoded key: login%5Fkey");
        ok(IdentityKeys.isIdentityKey("user%5Fid"), "percent-decoded key: user%5Fid");
        ok(IdentityKeys.decode("a%2Fb").equals("a/b"), "decode handles %2F");

        // ---- 5. 白名单优先级高于宽匹配 ----
        ok(!IdentityKeys.isIdentityKey("elneazh_uuid_hint"),
                "elneazh_* prefix always kept even if it contains uuid");
        ok(!IdentityKeys.isIdentityKey("assetbundle_version_login_ts"),
                "KEEP part wins over WIDE part (assetbundle_version ... login)");
        ok(IdentityKeys.isIdentityKey("cached_user_id"),
                "wide match: cached_user_id");
        ok(IdentityKeys.isIdentityKey("migrant_uniq"),
                "wide match: migrant_uniq");

        // ---- 6. 宽匹配关闭时只认固定名单 ----
        ok(IdentityKeys.isLoginPrefKey("uuid", false), "narrow: uuid in fixed list");
        ok(!IdentityKeys.isLoginPrefKey("cached_user_id", false),
                "narrow: cached_user_id not in fixed list");
        ok(IdentityKeys.isLoginPrefKey("cached_user_id", true),
                "wide: cached_user_id matched");

        // ---- 7. 批量筛选：保持顺序、去重、只留身份键 ----
        List<String> mixed = Arrays.asList(
                "uuid", "volume", "token", "uuid", "elneazh_offline", "session", "quality_level");
        Set<String> picked = IdentityKeys.filterIdentityKeys(mixed);
        ok(picked.equals(new LinkedHashSet<>(Arrays.asList("uuid", "token", "session"))),
                "filterIdentityKeys -> " + picked);

        // ---- 8. 两处口径一致（删除 / 只恢复存档 共用同一判定）----
        boolean same = true;
        for (String k : IDENTITY_KEYS) {
            same &= IdentityKeys.isIdentityKey(k) == IdentityKeys.isLoginPrefKey(k, true);
        }
        ok(same, "delete path and restore-skip path share one predicate for all identity keys");

        System.out.println();
        System.out.println(n + " checks, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }
}
