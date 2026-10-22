package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.view.Window;
import android.view.ViewGroup;
import android.view.View;
import android.view.Gravity;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.Color;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 存档备份 / 恢复。备份是一个 zip（files/ 里的存档 + shared_prefs/），放在手机公共目录
 * Documents/艾尔尼亚存档备份/，卸载游戏也不会被清掉；每次一个新文件，可以留很多份。
 *  - 手动：「交流与支持」里的「备份存档」。
 *  - 自动：每次启动（距上次自动备份超过 6 小时）在改名等处理之前备份一次，自动的只保留最近 10 份，手动的永不删除。
 *  - 恢复：选一个备份 zip（系统文件选择器，重装后也能选到旧备份）→ 先自动备份当前存档 → 覆盖 → 退出游戏，重开生效。
 * 不备份大体积可重新下载的数据（UnityCache、主数据表等）。全部 try/catch，失败只提示，不影响游戏。
 */
final class Backup {
    static final String TAG = "ElneaZH";
    static final String DIR = "艾尔尼亚存档备份";
    static final int REQ_RESTORE = 0x4E5A;
    private static final long AUTO_GAP = 6L * 3600 * 1000;
    private static final int AUTO_KEEP = 10;
    private static final long MAX_FILE = 40L * 1024 * 1024;
    private static final Set<String> SKIP_DIRS = new HashSet<>();
    static {
        for (String s : new String[]{"UnityCache", "il2cpp", "zh_backup", "elneazh", "code_cache", "cache", "no_backup"}) SKIP_DIRS.add(s);
    }
    /**
     * 除 files/ shared_prefs/ ext/ 之外，实际存在身份文件的那几处也补进来（换身份靠这份备份回旧号）：
     * shared_prefs 在各版本上位置不同（/data/data/&lt;pkg&gt;/shared_prefs、/data/user_de/&lt;n&gt;/&lt;pkg&gt;/shared_prefs、
     * /data/user/&lt;n&gt;/&lt;pkg&gt;/shared_prefs），getSharedPreferences 返回的就是运行时真正那份，
     * 所以按 SharedPreferences 实例把它的 xml 原样再登记一次；"databases" 里若有 login 相关文件也一并带上。
     * zip 里重复条目是合法的（后读的覆盖先读的），读取方按同名后者为准。
     */
    private static final String PREF_NAME = ".v2.playerprefs";

    /** uuid / login_key 实际存放（以及备份里恢复）的 prefs 文件名。 */
    static String prefsName(Context ctx) { return ctx.getPackageName() + PREF_NAME; }

    /** Called from ZhInit before anything touches the save. */
    static void auto(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("elneazh_backup", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - sp.getLong("last_auto", 0) < AUTO_GAP) return;
        if (!hasSave(ctx)) return;
        String name = make(ctx, "自动");
        if (name != null) {
            sp.edit().putLong("last_auto", now).apply();
            prune(ctx);
        }
    }

    /** 当前这次打包是否「不含身份」（make 内部用；add/addPrefsFile 递归里读）。 */
    private static final ThreadLocal<Boolean> NO_ID = new ThreadLocal<>();
    private static boolean noId() { return Boolean.TRUE.equals(NO_ID.get()); }

    static final String META = "elneazh_meta.txt";
    static final String README = "说明（先看我）.txt";

    /** Writes one backup zip (含身份); returns its file name or null. */
    static String make(Context ctx, String kind) { return make(ctx, kind, true); }

    /**
     * withIdentity=false：不含身份 —— 跳过整份身份文件与外部身份副本，PlayerPrefs 里逐键剔除身份键
     * （与「只恢复存档」同一套 isIdentityKey），别人导入能否正常登录尚在测试。
     */
    static String make(Context ctx, String kind, boolean withIdentity) {
        NO_ID.set(!withIdentity);
        try { return make0(ctx, kind, withIdentity); } finally { NO_ID.remove(); }
    }

    private static String readme(boolean withIdentity) {
        return "艾尔尼亚王国 汉化版（ElneaZH）存档备份\n\n"
            + "这份备份：" + (withIdentity ? "【含身份】" : "【不含身份】") + "\n\n"
            + "■ 含身份（卡维护请选这个）\n"
            + "  存档 + 账号身份（uuid / login_key / 设备码 althi_uniq / 账号块）全部原样打包。\n"
            + "  用途：一直卡「维护中」或卡登录时，把这份发给汉化作者排查；自己换机、重装、想回到原来那个号也用它。\n"
            + "  注意：拿到这份文件的人能登录你的号，只发给汉化作者。\n\n"
            + "■ 不含身份\n"
            + "  去掉了已知的身份文件（uuid / login_key / 设备码 / 账号块），只留存档和设置。\n"
            + "  注意：存档和设备码是绑在一起的，这份发给别人、或换了设备码再导入，联网会卡登录。只适合本机自己留存档。\n\n"
            + "■ 怎么恢复\n"
            + "  游戏内「交流与支持 → 存档管理 → 从文件导入」，选这个 zip。\n"
            + "  含身份的备份可选：完整恢复（含身份）/ 只恢复存档（保留当前身份）。\n"
            + "  不含身份的备份只能「只恢复存档」。\n"
            + "  恢复前会先自动备份当前存档；恢复后游戏会关闭，重新打开即可。\n\n"
            + "■ 不要改动 zip 里的文件，改了可能导不回去。\n";
    }

    private static String make0(Context ctx, String kind, boolean withIdentity) {
        String name = "存档_" + kind + (withIdentity ? "_含身份" : "_无身份") + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip";
        OutputStream out = null;
        Uri dst = null;
        try {
            Object[] opened = new Object[2];
            out = openOut(ctx, name, opened);   // opened[0] = Uri（MediaStore 项）
            dst = (Uri) opened[0];
            if (out == null) return null;
            Set<String> tables = tableNames(ctx);
            File data = ctx.getFilesDir().getParentFile();
            try (ZipOutputStream z = new ZipOutputStream(out)) {
                StringBuilder lst = new StringBuilder();
                // 元信息放第一条：读的时候不用把整个 zip 扫完
                z.putNextEntry(new ZipEntry(META));
                z.write(("with_identity=" + (withIdentity ? 1 : 0) + "\n").getBytes("UTF-8"));
                z.closeEntry();
                z.putNextEntry(new ZipEntry(README));
                z.write(readme(withIdentity).getBytes("UTF-8"));
                z.closeEntry();
                int n = add(z, new File(data, "files"), "files/", tables, 0, lst);
                n += add(z, new File(data, "shared_prefs"), "shared_prefs/", tables, 0, lst);
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) n += add(z, ext, "ext/", tables, 0, lst);
                // 身份文件兜底：真实 prefs 文件不在上面几个目录里时也要进备份
                n += addPrefsFile(ctx, z, lst);
                if (withIdentity) n += addIdentFiles(ctx, z, lst);
                z.putNextEntry(new ZipEntry("elneazh_backup.txt"));
                z.write(("ElneaZH save backup\nfiles=" + n + "\ntime=" + new Date() + "\ndata=" + data + "\next=" + ext
                        + "\nwith_identity=" + (withIdentity ? 1 : 0)
                        + "\nidentity=" + (withIdentity ? identSummary(ctx) : "无（不含身份）") + "\n\n" + lst).getBytes("UTF-8"));
                z.closeEntry();
                z.finish();
            }
            // 关键：ZipOutputStream.close() 只 finish、不关底层的 MediaStore 流；不显式 close 的话
            // resolver 没机会把 SIZE 写回、IS_PENDING 也不会清，列表里就是 1KB（重进菜单时才补上）。
            try { out.flush(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
            out = null;
            settle(ctx, dst);
            Log.i(TAG, "backup " + name + " (" + (dst != null ? size(ctx, dst) : -1) + " B)");
            return name;
        } catch (Throwable t) {
            Log.e(TAG, "backup failed", t);
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private static int add(ZipOutputStream z, File f, String path, Set<String> tables, int depth, StringBuilder lst) throws Exception {
        if (!f.exists() || depth > 8) return 0;
        if (f.isDirectory()) {
            if (depth == 1 && SKIP_DIRS.contains(f.getName())) return 0;
            File[] ls = f.listFiles();
            if (ls == null) return 0;
            int n = 0;
            for (File c : ls) n += add(z, c, path + (c.isDirectory() ? c.getName() + "/" : c.getName()), tables, depth + 1, lst);
            return n;
        }
        String nm = f.getName();
        if (nm.endsWith(".tmp") || f.length() > MAX_FILE) return 0;
        // 不是存档：调试大表、native 库、文件选择器缓存（里面常是上一份备份 zip，会套娃）
        if (nm.equals("elneazh_dump.tsv") || nm.endsWith(".so") || nm.endsWith(".so.stamp")
                || nm.startsWith("elneazh_pending_restore")) return 0;
        if (path.startsWith("files/lib/") || path.startsWith("files/image_provider/")) return 0;
        // 只跳第 1 层会漏掉 ext/UnityCache 这类深一层的大目录 → 任意层都按名字跳
        if (SKIP_DIRS.contains(path.substring(path.indexOf('/') + 1).split("/")[0])) return 0;
        if (SKIP_DIRS.contains(nm)) return 0;
        if (tables.contains(nm) && (path.endsWith("/zh/" + nm) || path.endsWith("/Contents/" + nm))) return 0;
        if (path.startsWith("shared_prefs/elneazh_backup")) return 0;
        // 单机开关（elneazh_settings/offline）是我们自己的运行设置，不属于存档：
        // 备份里带上它，恢复别人的存档就会把「单机开/关」一起换掉，用户会莫名其妙断网/联网。
        // 换身份同样不动它（wipe 跳过 elneazh_* 前缀），恢复时保留当前值。
        if (path.startsWith("shared_prefs/elneazh_settings")) return 0;
        if (noId()) {
            if (isIdentityCopy(nm)) return 0;
            if (path.startsWith("shared_prefs/")) {
                if (isIdentityPrefFile(nm)) return 0;
                String x = readText(f);
                if (x != null) {
                    byte[] b = stripXml(x).getBytes("UTF-8");
                    lst.append(path).append('\t').append(b.length).append('\n');
                    z.putNextEntry(new ZipEntry(path));
                    z.write(b);
                    z.closeEntry();
                    return 1;
                }
            }
        }
        lst.append(path).append('\t').append(f.length()).append('\n');
        z.putNextEntry(new ZipEntry(path));
        try (InputStream in = new FileInputStream(f)) { pipe(in, z); }
        z.closeEntry();
        return 1;
    }

    /**
     * 把 SharedPreferences 实例对应的 xml 文件原样写进 zip（shared_prefs/&lt;pkg&gt;.v2.playerprefs.xml）。
     * 用 getSharedPreferences().getAll() 确认文件里真的有 uuid/login_key；文件路径从
     * ContextImpl 之外的两条已知路径找：files/../shared_prefs 与 /data/user_de/&lt;n&gt;/&lt;pkg&gt;/shared_prefs。
     * 找不到文件时退回手工写一个和 SharedPreferences 同格式的 xml（同样能被 restore 恢复）。
     */
    private static int addPrefsFile(Context ctx, ZipOutputStream z, StringBuilder lst) {
        try {
            String pn = prefsName(ctx);
            SharedPreferences sp = ctx.getSharedPreferences(pn, Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            String entry = "shared_prefs/" + pn + ".xml";
            File f = findPrefsFile(ctx, pn + ".xml");
            if (f != null && f.isFile()) {
                // 已在 add(shared_prefs/) 里就跳过（避免重复）；否则补一份
                if (lst.indexOf(entry + "\t") >= 0) { Log.i(TAG, "prefs already in backup: " + entry); return 0; }
                z.putNextEntry(new ZipEntry(entry));
                String sx = noId() ? readText(f) : null;
                if (sx != null) z.write(stripXml(sx).getBytes("UTF-8"));
                else try (InputStream in = new FileInputStream(f)) { pipe(in, z); }
                z.closeEntry();
                lst.append(entry).append('\t').append(f.length()).append('\n');
                Log.i(TAG, "prefs added from " + f + " keys=" + all.keySet() + " noId=" + noId());
                return 1;
            }
            // 文件不在常规位置：按 SharedPreferences 的 xml 结构手写一份
            StringBuilder x = new StringBuilder("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n");
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (noId() && isIdentityKey(e.getKey())) continue;
                String t = v instanceof Boolean ? "boolean" : v instanceof Integer ? "int"
                        : v instanceof Long ? "long" : v instanceof Float ? "float" : "string";
                x.append("    <").append(t).append(" name=\"").append(esc(e.getKey())).append("\">")
                        .append(esc(String.valueOf(v))).append("</").append(t).append(">\n");
            }
            x.append("</map>\n");
            byte[] b = x.toString().getBytes("UTF-8");
            z.putNextEntry(new ZipEntry(entry));
            z.write(b);
            z.closeEntry();
            lst.append(entry).append('\t').append(b.length).append('\n');
            Log.i(TAG, "prefs written from memory: " + entry + " keys=" + all.keySet());
            return 1;
        } catch (Throwable t) {
            Log.w(TAG, "prefs backup", t);
            return 0;
        }
    }

    /** <pkg>.v2.playerprefs.xml 的实际文件（找不到返回 null）。 */
    static File findPrefsFile(Context ctx, String xmlName) {
        File data = ctx.getFilesDir().getParentFile();       // /data/data/<pkg>
        List<File> cand = new ArrayList<>();
        if (data != null) cand.add(new File(new File(data, "shared_prefs"), xmlName));
        try {
            int uid = ctx.getApplicationInfo().uid;
            String u = "user" + (uid / 100000);
            cand.add(new File("/data/" + u + "/" + ctx.getPackageName() + "/shared_prefs/" + xmlName));
            cand.add(new File("/data/user_de/" + (uid % 100000) + "/" + ctx.getPackageName() + "/shared_prefs/" + xmlName));
        } catch (Throwable ignored) {}
        for (File f : cand) { try { if (f.isFile() && f.canRead()) return f; } catch (Throwable ignored) {} }
        return null;
    }

    /** 除 prefs 外的登录相关文件（某些版本把 token 放 databases/ 或 files/ 根下）。 */
    private static int addIdentFiles(Context ctx, ZipOutputStream z, StringBuilder lst) {
        int n = 0;
        try {
            File data = ctx.getFilesDir().getParentFile();
            if (data == null) return 0;
            String[] names = {"login", "login_key", "token", "user", "account", "uuid", "session"};
            for (String dir : new String[]{"databases", "no_backup", "files"}) {
                File d = new File(data, dir);
                File[] ls = d.listFiles();
                if (ls == null) continue;
                for (File f : ls) {
                    if (!f.isFile() || f.length() > MAX_FILE) continue;
                    String ln = f.getName().toLowerCase(Locale.US);
                    boolean hit = false;
                    for (String k : names) if (ln.contains(k)) { hit = true; break; }
                    if (!hit) continue;
                    String entry = dir + "/" + f.getName();
                    if (lst.indexOf(entry + "\t") >= 0) continue;
                    z.putNextEntry(new ZipEntry(entry));
                    try (InputStream in = new FileInputStream(f)) { pipe(in, z); }
                    z.closeEntry();
                    lst.append(entry).append('\t').append(f.length()).append('\n');
                    n++;
                }
            }
        } catch (Throwable t) { Log.w(TAG, "ident files", t); }
        return n;
    }

    /** 这个备份 / 当前安装用的身份（写进 zip 说明与日志，恢复时对得上）。 */
    static String identSummary(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(prefsName(ctx), Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            Object id = all.get("uuid");
            boolean lk = all.containsKey("login_key");
            if (id == null) return "uuid=- login_key=" + lk + " keys=" + all.size();
            return "uuid=" + mask(String.valueOf(id)) + " login_key=" + lk + " keys=" + all.size();
        } catch (Throwable t) { return "unknown"; }
    }

    private static String mask(String s) {
        if (s == null) return "-";
        return s.length() <= 8 ? s : s.substring(0, 4) + "…" + s.substring(s.length() - 4);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Set<String> tableNames(Context ctx) {        Set<String> s = new HashSet<>();
        try {
            String[] t = ctx.getAssets().list("elneazh/Contents");
            if (t != null) for (String n : t) if (!n.startsWith("sv1_")) s.add(n);
        } catch (Throwable ignored) {}
        return s;
    }

    private static boolean hasSave(Context ctx) {
        for (File root : new File[]{ctx.getFilesDir(), ctx.getExternalFilesDir(null)}) {
            if (root == null) continue;
            for (String d : new String[]{"zh", "Contents"}) {
                File[] ls = new File(root, d).listFiles();
                if (ls != null) for (File f : ls) if (f.getName().startsWith("sv1_")) return true;
            }
        }
        return false;
    }

    /** /sdcard/艾尔尼亚存档备份 — used when the app has all-files access (or legacy storage permission). */
    static File rootDir() { return new File(Environment.getExternalStorageDirectory(), DIR); }

    static boolean rootOk(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
            if (Build.VERSION.SDK_INT == 29) return false; // scoped storage, no legacy flag
            return ctx.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    /** Opens the system page to grant all-files access (Android 11+) or asks the legacy permission. */
    static void askRoot(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName()));
                try { a.startActivity(i); }
                catch (Throwable t) { a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                toast(a, "请打开「允许管理所有文件」，返回后再点存档管理");
            } else if (Build.VERSION.SDK_INT == 29) {
                toast(a, "安卓 10 不支持放根目录，备份仍在 Documents/" + DIR);
            } else if (Build.VERSION.SDK_INT >= 23) {
                a.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0x4E5B);
            }
        } catch (Throwable t) { Log.w(TAG, "askRoot", t); toast(a, "打不开授权页面"); }
    }

    private static OutputStream openOut(Context ctx, String name) throws Exception {
        return openOut(ctx, name, new Object[2]);
    }

    /** opened[0] 回填 MediaStore 的 Uri（没有则为 null），供 settle()/size() 用。 */
    private static OutputStream openOut(Context ctx, String name, Object[] opened) throws Exception {
        if (rootOk(ctx)) {
            File d = rootDir();
            if (d.isDirectory() || d.mkdirs()) return new FileOutputStream(new File(d, name));
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + DIR);
            Uri u = ctx.getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
            opened[0] = u;
            return u == null ? null : ctx.getContentResolver().openOutputStream(u);
        }
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR);
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return new FileOutputStream(new File(d, name));
    }

    /** 写完后把 MediaStore 项的 IS_PENDING 清掉并让 resolver notifyChange，列表立刻能读到正确 SIZE。 */
    private static void settle(Uri u) {
        settle(CUR, u);
    }

    /** 进程内最近一次的 Application Context（ZhInit.onCreate 里 setCtx 过）。 */
    private static Context CUR;

    static void setCtx(Context c) { if (c != null) CUR = c.getApplicationContext(); }

    private static void settle(Context ctx, Uri u) {
        if (u == null || Build.VERSION.SDK_INT < 29) return;
        Context c = ctx != null ? ctx : CUR;
        if (c == null) return;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            c.getContentResolver().update(u, v, null, null);
        } catch (Throwable ignored) {}
    }

    /** 单个 uri 的真实大小（-1 = 查不到）。 */
    private static long size(Context ctx, Uri u) {
        if (u == null) return -1;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(u, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally { if (c != null) try { c.close(); } catch (Throwable ignored) {} }
        return -1;
    }

    /** Keeps only the newest AUTO_KEEP automatic backups this install created; manual ones are never touched. */
    private static void prune(Context ctx) {
        try {
            if (rootOk(ctx)) {
                File[] ls = rootDir().listFiles();
                List<String> autos = new ArrayList<>();
                if (ls != null) for (File f : ls) if (f.getName().startsWith("存档_自动_")) autos.add(f.getName());
                java.util.Collections.sort(autos, java.util.Collections.reverseOrder());
                for (int i = AUTO_KEEP; i < autos.size(); i++) new File(rootDir(), autos.get(i)).delete();
            }
        } catch (Throwable t) { Log.w(TAG, "prune root", t); }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = ctx.getContentResolver();
                Uri col = MediaStore.Files.getContentUri("external");
                List<Long> ids = new ArrayList<>();
                try (Cursor c = cr.query(col, new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                        new String[]{Environment.DIRECTORY_DOCUMENTS + "/" + DIR + "%", "存档_自动_%"},
                        MediaStore.MediaColumns.DISPLAY_NAME + " DESC")) {
                    if (c != null) while (c.moveToNext()) ids.add(c.getLong(0));
                }
                for (int i = AUTO_KEEP; i < ids.size(); i++) cr.delete(ContentUris.withAppendedId(col, ids.get(i)), null, null);
            } else {
                File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR);
                File[] ls = d.listFiles();
                if (ls == null) return;
                List<String> autos = new ArrayList<>();
                for (File f : ls) if (f.getName().startsWith("存档_自动_")) autos.add(f.getName());
                java.util.Collections.sort(autos, java.util.Collections.reverseOrder());
                for (int i = AUTO_KEEP; i < autos.size(); i++) new File(d, autos.get(i)).delete();
            }
        } catch (Throwable t) { Log.w(TAG, "prune", t); }
    }

    // ---------------- 换身份用：按备份登记的文件清单清空本地数据 ----------------

    /** 这个 zip 里登记过的所有条目（elneazh_backup.txt 的清单行）。读不到返回空表。 */
    private static Set<String> indexOf(Context ctx, Uri u) {
        Set<String> s = new LinkedHashSet<>();
        try (ZipInputStream z = new ZipInputStream(ctx.getContentResolver().openInputStream(u))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!"elneazh_backup.txt".equals(e.getName())) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = z.read(b)) > 0) bo.write(b, 0, n);
                for (String line : bo.toString("UTF-8").split("\n")) {
                    int t = line.indexOf('\t');
                    if (t > 0) s.add(line.substring(0, t));
                }
                break;
            }
        } catch (Throwable t) { Log.w(TAG, "index", t); }
        return s;
    }

    /** 备份登记过、但现在不在了的文件（换身份前自检用）。 */
    static int missingFrom(Context ctx, Uri u) {
        int miss = 0;
        File data = ctx.getFilesDir().getParentFile();
        File ext = ctx.getExternalFilesDir(null);
        for (String p : indexOf(ctx, u)) {
            File f = null;
            if (p.startsWith("files/")) f = new File(data, p);
            else if (p.startsWith("shared_prefs/")) f = new File(data, p);
            else if (p.startsWith("ext/") && ext != null) f = new File(ext, p.substring(4));
            else if (p.startsWith("databases/") || p.startsWith("no_backup/")) f = new File(data, p);
            if (f != null && !f.isFile()) miss++;
        }
        return miss;
    }

    /** 备份里是否带着 uuid 或 login_key（换身份前必须为真，否则恢复不回来）。 */
    static boolean hasIdentity(Context ctx, Uri u) {
        try (ZipInputStream z = new ZipInputStream(ctx.getContentResolver().openInputStream(u))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String p = e.getName();
                if (!p.startsWith("shared_prefs/") || !p.endsWith(".xml")) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = z.read(b)) > 0) bo.write(b, 0, n);
                String x = bo.toString("UTF-8");
                if (x.contains("name=\"uuid\"") || x.contains("name=\"login_key\"")) return true;
            }
        } catch (Throwable t) { Log.w(TAG, "hasIdentity", t); }
        return false;
    }

    /**
     * 换身份的第一步：备份当前存档（{@code make} 成功返回非 null），并校验这份 zip 里确实有身份文件。
     * 返回 zip 的 uri；失败（或没有身份文件）返回 null —— 调用方据此中止，不动任何数据。
     */
    static Uri backupForIdentity(Context ctx) {
        String name = make(ctx, "换身份前");
        if (name == null) { Log.e(TAG, "identity: backup failed, abort"); return null; }
        Uri u = findByName(ctx, name);
        if (u == null) { Log.e(TAG, "identity: backup not found after write, abort"); return null; }
        boolean id = hasIdentity(ctx, u);
        Log.i(TAG, "identity: backup ok " + name + " identity=" + id + " ident=" + identSummary(ctx));
        return id ? u : null;
    }

    /** 按名字找到刚写的那个备份。 */
    static Uri findByName(Context ctx, String name) {
        for (Item it : list(ctx)) if (name.equals(it.name)) return it.uri;
        return null;
    }

    /**
     * 刚 make() 出来的那份备份的 uri：先按名字查列表，查不到再直接拼「根目录/文件名」。
     * 导出/分享用，绝不抛异常。
     */
    static Uri uriOf(Context ctx, String name) {
        if (ctx == null || name == null) return null;
        try {
            Uri u = findByName(ctx, name);
            if (u != null) return u;
        } catch (Throwable ignored) {
        }
        try {
            File f = new File(rootDir(), name);
            if (f.isFile()) return Uri.fromFile(f);
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------- 换身份：最小删除集合 ----------------
    //
    // 依据（全部来自 base.apk 静态取证，见 .work/p_keys2.txt / p_cache.txt）：
    //   登录键名的字面量，global-metadata 索引 —— 'althi_uniq'(10282) 'login_key'(12937) 'uuid'(15217)
    //   'token'(14859) 'user_id'(15196) user_pass/user_uniq(15200/15205) 'already login!'(10275)
    //   'login.php'(12935) 'create.php'(11131) — 归在同一个登录流程 (LoginRequest / AccountInputPanel)。
    //   libil2cpp.so 与 dex 里都搜不到 althi_uniq/login_key → 它们是 C# 字面量，只能在 metadata 里取到。
    //
    // 必须保留（删了要重新下载数据包 / 丢汉化）：
    //   UnityCache/Shared/<bundle>/<hash>/{__data,__info} —— libunity.so 原生 AssetBundle 缓存
    //        （字面量 'UnityCache'(libunity +0x51xxx) '__data' '__info'），与登录无关。
    //   files/zh、files/Contents 里的汉化主表（c_* 22 张，tableNames 给名单）。
    //   shared_prefs/elneazh_*（含 elneazh_names、elneazh_backup 的 last_auto）。
    //   我们会话自己生成的东西（elneazh_selfcheck.txt、zh_backup/、il2cpp/）。
    //   PlayerPrefs 里的资源版本键：'assetbundle_version'(10436) 等 —— 只删登录键，不当整体清空。
    //
    // 存档 sv1_* 全删：它们存的是旧身份的存档（含旧 PlayerPrefs 之外的角色/家族/世界状态）。
    static final Set<String> LOGIN_PREF_KEYS = IdentityKeys.LOGIN_PREF_KEYS;

    /** 这些 prefs 文件本身就是登录凭证（althi_uniq 有时单独存一个 xml）。 */
    private static final Set<String> LOGIN_PREF_FILES = new HashSet<>();
    static {
        for (String n : new String[]{"althi_uniq", "login_key", "uuid", "token"}) LOGIN_PREF_FILES.add(n);
    }

    static boolean isLoginPrefFile(String fileName) {
        String b = fileName.toLowerCase(Locale.US);
        if (b.endsWith(".xml")) b = b.substring(0, b.length() - 4);
        return LOGIN_PREF_FILES.contains(b);
    }

    /**
     * 身份键的唯一判定入口 —— 换身份删除与「只恢复存档」跳过必须用同一套，
     * 否则一处删了、一处又写回来，等于没换。实现在 {@link IdentityKeys}（纯逻辑，可离线单测）。
     */
    static boolean isIdentityKey(String key) {
        return IdentityKeys.isIdentityKey(key);
    }

    /**
     * 某个 shared_prefs xml「整文件是不是身份文件」。是则「只恢复存档」整份跳过；
     * 否则逐键按 {@link #isIdentityKey} 剔除。
     */
    static boolean isIdentityPrefFile(String fileName) {
        return isLoginPrefFile(fileName);
    }

    private static boolean isLoginPrefKey(String k) {
        return IdentityKeys.isLoginPrefKey(k, false);
    }

    /** 见 {@link IdentityKeys#isLoginPrefKey}。 */
    static boolean isLoginPrefKey(String k, boolean wide) {
        return IdentityKeys.isLoginPrefKey(k, wide);
    }

    /** 宽匹配时命中即保留的键名片段 —— 资源版本、画面/音量设置、我们自己的表。
     *  注意：app 自己的「客户端版本/渠道」也带 version/chnl，但那是游戏写的，删了只会重算，
     *  所以只按整个键名保留 Unity 的 assetbundle_version，不用 'version' 这种大词兜。 */
/**
     * 换身份：只删「明确属于登录凭证 / 旧存档」的东西，数据包与汉化表一概不碰。
     *  - files/ 与 ext files 下的 sv1_*（存档）、*.ntmp/*.rtmp（写了一半的临时文件）
     *  - PlayerPrefs 里的登录键（uuid / login_key / althi_uniq / token / user_* …）—— 逐键 remove，
     *    资源下载 / 版本键（assetbundle_version 等）原样保留
     *  - althi_uniq 系列独立 prefs xml（files/althi_uniq、shared_prefs/althi_uniq.xml）
     *  - databases/ 里的账号库（名字含 login/account/token/user 的 .db*）
     *  - files/althi_uniq 这类无扩展名的凭证文件
     * UnityCache、汉化表、我们自己的 elneazh_* 全部保留。
     */
    static void wipeForIdentity(Context ctx, Uri backup) { wipeIdentity(ctx, false); }

    /** keepSaves=true：只更换身份（测试功能）—— 删身份/登录相关，存档 sv1_* 与其兄弟文件不动。 */
    static void wipeIdentity(Context ctx, boolean keepSaves) {
        File data = ctx.getFilesDir().getParentFile();
        File ext = ctx.getExternalFilesDir(null);
        int del = 0;
        if (!keepSaves) {

        // 1) 存档 sv1_*（files/zh、files/Contents、ext/zh、ext/Contents 以及这两个根的其它子目录）
        for (File root : new File[]{ctx.getFilesDir(), ctx.getExternalFilesDir(null)}) {
            if (root == null) continue;
            del += deleteSaveFiles(root, 0);
        }

        // 1b) 存档的兄弟文件：version 文本、orgFile 备份、写了一半的 tmp、旧存档目录
        //     （取证依据：global-metadata 里 SaveData 的 WriteVersionText / ReadVersionText /
        //      orgFileKey / isBrokenSave / tmpSaveFolder / saveFolder / contentsFolder 字段）
        for (File root : new File[]{ctx.getFilesDir(), ext}) {
            if (root == null) continue;
            for (String sub : new String[]{"zh", "Contents", "save", "saves"}) {
                File d = new File(root, sub);
                if (!d.isDirectory()) continue;
                File[] ls = d.listFiles();
                if (ls == null) continue;
                for (File f : ls) {
                    String nm = f.getName();
                    if (f.isFile() && (nm.endsWith(".txt") || nm.endsWith(".tmp")
                            || nm.endsWith(".bak") || nm.endsWith(".org"))) {
                        try { if (f.delete()) del++; } catch (Throwable ignored) {}
                    }
                }
            }
        }

        }   // !keepSaves

        // 1c) 登录目录：SaveData.GetLoginDirectory() 建的那一层（取证：'_loginDirectory'
        //     'GetLoginDirectory' 'saveFolder' 'contentsFolder'）—— 服务器认旧身份时读的就是它，
        //     只要它还在，删掉若干 PlayerPrefs 键也照样「already login!」。整层删掉，重开自动重建。
        for (File root : new File[]{ctx.getFilesDir(), ext}) {
            if (root == null || !root.isDirectory()) continue;
            File[] ls = root.listFiles();
            if (ls == null) continue;
            for (File d : ls) {
                if (!d.isDirectory()) continue;
                String nm = d.getName().toLowerCase(Locale.US);
                if (nm.contains("login") || nm.contains("account") || nm.equals("uuid")) {
                    del += deleteTree(d);
                    Log.i(TAG, "identity: removed login dir " + d);
                }
            }
        }

        // 2) files/ 与 ext files 根下的登录凭证文件（althi_uniq 之类，没有扩展名）
        for (File root : new File[]{ctx.getFilesDir(), ext, new File(data, "no_backup")}) {
            if (root == null || !root.isDirectory()) continue;
            File[] ls = root.listFiles();
            if (ls == null) continue;
            for (File f : ls) {
                if (!f.isFile()) continue;
                String nm = f.getName().toLowerCase(Locale.US);
                if (isLoginPrefFile(nm) || nm.startsWith("althi_uniq") || nm.contains("login")
                        || nm.contains("uuid") || nm.contains("token") || nm.contains("account")) {
                    try { if (f.delete()) { del++; Log.i(TAG, "identity: removed " + f); } } catch (Throwable ignored) {}
                }
            }
        }

        // 3) 独立 prefs xml：shared_prefs/althi_uniq.xml 之类
        File sp = new File(data, "shared_prefs");
        File[] sps = sp.listFiles();
        if (sps != null) for (File f : sps) {
            if (!f.isFile()) continue;
            String nm = f.getName().toLowerCase(Locale.US);
            if (nm.startsWith("elneazh_") || nm.startsWith("com.shuilize")) continue;   // 我们自己的
            if (isLoginPrefFile(nm)) {
                try { if (f.delete()) { del++; Log.i(TAG, "identity: removed " + f); } } catch (Throwable ignored) {}
            }
        }

        // 4) 所有 prefs 文件：宽匹配逐键 remove（登录键），资源版本/设置键原样保留
        del += clearAllPrefs(ctx, data);

        // 5) databases/ 里的账号库
        File db = new File(data, "databases");
        File[] dbs = db.listFiles();
        if (dbs != null) for (File f : dbs) {
            if (!f.isFile()) continue;
            String nm = f.getName().toLowerCase(Locale.US);
            boolean hit = false;
            for (String k : new String[]{"login", "account", "token", "user", "session", "althi", "uuid"})
                if (nm.contains(k)) { hit = true; break; }
            if (!hit) continue;
            try { if (f.delete()) { del++; Log.i(TAG, "identity: removed " + f); } } catch (Throwable ignored) {}
        }

        // 6) Unity 的「上次存档摘要」类文件：不清的话重开仍会拿旧摘要去问服务器
        for (File f : new File[]{new File(data, "shared_prefs"), ctx.getFilesDir()}) {
            File[] ls = f.listFiles();
            if (ls == null) continue;
            for (File x : ls) {
                String nm = x.getName().toLowerCase(Locale.US);
                if (x.isFile() && (nm.contains("lastsave") || nm.contains("last_save")
                        || nm.contains("savetime") || nm.contains("saveinfo"))) {
                    try { if (x.delete()) del++; } catch (Throwable ignored) {}
                }
            }
        }

        if (keepSaves) {
            // 下次启动时跑（主线程、WebView 未初始化）：直接删 WebView 的 cookie 目录，不走 CookieManager
            if (data != null) del += deleteTree(new File(data, "app_webview"));
        } else {
            del += wipeWebStorage(ctx);
        }

        // 7) 设备码 / 账号块在游戏私有目录**之外**的副本 —— 真机「换了身份还是旧账号」的头号原因。
        //    取证：getExternalFilesDir(null)/althi_uniq 里有一份 41 字符的设备码（/sdcard/Android/data/
        //    <pkg>/files/althi_uniq），删了 files/ 与 prefs 里的键它还在，重开又被读回来；
        //    /sdcard 根、Documents、游戏目录的其它子目录也可能有同名副本。
        del += wipeIdentityCopies(ctx);

        Log.i(TAG, "identity wipe (wide): deleted " + del + "; UnityCache/tables/resource prefs kept");
        Log.i(TAG, "identity wipe summary: prefs keys + save files + login dirs/files + databases"
                + " + web storage + 外部设备码/账号副本");
    }

    // ---------------- 换身份 v2：下次启动时按「清除数据」口径清 ----------------
    // 真机（2026-10-22）：点「更换身份」后仍进不去，系统「清除数据」后能进。原因两条：
    //  1) 旧流程在游戏进程里删完文件，再 finishAffinity —— Unity onPause/onDestroy 会把内存里的
    //     PlayerPrefs（含旧身份）和存档重新写回磁盘；
    //  2) 只删「认得出的」身份键/文件，漏网的就留下了。
    // 现在：当前进程只做备份 + 立旗，然后立刻杀进程（不走 finish）；下次启动在 ZhInit 最前面、
    // Unity 起来之前，把私有目录和外置目录按「清除数据」清空，只留汉化自己的东西与资源缓存。
    static final String PENDING_WIPE = "pending_identity_wipe";

    /** full=true：按清除数据清；false：只更换身份（保留存档）。 */
    static void scheduleIdentityWipe(Context ctx, boolean full) {
        ctx.getSharedPreferences("elneazh_settings", Context.MODE_PRIVATE).edit()
                .putString(PENDING_WIPE, full ? "full" : "id").commit();
    }

    /** ZhInit 最前面调用。返回是否执行了清理。 */
    static boolean runPendingIdentityWipe(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("elneazh_settings", Context.MODE_PRIVATE);
        String mode = null;
        try { mode = sp.getString(PENDING_WIPE, null); } catch (Throwable t) { mode = "full"; }  // 旧版存的是 boolean
        if (mode == null) return false;
        if ("id".equals(mode)) {
            sp.edit().remove(PENDING_WIPE).commit();
            try { wipeIdentity(ctx, true); } catch (Throwable t) { Log.w(TAG, "identity-only wipe", t); }
            Log.i(TAG, "identity wipe (identity only, saves kept) done");
            return true;
        }
        int n = 0;
        try {
            File data = ctx.getFilesDir().getParentFile();
            n += clearLikeSystem(data, 0);
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null && ext.getParentFile() != null) n += clearLikeSystem(ext.getParentFile(), 0); // Android/data/<pkg>
            try { n += wipeIdentityCopies(ctx); } catch (Throwable ignored) {}
            File de = null;
            try {
                int uid = ctx.getApplicationInfo().uid;
                de = new File("/data/user_de/" + (uid / 100000) + "/" + ctx.getPackageName());
            } catch (Throwable ignored) {}
            if (de != null && de.isDirectory()) n += clearLikeSystem(de, 0);
        } catch (Throwable t) { Log.w(TAG, "pending wipe", t); }
        sp.edit().remove(PENDING_WIPE).commit();
        Log.i(TAG, "identity wipe v2 (like clear data): deleted " + n + " file(s)");
        return true;
    }

    /** 留下的：native 库、汉化自己的文件/设置、资源缓存（不含身份，删了要重下几百 MB）。 */
    private static boolean keepOnWipe(File f, int depth) {
        String nm = f.getName();
        String ln = nm.toLowerCase(Locale.US);
        if (ln.startsWith("elneazh")) return true;                 // elneazh_settings.xml / elneazh_* 文件
        if (ln.startsWith("com.shuilize")) return true;
        if (depth == 0 && (nm.equals("lib") || nm.equals("code_cache"))) return true;
        if (nm.equals("UnityCache")) return true;
        if (nm.equals("lib") && f.getParentFile() != null && "files".equals(f.getParentFile().getName())) return true; // files/lib/libelneagate.so
        return false;
    }

    private static int clearLikeSystem(File dir, int depth) {
        int n = 0;
        File[] ls = dir.listFiles();
        if (ls == null) return 0;
        for (File c : ls) {
            if (keepOnWipe(c, depth)) continue;
            if (c.isDirectory()) {
                n += clearLikeSystem(c, depth + 1);
                c.delete();     // 空了才删得掉；里面有要留的东西就留着
            } else if (c.delete()) n++;
        }
        return n;
    }

    /** 递归删一整棵目录（只用于登录目录这种明确目标）。 */
    /**
     * 换身份：清 WebView 的 cookie 与网页缓存。
     * 依据：商店 / 恢复码之类页面走 WebView，CookieManager 把 uuid / session 存在
     * /data/data/<pkg>/app_webview/ 里；只删 prefs 与存档、不动这份 cookie 时，
     * 服务器仍能从 cookie 认出旧身份。CookieManager 必须在有 Looper 的线程调用，调用方在主线程。
     */
    static int wipeWebStorage(Context ctx) {
        int n = 0;
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            if (cm != null) {
                cm.setAcceptCookie(true);
                try { cm.removeAllCookies(null); n++; } catch (Throwable t) { Log.w(TAG, "cookie removeAllCookies", t); }
                try { cm.flush(); } catch (Throwable ignored) {}
                if (cm.hasCookies()) {
                    // 有机型 flush 晚一拍，再清一次
                    try { cm.removeAllCookies(null); n++; } catch (Throwable ignored) {}
                }
                Log.i(TAG, "identity: cookie manager cleared, hasCookies=" + cm.hasCookies());
            }
        } catch (Throwable t) {
            Log.w(TAG, "identity: cookie clear failed", t);
        }
        File data = ctx.getFilesDir().getParentFile();
        for (String nm : new String[]{"app_webview", "app_textures", "app_tmpdir",
                "cache/WebView", "cache/org.chromium.android_webview"}) {
            File f = new File(data, nm);
            if (f.exists()) {
                n += deleteTree(f);
                Log.i(TAG, "identity: removed web storage " + nm);
            }
        }
        File localStorage = new File(new File(data, "app_webview"), "Local Storage");
        if (localStorage.exists()) {
            n += deleteTree(localStorage);
            Log.i(TAG, "identity: removed Local Storage");
        }
        Log.i(TAG, "identity: web storage cleared (" + n + " entries)");
        return n;
    }

    // ---------------- 换身份：游戏目录之外的身份副本 ----------------

    /**
     * 扫描根：游戏自己的私有目录、外置目录、以及容易被人手动拷一份的地方。
     * 全是<b>该扫描的浅层</b>，不递归 /sdcard 全盘（那要几分钟）。
     */
    static java.util.List<File> identityCopyRoots(Context ctx) {
        java.util.List<File> out = new java.util.ArrayList<>();
        try { out.add(ctx.getFilesDir()); } catch (Throwable ignored) {}
        try { out.add(ctx.getExternalFilesDir(null)); } catch (Throwable ignored) {}
        try {
            File data = ctx.getFilesDir() == null ? null : ctx.getFilesDir().getParentFile();
            if (data != null) out.add(new File(data, "no_backup"));
        } catch (Throwable ignored) {}
        File sdcard = null;
        try { sdcard = Environment.getExternalStorageDirectory(); } catch (Throwable ignored) {}
        if (sdcard != null) {
            out.add(sdcard);
            out.add(new File(sdcard, Environment.DIRECTORY_DOCUMENTS));
            out.add(new File(sdcard, Environment.DIRECTORY_DOWNLOADS));
        }
        try {
            File[] exts = ctx.getExternalFilesDirs(null);
            if (exts != null) for (File f : exts) if (f != null) out.add(f);
        } catch (Throwable ignored) {}
        java.util.List<File> clean = new java.util.ArrayList<>();
        for (File f : out) if (f != null && !clean.contains(f)) clean.add(f);
        return clean;
    }

    /**
     * 「只恢复存档」时，备份里的这一条是不是身份副本（按备份里的相对路径判）。
     * 口径在 {@link IdentityFiles}（纯 JDK，有单测）。
     */
    static boolean isRestoreIdentityCopy(String path) {
        return IdentityFiles.isRestoreIdentityCopy(path);
    }

    /** 这个名字是不是「换身份要删的身份副本」。口径在 {@link IdentityFiles}（纯 JDK，有单测）。 */
    static boolean isIdentityCopy(String name) {
        return IdentityFiles.isIdentityCopy(name);
    }

    /**
     * 删游戏目录之外的身份副本（外置 files/althi_uniq、/sdcard 根、Documents、Downloads…）。
     *
     * <p>只要没有「所有文件访问」权限，/sdcard 那些公共目录就写不动（Android 11+ 只允许经
     * MediaStore 操作媒体文件），这里**静默跳过**并记一行日志，不影响私有目录那一半照删。
     *
     * @return 删掉的条目数
     */
    static int wipeIdentityCopies(Context ctx) {
        int n = 0;
        StringBuilder seen = new StringBuilder();
        for (File root : identityCopyRoots(ctx)) {
            if (!root.isDirectory()) continue;
            // 只扫浅层（根、以及根下一层的登录目录），不递归整张卡
            n += wipeIdentityCopiesIn(root, 0, seen);
            File[] ls = root.listFiles();
            if (ls == null) continue;
            for (File d : ls) {
                if (!d.isDirectory()) continue;
                String nm = d.getName().toLowerCase(Locale.US);
                if (nm.contains("login") || nm.contains("account") || nm.equals("uuid")
                        || nm.equals("althi") || nm.equals("elneazh_keep")) {
                    n += wipeIdentityCopiesIn(d, 1, seen);
                }
            }
        }
        if (n > 0) {
            Log.i(TAG, "identity: removed " + n + " 身份副本: " + seen);
        } else {
            Log.i(TAG, "identity: no 外部身份副本 found (或无权访问 /sdcard 公共目录)");
        }
        return n;
    }

    private static int wipeIdentityCopiesIn(File dir, int depth, StringBuilder seen) {
        if (dir == null || !dir.isDirectory() || depth > 1) return 0;
        File[] ls = dir.listFiles();
        if (ls == null) return 0;
        int n = 0;
        for (File f : ls) {
            if (f.isDirectory()) continue;
            if (!isIdentityCopy(f.getName())) continue;
            try {
                if (f.delete()) {
                    n++;
                    if (seen.length() < 400) seen.append(seen.length() > 0 ? "; " : "").append(f.getAbsolutePath());
                }
            } catch (Throwable t) {
                Log.w(TAG, "identity: can not remove " + f + ": " + t);
            }
        }
        return n;
    }

    private static int deleteTree(File f) {
        int n = 0;
        if (f == null) return 0;
        File[] ls = f.listFiles();
        if (ls != null) for (File c : ls) n += c.isDirectory() ? deleteTree(c) : (c.delete() ? 1 : 0);
        if (f.delete()) n++;
        return n;
    }

    /**
     * 遍历 /data/data/<pkg>/shared_prefs 下每一个 xml，宽匹配删登录键。
     * 直接读文件而不走 {@code getSharedPreferences}：有些机型上游戏的 prefs 在
     * /data/user_de/<n>/ 下，getSharedPreferences 拿到的是另一份，删了不生效。
     */
    private static int clearAllPrefs(Context ctx, File data) {
        int n = 0;
        File sp = new File(data, "shared_prefs");
        File[] ls = sp.listFiles();
        if (ls != null) for (File f : ls) {
            if (!f.isFile() || !f.getName().endsWith(".xml")) continue;
            String base = f.getName().substring(0, f.getName().length() - 4);
            if (base.startsWith("elneazh_")) continue;                     // 我们自己的设置
            n += clearLoginPrefs(ctx, base);
        }
        // 游戏用的那份 PlayerPrefs 名字固定（libunity.so 里 '.v2.playerprefs'），保险再来一次
        n += clearLoginPrefs(ctx, prefsName(ctx));
        n += clearLoginPrefs(ctx, "althi_uniq");
        n += clearLoginPrefs(ctx, "login_key");
        return n;
    }

    /** 删 root 下（递归 8 层）所有 sv1_* 存档与临时文件，返回删除数。不进 UnityCache/il2cpp。 */
    private static int deleteSaveFiles(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 8) return 0;
        String dn = dir.getName();
        if (depth > 0 && (dn.equals("UnityCache") || dn.equals("il2cpp") || dn.equals("zh_backup")
                || dn.equals("cache") || dn.equals("code_cache"))) return 0;
        if (depth == 0) {
            // 根目录下只下探存档可能出现的那几个子目录，别乱扫
            int n = 0;
            for (String d : new String[]{"zh", "Contents", "save", "saves"})
                n += deleteSaveFiles(new File(dir, d), depth + 1);
            return n;
        }
        File[] ls = dir.listFiles();
        if (ls == null) return 0;
        int n = 0;
        for (File f : ls) {
            if (f.isDirectory()) { n += deleteSaveFiles(f, depth + 1); continue; }
            String nm = f.getName();
            if (!nm.startsWith("sv1_") && !nm.endsWith(".ntmp") && !nm.endsWith(".rtmp")) continue;
            try { if (f.delete()) n++; } catch (Throwable t) { Log.w(TAG, "wipe " + f, t); }
        }
        return n;
    }

    /** 只删登录键（宽匹配）；返回删掉的键数。commit 失败也不抛。 */
    private static int clearLoginPrefs(Context ctx, String name) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            if (all.isEmpty()) return 0;
            SharedPreferences.Editor e = sp.edit();
            int n = 0;
            StringBuilder hit = new StringBuilder();
            boolean whole = isLoginPrefFile(name);
            for (String k : all.keySet()) {
                String kd = android.net.Uri.decode(k);
                // 与「只恢复存档」共用 isIdentityKey —— 一处删、一处留会让换身份两头漏
                if (whole || isIdentityKey(k) || isIdentityKey(kd)) {
                    e.remove(k);
                    if (hit.length() < 200) hit.append(k).append(' ');
                    n++;
                }
            }
            if (n > 0) {
                boolean ok = e.commit();
                Log.i(TAG, "identity: cleared " + n + " login key(s) from " + name + " ok=" + ok
                        + " [" + hit.toString().trim() + "]");
            }
            return n;
        } catch (Throwable t) { Log.w(TAG, "clearLoginPrefs " + name, t); return 0; }
    }

    /**
     * 「只恢复存档」用：把刚被备份覆盖回来的 PlayerPrefs xml 里的身份键逐条去掉，
     * 其余设置（画面 / 音量 / 资源版本）保留。判据与换身份删除是同一套
     * {@link #isIdentityKey}，不会出现「这边删那边留」。
     */
    /** 去掉 SharedPreferences xml 里的身份键（不含身份的备份用），其余原样。 */
    static String stripXml(String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(string|int|long|boolean|float)\\s+name=\"([^\"]+)\"[^>]*>[^<]*</\\1>"
                        + "|<(string|int|long|boolean|float)\\s+name=\"([^\"]+)\"[^>]*/>")
                .matcher(xml);
        StringBuilder out = new StringBuilder(xml.length());
        int pos = 0;
        while (m.find()) {
            String k = m.group(2) != null ? m.group(2) : m.group(4);
            if (k != null && (isIdentityKey(k) || isIdentityKey(android.net.Uri.decode(k)))) {
                out.append(xml, pos, m.start());
                pos = m.end();
            }
        }
        out.append(xml.substring(pos));
        return out.toString();
    }

    /** 备份是否含身份：读第一条元信息；旧备份没有元信息时按「含身份」算（旧版一律整包）。 */
    static boolean metaHasIdentity(Context ctx, Uri u) {
        try (ZipInputStream z = new ZipInputStream(ctx.getContentResolver().openInputStream(u))) {
            ZipEntry e = z.getNextEntry();
            if (e == null || !META.equals(e.getName())) return true;
            byte[] b = new byte[256]; int n = z.read(b);
            return n <= 0 || !new String(b, 0, n, "UTF-8").contains("with_identity=0");
        } catch (Throwable t) { return true; }
    }

    static void stripIdentityKeys(Context ctx, File prefsXml) {
        try {
            String xml = readText(prefsXml);
            if (xml == null) return;
            StringBuilder out = new StringBuilder(xml.length());
            int removed = 0, kept = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<(string|int|long|boolean|float)\\s+name=\"([^\"]+)\"[^>]*>[^<]*</\\1>")
                    .matcher(xml);
            int pos = 0;
            while (m.find()) {
                String k = m.group(2);
                if (isIdentityKey(k)) {
                    out.append(xml, pos, m.start());
                    pos = m.end();
                    removed++;
                } else {
                    kept++;
                }
            }
            out.append(xml.substring(pos));
            if (removed == 0) { Log.i(TAG, "restore: prefs had no identity key to strip"); return; }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(prefsXml);
            try { fo.write(out.toString().getBytes("UTF-8")); } finally { fo.close(); }
            Log.i(TAG, "restore: stripped " + removed + " identity key(s), kept " + kept
                    + " setting(s) from " + prefsXml.getName());
        } catch (Throwable t) { Log.w(TAG, "stripIdentityKeys", t); }
    }

    /** 恢复备份时把里面带的 uuid / login_key 一起写回（旧身份能直接回去）。 */
    private static void restoreIdentityFile(Context ctx, File prefsXml) {
        try {
            String xml = readText(prefsXml);
            if (xml == null || (!xml.contains("name=\"uuid\"") && !xml.contains("name=\"login_key\""))) return;
            SharedPreferences sp = ctx.getSharedPreferences(prefsName(ctx), Context.MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            int n = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<(string|int|long|boolean|float)\\s+name=\"([^\"]+)\"\\s*>([^<]*)</\\1>").matcher(xml);
            while (m.find()) {
                String t = m.group(1), k = m.group(2), v = m.group(3);
                try {
                    if ("string".equals(t)) e.putString(k, v);
                    else if ("int".equals(t)) e.putInt(k, Integer.parseInt(v.trim()));
                    else if ("long".equals(t)) e.putLong(k, Long.parseLong(v.trim()));
                    else if ("boolean".equals(t)) e.putBoolean(k, "true".equalsIgnoreCase(v.trim()));
                    else if ("float".equals(t)) e.putFloat(k, Float.parseFloat(v.trim()));
                    n++;
                } catch (Throwable ignored) {}
            }
            boolean ok = e.commit();
            Log.i(TAG, "restore identity: " + n + " keys from " + prefsXml.getName() + " ok=" + ok);
        } catch (Throwable t) { Log.w(TAG, "restore identity", t); }
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) { return null; }
    }

    // ---------------- 存档管理 ----------------

    static final class Item { String name; Uri uri; long size; String ident; }

    /** Lists backups this install can see (newest first). */
    static List<Item> list(Context ctx) {
        List<Item> r = new ArrayList<>();
        try {
            if (rootOk(ctx)) {
                File[] ls = rootDir().listFiles();
                if (ls != null) for (File f : ls) if (f.getName().endsWith(".zip")) {
                    Item it = new Item(); it.name = f.getName(); it.uri = Uri.fromFile(f); it.size = f.length(); r.add(it);
                }
            }
        } catch (Throwable t) { Log.w(TAG, "list root", t); }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri col = MediaStore.Files.getContentUri("external");
                try (Cursor c = ctx.getContentResolver().query(col,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE},
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{Environment.DIRECTORY_DOCUMENTS + "/" + DIR + "%"},
                        MediaStore.MediaColumns.DISPLAY_NAME + " DESC")) {
                    if (c != null) while (c.moveToNext()) {
                        Item it = new Item();
                        it.uri = ContentUris.withAppendedId(col, c.getLong(0));
                        it.name = c.getString(1);
                        it.size = c.getLong(2);
                        if (it.name != null && it.name.endsWith(".zip")) r.add(it);
                    }
                }
            } else {
                File[] ls = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR).listFiles();
                if (ls != null) for (File f : ls) if (f.getName().endsWith(".zip")) {
                    Item it = new Item(); it.name = f.getName(); it.uri = Uri.fromFile(f); it.size = f.length(); r.add(it);
                }
            }
        } catch (Throwable t) { Log.w(TAG, "list", t); }
        java.util.Collections.sort(r, new java.util.Comparator<Item>() {
            @Override public int compare(Item x, Item y) { return y.name.compareTo(x.name); }
        });
        for (Item it : r) it.ident = ident(ctx, it.uri);
        return r;
    }

    /** 从备份 zip 的说明条目里读出身份摘要（uuid 打码 / login_key 有无），读不到给 null。 */
    private static String ident(Context ctx, Uri u) {
        try (ZipInputStream z = new ZipInputStream(ctx.getContentResolver().openInputStream(u))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!"elneazh_backup.txt".equals(e.getName())) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n;
                while ((n = z.read(b)) > 0 && bo.size() < 65536) bo.write(b, 0, n);
                for (String line : bo.toString("UTF-8").split("\n")) {
                    if (line.startsWith("identity=")) return line.substring(9).trim();
                }
                return null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** In-game backup manager: list, back up now, restore, delete. */
    static void manage(final Activity a) {
        new Thread(new Runnable() { @Override public void run() {
            final List<Item> items = list(a);
            new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() { showList(a, items); }});
        }}).start();
    }

    private static void showList(final Activity a, final List<Item> items) {
        final boolean root = rootOk(a);
        Pop p = new Pop(a, "存档管理");
        p.msg("联网和单机共用这些备份（单机记录也在里面）。点「读取」恢复，「删除」删掉。");
        if (items.isEmpty()) p.msg("这里还没看到备份。重装过游戏的话，点「从文件导入」去 Documents/" + DIR + " 里选。");
        for (final Item it : items) p.item(head(it), size(it),
                new Runnable() { @Override public void run() { confirmRestore(a, it.uri); } },
                new Runnable() { @Override public void run() { confirmDelete(a, it); } });
        if (!root) p.link("备份放在 Documents/" + DIR + "，点这里改放到根目录", new Runnable() { @Override public void run() { askRoot(a); } });
        p.btn("备份当前存档", 0, new Runnable() { @Override public void run() { doBackup(a, true); } });
        p.btn("从文件导入（别人发的 / 重装前的）", 1, new Runnable() { @Override public void run() { importFile(a); } });
        p.btn("关闭", 3, null);
        p.show();
    }

    /** 备份前选：含身份 / 不含身份。 */
    static void chooseBackup(final Activity a) {
        Pop p = new Pop(a, "备份当前存档");
        p.msg("选一种。两种都会放进存档管理，zip 里附带一份说明。");
        p.row("含身份（卡维护选这个）",
                "存档 + 账号身份（uuid / login_key / 设备码）原样保存。卡维护、卡登录时发给汉化作者排查；换机、重装回原号也用它。拿到的人能登你的号，只发给汉化作者。",
                new Runnable() { @Override public void run() { doBackup(a, true); } });
        p.row("不含身份（测试中）",
                "去掉身份文件，只留存档和设置。存档和设备码绑定，发给别人或换设备码后导入会卡登录，只适合本机留存档。",
                new Runnable() { @Override public void run() { doBackup(a, false); } });
        p.btn("取消", 3, new Runnable() { @Override public void run() { manage(a); } });
        p.show();
    }

    static void doBackup(final Activity a, final boolean withId) {
        toast(a, withId ? "正在备份（含身份）…" : "正在备份（不含身份）…");
        new Thread(new Runnable() { @Override public void run() {
            String n = make(a, "手动", withId);
            toast(a, n == null ? "备份失败（手机存储不可写？）" : "备份好了：" + n);
            manage(a);
        }}).start();
    }

    /** 系统文件选择器选一个备份 zip；结果只有 SupportActivity 收得到，所以不在它里面时转过去。 */
    static void importFile(final Activity a) {
        try {
            if (a instanceof SupportActivity) { pick(a); return; }
            Intent i = new Intent(a, SupportActivity.class);
            i.putExtra("restore", true);
            a.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "import", t);
            toast(a, "打不开文件选择器");
        }
    }

    private static void confirmDelete(final Activity a, final Item it) {
        new Pop(a, "删除备份")
                .msg("删除「" + head(it) + "」？删了就找不回来了。")
                .btn("删除", 2, new Runnable() { @Override public void run() {
                    boolean ok = false;
                    try {
                        if ("file".equals(it.uri.getScheme())) ok = new File(it.uri.getPath()).delete();
                        else ok = a.getContentResolver().delete(it.uri, null, null) > 0;
                    } catch (Throwable t) { Log.w(TAG, "delete", t); }
                    toast(a, ok ? "已删除" : "删不掉，请用文件管理删除");
                    manage(a);
                }})
                .btn("取消", 3, new Runnable() { @Override public void run() { manage(a); } })
                .show();
    }

    /** Small popup in the same cream/brown game style as {@link Support}. Every action dismisses it first. */
    static final class Pop {
        final Activity a; final float d; final Dialog dlg; final LinearLayout card, rows, btns;
        Pop(Activity a, String title) {
            this.a = a; d = a.getResources().getDisplayMetrics().density;
            dlg = new Dialog(a);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dlg.setCanceledOnTouchOutside(true);
            card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Support.round(Support.CREAM, Support.BROWN, 3 * d, 14 * d));
            int pad = (int) (18 * d);
            card.setPadding(pad, (int) (14 * d), pad, (int) (16 * d));
            TextView t = new TextView(a);
            t.setText(title); t.setTextColor(Support.BROWN); t.setTextSize(19);
            t.setTypeface(Typeface.DEFAULT_BOLD); t.setGravity(Gravity.CENTER);
            t.setPadding(0, 0, 0, (int) (8 * d));
            card.addView(t, new LinearLayout.LayoutParams(-1, -2));
            View line = new View(a);
            line.setBackgroundColor(Color.argb(0x66, 0x7A, 0x4B, 0x24));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, (int) (1.5f * d)));
            lp.bottomMargin = (int) (8 * d);
            card.addView(line, lp);
            rows = new LinearLayout(a); rows.setOrientation(LinearLayout.VERTICAL);
            btns = new LinearLayout(a); btns.setOrientation(LinearLayout.VERTICAL);
        }
        Pop msg(String m) {
            TextView tv = new TextView(a);
            tv.setText(m); tv.setTextColor(Support.BROWN_TEXT); tv.setTextSize(14); tv.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = (int) (4 * d);
            rows.addView(tv, lp);
            return this;
        }
        Pop row(String head, String sub, final Runnable r) {
            LinearLayout b = new LinearLayout(a);
            b.setOrientation(LinearLayout.VERTICAL);
            int p = (int) (12 * d);
            b.setPadding(p, (int) (9 * d), p, (int) (9 * d));
            StateListDrawable st = new StateListDrawable();
            st.addState(new int[]{android.R.attr.state_pressed}, Support.round(Color.rgb(0xE6, 0xCC, 0x9C), Support.BROWN, 1.5f * d, 10 * d));
            st.addState(new int[0], Support.round(Support.CREAM_DARK, Color.argb(0x99, 0x7A, 0x4B, 0x24), 1.5f * d, 10 * d));
            b.setBackground(st);
            b.setClickable(true);
            TextView h = new TextView(a);
            h.setText(head); h.setTextColor(Support.BROWN); h.setTextSize(15); h.setTypeface(Typeface.DEFAULT_BOLD);
            b.addView(h);
            if (sub != null) {
                TextView s2 = new TextView(a);
                s2.setText(sub); s2.setTextColor(Support.BROWN_TEXT); s2.setTextSize(12);
                b.addView(s2);
            }
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(r); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = (int) (8 * d);
            rows.addView(b, lp);
            return this;
        }
        /** One backup: text on the left, small 读取 / 删除 buttons on the right. */
        Pop item(String head, String sub, final Runnable load, final Runnable del) {
            LinearLayout b = new LinearLayout(a);
            b.setOrientation(LinearLayout.HORIZONTAL);
            b.setGravity(Gravity.CENTER_VERTICAL);
            int p = (int) (10 * d);
            b.setPadding((int) (12 * d), p, p, p);
            b.setBackground(Support.round(Support.CREAM_DARK, Color.argb(0x99, 0x7A, 0x4B, 0x24), 1.5f * d, 10 * d));
            LinearLayout txt = new LinearLayout(a);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView h = new TextView(a);
            h.setText(head); h.setTextColor(Support.BROWN); h.setTextSize(14); h.setTypeface(Typeface.DEFAULT_BOLD);
            txt.addView(h);
            TextView s2 = new TextView(a);
            s2.setText(sub); s2.setTextColor(Support.BROWN_TEXT); s2.setTextSize(12);
            txt.addView(s2);
            b.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            b.addView(small("读取", Support.ORANGE, Support.ORANGE_DARK, load));
            b.addView(small("删除", Color.rgb(0xC6, 0x3C, 0x2B), Color.rgb(0x8E, 0x22, 0x16), del));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = (int) (8 * d);
            rows.addView(b, lp);
            return this;
        }
        private TextView small(String text, int fill, int stroke, final Runnable r) {
            TextView t = new TextView(a);
            t.setText(text); t.setTextColor(Color.WHITE); t.setTextSize(14); t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            StateListDrawable st = new StateListDrawable();
            st.addState(new int[]{android.R.attr.state_pressed}, Support.round(stroke, stroke, 2 * d, 8 * d));
            st.addState(new int[0], Support.round(fill, stroke, 2 * d, 8 * d));
            t.setBackground(st);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(r); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (56 * d), (int) (36 * d));
            lp.leftMargin = (int) (6 * d);
            t.setLayoutParams(lp);
            return t;
        }
        Pop link(String text, final Runnable r) {
            TextView t = new TextView(a);
            t.setText(text); t.setTextColor(Support.ORANGE_DARK); t.setTextSize(12); t.setGravity(Gravity.CENTER);
            t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(r); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = (int) (10 * d);
            rows.addView(t, lp);
            return this;
        }
        Pop btn(String text, int style, final Runnable r) {
            int fill = style == 0 ? Support.ORANGE : style == 1 ? Support.CREAM_DARK : style == 2 ? Color.rgb(0xC6, 0x3C, 0x2B) : Support.BROWN;
            int stroke = style == 0 ? Support.ORANGE_DARK : style == 1 ? Support.BROWN : style == 2 ? Color.rgb(0x8E, 0x22, 0x16) : Color.rgb(0x4E, 0x2F, 0x14);
            int tc = style == 1 ? Support.BROWN : Color.WHITE;
            btns.addView(Support.button(a, d, text, fill, stroke, tc, new View.OnClickListener() {
                @Override public void onClick(View v) { go(r); }
            }));
            return this;
        }
        private void go(Runnable r) {
            try { dlg.dismiss(); } catch (Throwable ignored) {}
            if (r != null) try { r.run(); } catch (Throwable t) { Log.w(TAG, "pop", t); }
        }
        void show() {
            try {
                ScrollView sv = new ScrollView(a);
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(rows);
                int sh = a.getResources().getDisplayMetrics().heightPixels;
                card.addView(sv, new LinearLayout.LayoutParams(-1, -2));
                card.addView(btns, new LinearLayout.LayoutParams(-1, -2));
                dlg.setContentView(card);
                Window w = dlg.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    int sw = a.getResources().getDisplayMetrics().widthPixels;
                    w.setLayout(Math.min((int) (sw * 0.9f), (int) (380 * d)), ViewGroup.LayoutParams.WRAP_CONTENT);
                }
                // keep long lists from pushing the buttons off screen
                rows.measure(View.MeasureSpec.makeMeasureSpec((int) (340 * d), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED);
                if (rows.getMeasuredHeight() > sh * 0.45f) sv.getLayoutParams().height = (int) (sh * 0.45f);
                dlg.show();
            } catch (Throwable t) { Log.w(TAG, "pop show", t); }
        }
    }

    private static String head(Item it) {
        String n = it.name.replace("存档_", "").replace(".zip", "");
        String tag = n.contains("_无身份_") ? "（不含身份）" : n.contains("_含身份_") ? "（含身份）" : "";
        n = n.replace("_无身份_", "_").replace("_含身份_", "_");
        String[] p = n.split("_");
        String kind = p.length > 0 ? p[0] : n;
        String when = n;
        if (p.length >= 3 && p[1].length() == 8 && p[2].length() >= 4)
            when = p[1].substring(0, 4) + "-" + p[1].substring(4, 6) + "-" + p[1].substring(6, 8) + "  " + p[2].substring(0, 2) + ":" + p[2].substring(2, 4);
        return when + "  ·  " + kind + "备份" + tag;
    }

    private static String size(Item it) {
        String sz = it.size >= 1024 * 1024 ? String.format(Locale.US, "%.1f MB", it.size / 1048576.0)
                : it.size > 0 ? Math.max(1, it.size / 1024) + " KB" : "…";
        return sz + (it.ident != null && it.ident.length() > 0 ? "  ·  " + it.ident : "");
    }

    // ---------------- UI ----------------

    static void manual(final Activity a) {
        manual(a, null);
    }

    /**
     * 立即备份；成功时把文件名回调给调用方（Support 用它弹「分享到…」）。
     * 回调跑在主线程。
     */
    static void manual(final Activity a, final OnBackupDone done) { manual(a, done, true); }

    static void manual(final Activity a, final OnBackupDone done, final boolean withId) {
        toast(a, withId ? "正在备份（含身份）…" : "正在备份（不含身份）…");
        new Thread(new Runnable() { @Override public void run() {
            final String n = make(a, "手动", withId);
            if (n == null) {
                toast(a, "备份失败（手机存储不可写？）");
                if (done != null) new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { done.onDone(null); }
                });
                return;
            }
            final String where = (rootOk(a) ? "/sdcard/" : "Documents/") + DIR + "/" + n;
            toast(a, "已备份：" + where);
            Log.i(TAG, "manual backup ok: " + where);
            if (done != null) new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { done.onDone(n); }
            });
        }}).start();
    }

    /** 备份完成回调（主线程）；n 为 null 表示失败。 */
    interface OnBackupDone { void onDone(String name); }

    static void pick(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
            a.startActivityForResult(i, REQ_RESTORE);
            toast(a, "备份在 " + (rootOk(a) ? "根目录/" : "Documents/") + DIR + " 里");
        } catch (Throwable t) {
            Log.w(TAG, "pick", t);
            toast(a, "打不开文件选择器");
        }
    }

    /** From SupportActivity.onActivityResult. */
    static void confirmRestore(final Activity a, final Uri u) {
        try {
            final boolean[] chosen = {false};
            Pop p = new Pop(a, "恢复存档")
                    .msg("会覆盖当前存档。恢复后游戏会关闭，重新打开时生效。");
            // 身份要不要跟着回来，是两件完全不同的事，必须让用户自己选一次：
            //  - 完整恢复：连 uuid / login_key 一起写回 —— 等于「回到备份那台机器的身份」。
            //  - 只恢复存档：跳过全部身份/登录键 —— 换身份之后导老存档用这个，否则一导入身份就回去了。
            if (metaHasIdentity(a, u)) p.row("完整恢复（含身份）",
                    "存档 + 旧账号身份一起回来；想回到备份时的那个号选这个。",
                    new Runnable() { @Override public void run() { chosen[0] = true; askBackupFirst(a, u, true); } });
            else p.msg("这份备份不含身份，只能恢复存档，账号保持你现在的。");
            p.row("只恢复存档（保留当前身份）",
                    "只写回存档，不动 uuid / login_key；换过身份后用这个。单机模式用这个。",
                    new Runnable() { @Override public void run() { chosen[0] = true; askBackupFirst(a, u, false); } });
            p.btn("取消", 3, null);
            p.dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) { if (!chosen[0]) closeIfRestoreOnly(a); }
            });
            p.show();
        } catch (Throwable t) { Log.w(TAG, "confirm", t); }
    }

    private static void closeIfRestoreOnly(Activity a) {
        try { if (a.getIntent() != null && a.getIntent().getBooleanExtra("restore", false)) a.finish(); } catch (Throwable ignored) {}
    }

    static final String PENDING_RESTORE = "pending_restore";
    static final String PENDING_RESTORE_FILE = "elneazh_pending_restore.zip";

    private static void askBackupFirst(final Activity a, final Uri u, final boolean withIdentity) {
        try {
            final boolean[] chosen = {false};
            Pop p = new Pop(a, "恢复前要备份吗？")
                    .msg("要不要先把你现在的存档备份一份？以后想回来可以再恢复它。");
            p.row("先备份，再恢复", "多一份「恢复前」备份，稳妥。",
                    new Runnable() { @Override public void run() { chosen[0] = true; restore(a, u, withIdentity, true); } });
            p.row("不备份，直接恢复", "现在的存档会被覆盖，找不回来。",
                    new Runnable() { @Override public void run() { chosen[0] = true; restore(a, u, withIdentity, false); } });
            p.btn("取消", 3, null);
            p.dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) { if (!chosen[0]) closeIfRestoreOnly(a); }
            });
            p.show();
        } catch (Throwable t) { Log.w(TAG, "askBackupFirst", t); }
    }

    /**
     * 恢复 v2：当前进程只做（可选）备份 + 把 zip 拷到私有目录 + 立旗，然后直接杀进程。
     * 旧做法在游戏进程里写文件再 finishAffinity，Unity 退出时会把内存里的 PlayerPrefs（你当前的 uuid）
     * 写回去，盖掉刚恢复的身份 —— 这就是「读了别人的完整身份存档，身份没变」的原因。
     * 真正写入放在下次启动 ZhInit 最前面（Unity 起来之前）。完整恢复还会先按「清除数据」清空，
     * 免得备份里没有的你自己的账号块/存档/身份副本残留。
     */
    private static void restore(final Activity a, final Uri u, final boolean withIdentity, final boolean backupFirst) {
        toast(a, backupFirst ? "正在备份当前存档…" : "正在准备恢复…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                if (!looksLikeBackup(a, u)) { toast(a, "这不是汉化版的存档备份"); return; }
                if (backupFirst && make(a, "恢复前") == null) { toast(a, "备份当前存档失败，已取消恢复"); return; }
                File dst = new File(a.getFilesDir(), PENDING_RESTORE_FILE);
                File tmp = new File(dst.getPath() + ".part");
                try (InputStream in = a.getContentResolver().openInputStream(u);
                     OutputStream o = new FileOutputStream(tmp)) { pipe(in, o); }
                if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new java.io.IOException("rename"); }
                a.getSharedPreferences("elneazh_settings", Context.MODE_PRIVATE).edit()
                        .putString(PENDING_RESTORE, withIdentity ? "full" : "save").commit();
                toast(a, "游戏即将关闭，重新打开时完成恢复");
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() {
                    android.os.Process.killProcess(android.os.Process.myPid());   // 不走 finish，防 Unity 写回旧身份
                }}, 1800);
            } catch (Throwable t) {
                Log.e(TAG, "restore stage failed", t);
                toast(a, "恢复失败：" + t.getClass().getSimpleName());
            }
        }}).start();
    }

    /** ZhInit 最前面（换身份清理之后）调用。 */
    static boolean runPendingRestore(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("elneazh_settings", Context.MODE_PRIVATE);
        String mode = sp.getString(PENDING_RESTORE, null);
        if (mode == null) return false;
        sp.edit().remove(PENDING_RESTORE).commit();
        File zf = new File(ctx.getFilesDir(), PENDING_RESTORE_FILE);
        if (!zf.isFile()) { Log.w(TAG, "pending restore: zip missing"); return false; }
        boolean full = "full".equals(mode);
        try {
            if (full) {
                // 完整恢复 = 变成备份里那台机器的样子：先清干净（elneazh_* 留着，包括这份 zip）
                sp.edit().putString(PENDING_WIPE, "full").commit();
                runPendingIdentityWipe(ctx);
            }
            applyRestore(ctx, android.net.Uri.fromFile(zf), full);
            sp.edit().putString("last_restore", (full ? "完整恢复" : "只恢复存档") + " 完成 " + new Date()).commit();
        } catch (Throwable t) {
            Log.e(TAG, "pending restore failed", t);
            sp.edit().putString("last_restore", "恢复失败 " + t + " " + new Date()).commit();
        } finally {
            zf.delete();
        }
        return true;
    }

    private static void applyRestore(final Context ctx, final Uri u, final boolean withIdentity) throws Exception {
        File data = ctx.getFilesDir().getParentFile();
        String base = data.getCanonicalPath() + File.separator;
        int n = 0, skipped = 0;
        try (ZipInputStream z = new ZipInputStream(ctx.getContentResolver().openInputStream(u))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String p = e.getName();
                if (e.isDirectory()) continue;
                // 老备份里夹带的 native 库/调试表/文件选择器缓存：不写回，免得旧 .so 盖掉当前版本
                if (p.startsWith("files/lib/") || p.startsWith("files/image_provider/") || p.endsWith(".so")
                        || p.endsWith(".so.stamp") || p.endsWith("elneazh_dump.tsv")
                        || p.startsWith("shared_prefs/elneazh_settings")) continue;
                // 「只恢复存档」：身份文件整份跳过 —— 里面就是 uuid / login_key，
                // 写回一个 uuid 就等于把旧身份又装回了，换身份白做。
                if (!withIdentity && p.startsWith("shared_prefs/")
                        && isIdentityPrefFile(p.substring("shared_prefs/".length()))) {
                    skipped++;
                    Log.i(TAG, "restore: skipped identity prefs " + p);
                    continue;
                }
                // 「只恢复存档」：备份里散落的身份副本（files/althi_uniq、ext/ws_account…）同样不许写回 ——
                // 它们不是存档，写回去等于把旧设备码又装了一份。只认身份文件名，不误伤存档与汉化表。
                if (!withIdentity && isRestoreIdentityCopy(p)) {
                    skipped++;
                    Log.i(TAG, "restore: skipped identity copy " + p);
                    continue;
                }
                File t;
                if (p.startsWith("files/") || p.startsWith("shared_prefs/")) {
                    t = new File(data, p);
                    if (!t.getCanonicalPath().startsWith(base)) continue;
                } else if (p.startsWith("ext/") && ctx.getExternalFilesDir(null) != null) {
                    File ex = ctx.getExternalFilesDir(null);
                    t = new File(ex, p.substring(4));
                    if (!t.getCanonicalPath().startsWith(ex.getCanonicalPath() + File.separator)) continue;
                } else continue;
                File dir = t.getParentFile();
                if (dir != null && !dir.isDirectory()) dir.mkdirs();
                File tmp = new File(t.getPath() + ".rtmp");
                try (OutputStream o = new FileOutputStream(tmp)) { pipe(z, o); }
                if (!tmp.renameTo(t)) { t.delete(); tmp.renameTo(t); }
                n++;
            }
        }
        Log.i(TAG, "restored " + n + " files, skipped " + skipped + " identity file(s), withIdentity=" + withIdentity);
        // 身份（uuid / login_key）一起写回：旧身份解封后恢复即回去。只恢复存档时**绝不写**。
        if (withIdentity) {
            File prefsXml = new File(data, "shared_prefs/" + prefsName(ctx) + ".xml");
            if (prefsXml.isFile()) restoreIdentityFile(ctx, prefsXml);
        } else {
            // 游戏的 PlayerPrefs xml 里除了身份还有画面/音量/资源版本等设置，整份跳过会丢设置，
            // 所以这里逐键剔除：身份键全部删掉，其余设置保留。
            File prefsXml = new File(data, "shared_prefs/" + prefsName(ctx) + ".xml");
            if (prefsXml.isFile()) stripIdentityKeys(ctx, prefsXml);
            // 双保险：即使 prefs 文件没被扫到（老格式备份放在别处），也按同一套判定再清一次，
            // 保证「保留当前身份」这件事在结果上成立。
            int wiped = clearAllPrefs(ctx, data);
            Log.i(TAG, "restore: kept current identity, cleared " + wiped + " identity key(s)");
        }
        // 换成新身份后本机可能已经跑出了新号的存档；恢复旧备份时先删掉这些「备份里没有的 sv1_*」，
        // 否则新旧两份存档混在一起。只碰 sv1_*，不碰缓存、不碰汉化表。
        try {
            Set<String> keep = indexOf(ctx, u);
            for (File root : new File[]{ctx.getFilesDir(), ctx.getExternalFilesDir(null)}) {
                if (root == null) continue;
                for (String d : new String[]{"zh", "Contents"}) {
                    File[] ls = new File(root, d).listFiles();
                    if (ls == null) continue;
                    for (File f : ls) {
                        String nm = f.getName();
                        if (!nm.startsWith("sv1_") || !f.isFile()) continue;
                        String rel = (root == ctx.getFilesDir() ? "files/" : "ext/") + d + "/" + nm;
                        if (keep.contains(rel)) continue;
                        if (f.delete()) Log.i(TAG, "restore: removed newer save " + rel);
                    }
                }
            }
        } catch (Throwable t) { Log.w(TAG, "restore cleanup", t); }

    }

    private static boolean looksLikeBackup(Context c, Uri u) {
        try (ZipInputStream z = new ZipInputStream(c.getContentResolver().openInputStream(u))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (e.getName().equals("elneazh_backup.txt")) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    static void toast(final Context c, final String s) {
        new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
            try { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
        }});
    }
}
