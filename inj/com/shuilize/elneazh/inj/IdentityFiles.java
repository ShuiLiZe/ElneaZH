package com.shuilize.elneazh.inj;

import java.util.Locale;

/**
 * 换身份 / 只恢复存档 的「文件口径」判定 —— 纯 JDK，不碰 android.*，可以直接单测。
 *
 * <p>为什么单独一类：这条规则是用户真机卡死的根因（{@code getExternalFilesDir(null)/althi_uniq}
 * 里那份 41 字符设备码删不掉），而它以前埋在 {@code Backup.java} 里 —— 那个类 import 了十几个
 * android 包，纯 JDK 单测编译不了，于是规则只能靠肉眼看。抽出来以后 CI 每次都能跑。
 */
public final class IdentityFiles {

    /**
     * 换身份必须删掉的文件名（小写比较，<b>全名命中</b>）。{@code althi_uniq} 是设备码本体，
     * {@code ws_account} 是账号块，其余是登录信息在文件系统里的常见落点。
     */
    static final String[] EXACT = {
            "althi_uniq", "althi_uniq.txt", "althi_uniq.dat", "althi_uniq.json",
            "ws_account", "ws_account.txt", "ws_account.dat", "ws_account.json",
            "login_key", "login_key.txt", "login_key.dat",
            "uuid", "uuid.txt", "uuid.json", "uuid.dat",
            "unity.cloud_userid", "unity.cloud_userid.txt",
    };

    /** 名字里带这些片段的也算（带变体后缀的旧副本：althi_uniq.bak / ws_account_v2）。 */
    static final String[] PARTS = {"althi_uniq", "ws_account"};

    /** 这些名字开头的一律不动：汉化自身的东西。 */
    private static final String[] KEEP_PREFIX = {"elneazh_", "elneazh-"};

    private IdentityFiles() {}

    /**
     * 这个文件名是不是「换身份要删的身份副本」。只看文件名，不看路径 ——
     * 同一份副本可能在 files/、外置 files/、/sdcard 根、Documents 各放一处。
     */
    public static boolean isIdentityCopy(String name) {
        if (name == null) return false;
        if (name.isEmpty()) return false;
        for (String p : KEEP_PREFIX) if (name.startsWith(p)) return false;
        String nm = name.toLowerCase(Locale.US);
        for (String k : EXACT) if (nm.equals(k)) return true;
        for (String k : PARTS) if (nm.contains(k)) return true;
        return false;
    }

    /**
     * 「只恢复存档（保留当前身份）」时，备份里这一条路径是不是身份副本、不许写回。
     * {@code files/althi_uniq}、{@code ext/ws_account.txt} 跳过；
     * 存档 {@code files/zh/sv1_*}、汉化表 {@code files/zh/c_*}、{@code elneazh_*} 放行。
     */
    public static boolean isRestoreIdentityCopy(String path) {
        if (path == null) return false;
        String rel;
        if (path.startsWith("files/")) rel = path.substring(6);
        else if (path.startsWith("ext/")) rel = path.substring(4);
        else return false;
        if (rel.startsWith("zh/") || rel.startsWith("Contents/")) return false;
        int slash = rel.lastIndexOf('/');
        String name = slash >= 0 ? rel.substring(slash + 1) : rel;
        return isIdentityCopy(name);
    }
}
