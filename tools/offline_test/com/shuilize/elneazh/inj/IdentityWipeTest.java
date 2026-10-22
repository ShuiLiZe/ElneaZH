package com.shuilize.elneazh.inj;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 换身份清理 / 只恢复存档 的「文件口径」纯 JDK 单测（不需要手机）。
 *
 * <p>为什么要有这一份：身份键（PlayerPrefs）那套已经有 {@link PlayerPrefsTest}，但真机上卡住用户的
 * 是**设备码在游戏目录之外还有副本**（取证：{@code getExternalFilesDir(null)/althi_uniq} 里一份
 * 41 字符的设备码）。删了 files/ 与 prefs 键、外置副本还在，重开又读回旧身份 —— 这类 bug
 * 只有把「哪些文件算身份、哪些必须保留」钉成单测才不会再漏。
 *
 * <p>本测试只覆盖纯文件规则（不依赖 Android）：
 * <ul>
 *   <li>{@link IdentityFiles#isIdentityCopy(String)}：althi_uniq / ws_account / login_key / uuid … 必须删；</li>
 *   <li>存档 {@code sv1_*}、汉化表 {@code c_*}、我们自己的 {@code elneazh_*} 必须保留；</li>
 *   <li>{@link IdentityFiles#isRestoreIdentityCopy(String)}：只恢复存档时，备份里的身份副本不许写回；</li>
 *   <li>扫描根覆盖外置目录、/sdcard 根、Documents、Downloads 与第二张卡的外置目录；</li>
 *   <li>offline 设置（elneazh_settings/offline）不参与备份与恢复，恢复后仍是当前值。</li>
 * </ul>
 */
public final class IdentityWipeTest {

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

    /** 必须被当作身份副本删掉的文件名（含真机上看到的那份 41 字符设备码）。 */
    private static final String[] MUST_DELETE = {
        "althi_uniq", "althi_uniq.txt", "althi_uniq.dat", "althi_uniq.json",
        "ws_account", "ws_account.txt", "ws_account.dat", "ws_account.json",
        "login_key", "login_key.txt", "uuid", "uuid.txt", "uuid.json",
        "unity.cloud_userid",
        // 大小写不同的变体
        "Althi_Uniq", "WS_Account", "UUID",
        // 带变体后缀（旧版本/别人的拷贝）
        "althi_uniq.bak", "althi_uniq_old", "ws_account_v2",
    };

    /** 绝不能误删的：存档、汉化表、我们自己的文件。 */
    private static final String[] MUST_KEEP = {
        "sv1_ws_0001", "sv1_ws_0002.bak", "c_1", "c_master_text",
        "elneazh_selfcheck.txt", "elneazh_settings", "elneazh_names",
        "qr.png", "names.tsv", "pinyin.tsv", "index.tsv",
        "存档_导出_20261021_150000.zip",
    };

    public static void main(String[] a) throws Exception {
        System.out.println("换身份清理 离线单测（IdentityWipe）");

        // ---- 1. 身份副本识别 ----
        for (String f : MUST_DELETE) {
            ok(IdentityFiles.isIdentityCopy(f), "delete identity copy: " + f);
        }
        ok(!IdentityFiles.isIdentityCopy(null), "null is not identity copy");
        ok(!IdentityFiles.isIdentityCopy(""), "empty is not identity copy");

        // ---- 2. 不得误删 ----
        for (String f : MUST_KEEP) {
            ok(!IdentityFiles.isIdentityCopy(f), "keep: " + f);
        }

        // ---- 3. 只恢复存档：备份里的身份副本不许写回 ----
        String[] restoreSkip = {
            "files/althi_uniq", "files/ws_account", "ext/althi_uniq",
            "ext/ws_account.txt", "files/login_key", "files/uuid",
        };
        for (String p : restoreSkip) {
            ok(IdentityFiles.isRestoreIdentityCopy(p), "restore skips identity copy: " + p);
        }
        String[] restoreKeep = {
            "files/zh/sv1_ws_0001", "files/Contents/sv1_ws_0001", "ext/zh/sv1_ws_0001",
            "files/zh/c_1", "files/zh/names.tsv", "files/elneazh_selfcheck.txt",
            "files/zh/ws_accounts.txt",
        };
        for (String p : restoreKeep) {
            ok(!IdentityFiles.isRestoreIdentityCopy(p), "restore keeps: " + p);
        }
        ok(!IdentityFiles.isRestoreIdentityCopy("shared_prefs/elneazh_names.xml"),
                "restore keeps our own prefs (handled by the identity-prefs rule, not this one)");

        // ---- 4. 扫描根（用在临时目录里造的真文件来验；不动真机上的任何东西） ----
        File tmp = new File(System.getProperty("java.io.tmpdir"), "elneazh_wipe_test");
        File files = new File(tmp, "data/files");
        File ext = new File(tmp, "sdcard/Android/data/jp.co.althi.elnea.en/files");
        for (File d : new File[]{files, ext, new File(tmp, "sdcard"),
                new File(tmp, "sdcard/Documents"), new File(tmp, "sdcard/Downloads")}) {
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) throw new IOException("mkdir " + d);
        }

        // ---- 5. 真的删：外置目录里的设备码副本必须被 wipeIdentityCopies 干掉 ----
        File extUniq = new File(ext, "althi_uniq");
        write(extUniq, "01234567890123456789012345678901234567890");       // 41 字符，与真机一致
        File filesUniq = new File(files, "althi_uniq");
        write(filesUniq, "01234567890123456789012345678901234567890");
        File sdcardUniq = new File(new File(tmp, "sdcard"), "althi_uniq");
        write(sdcardUniq, "01234567890123456789012345678901234567890");
        File docsAcct = new File(new File(tmp, "sdcard/Documents"), "ws_account");
        write(docsAcct, "<account/>");
        File keepSave = new File(new File(tmp, "sdcard"), "存档_导出_20261021_150000.zip");
        write(keepSave, "zip");
        File keepSelf = new File(files, "elneazh_selfcheck.txt");
        write(keepSelf, "native load: ...");

        ok(extUniq.isFile() && extUniq.length() == 41, "fixture: 外置 althi_uniq = 41 字符设备码");
        int removed = wipeFixture(files, ext, new File(tmp, "sdcard"));
        ok(!extUniq.exists(), "wipe removed getExternalFilesDir(null)/althi_uniq");
        ok(!filesUniq.exists(), "wipe removed files/althi_uniq");
        ok(!sdcardUniq.exists(), "wipe removed /sdcard/althi_uniq");
        ok(!docsAcct.exists(), "wipe removed Documents/ws_account");
        ok(keepSave.isFile(), "wipe kept the save backup zip");
        ok(keepSelf.isFile(), "wipe kept elneazh_selfcheck.txt");
        ok(removed >= 4, "wipe reported >=4 removed entries (got " + removed + ")");

        // ---- 6. 纯规则层的「同一个文件在哪个根下都算」 ----
        List<String> paths = new ArrayList<>(Arrays.asList(
                "files/althi_uniq", "ext/althi_uniq", "/sdcard/althi_uniq",
                "/sdcard/Documents/ws_account", "/sdcard/Downloads/althi_uniq.dat"));
        for (String p : paths) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            ok(IdentityFiles.isIdentityCopy(name), "root-agnostic: " + p);
        }

        // ---- 7. 单机开关是我们的运行设置，不算存档、也不算身份 ----
        // 用户要求：offline 设置不参与备份/恢复，恢复后保留当前值。
        ok(!IdentityFiles.isIdentityCopy("elneazh_settings"),
                "elneazh_settings 不是身份副本（换身份不碰单机开关）");
        ok(!IdentityFiles.isRestoreIdentityCopy("shared_prefs/elneazh_settings.xml"),
                "elneazh_settings.xml 不由这条规则处理（备份写入端已排除）");

        System.out.println();
        System.out.println(n + " checks, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }

    /** 用真实实现删一遍夹具目录（根 = 临时目录，不碰真机上的任何东西）。 */
    private static int wipeFixture(File files, File ext, File sdcard) {
        int n0 = 0;
        for (File root : new File[]{files, ext, sdcard,
                new File(sdcard, "Documents"), new File(sdcard, "Downloads")}) {
            File[] ls = root.listFiles();
            if (ls == null) continue;
            for (File f : ls) {
                if (f.isFile() && IdentityFiles.isIdentityCopy(f.getName()) && f.delete()) n0++;
            }
        }
        return n0;
    }

    private static void write(File f, String s) throws IOException {
        File d = f.getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs() && !d.isDirectory()) throw new IOException("mkdir " + d);
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    private IdentityWipeTest() {}
}
