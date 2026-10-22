package com.shuilize.elneazh.inj;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 身份 / 登录键判定（纯逻辑，不碰 Android API，可直接在 JDK 上跑单测）。
 *
 * <p>换身份删除与「只恢复存档（保留当前身份）」跳过都用这一套判定，
 * 保证两处口径一致 —— 一处删了、另一处又写回来等于没换。
 *
 * <p>三档：
 * <ol>
 *   <li>固定名单 {@link #LOGIN_PREF_KEYS}：已知的登录键；</li>
 *   <li>宽匹配 {@link #WIDE_KEY_PARTS}：键名里带 uuid / login / token / session 等片段的都算；</li>
 *   <li>白名单 {@link #KEEP_KEY_PARTS}：资源包版本、音量、画质、汉化自身等，永远不算身份。</li>
 * </ol>
 */
public final class IdentityKeys {

    private IdentityKeys() {}

    /** 已知登录键固定名单（小写）。 */
    public static final Set<String> LOGIN_PREF_KEYS = new HashSet<>(Arrays.asList(
            "uuid", "login_key", "althi_uniq", "token", "token_id",
            "user_id", "user_uniq", "user_pass", "user_name", "user_rename",
            "account", "session", "session_id", "device_id"));

    /** 宽匹配：键名命中任一即视为身份键（小写）。 */
    public static final String[] WIDE_KEY_PARTS = {
            "uuid", "ulid", "udid", "uid", "login", "uniq", "token", "session", "account",
            "migrant", "migrate", "transfer", "first_launch", "firstlaunch",
            "device", "user_id", "user_uniq", "user_pass", "user_name"};

    /** 白名单：命中即「不是身份键」，优先级高于宽匹配。 */
    public static final String[] KEEP_KEY_PARTS = {
            "elneazh_", "assetbundle_version", "ab_version", "volume", "sound",
            "bgm", "se_", "quality", "resolution", "language", "lang",
            "brightness", "frame", "graphics", "shadow", "vsync", "fullscreen",
            "notify", "save_path", "autosave", "font", "scale", "user_dict",
            "user_skill", "user_item", "user_weapon", "user_mailbox", "user_sale",
            "user_right", "user_group", "user_locale", "userdata",
            "player_session"};

    /** Unity Analytics 的设备级匿名 id：服务器拿它当换身份指纹。 */
    public static final String UNITY_DEVICE_ID = "unity.cloud_userid";

    /** 身份键判定（总是开宽匹配）；同时解开 URL 编码再判一次。 */
    public static boolean isIdentityKey(String key) {
        if (key == null) return false;
        return isLoginPrefKey(key, true) || isLoginPrefKey(decode(key), true);
    }

    /** 登录键判定。wide=true 时启用宽匹配 + 白名单。 */
    public static boolean isLoginPrefKey(String k, boolean wide) {
        if (k == null) return false;
        String s = k.toLowerCase(Locale.US).trim();
        if (s.isEmpty()) return false;
        if (s.startsWith("elneazh_")) return false;          // 我们自己的键永远不动
        if (LOGIN_PREF_KEYS.contains(s)) return true;
        if (!wide) return false;
        for (String keep : KEEP_KEY_PARTS) if (s.contains(keep)) return false;
        if (s.equals(UNITY_DEVICE_ID)) return true;
        for (String hit : WIDE_KEY_PARTS) if (s.contains(hit)) return true;
        return false;
    }

    /** 百分号解码（等价 android.net.Uri.decode，只处理 %XX），避免测试/离线依赖 Android。 */
    public static String decode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 从一个待删键列表里挑出真正要删的（保持顺序、去重），单测与日志都用它。 */
    public static Set<String> filterIdentityKeys(Iterable<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        if (keys == null) return out;
        for (String k : keys) if (isIdentityKey(k)) out.add(k);
        return out;
    }
}
