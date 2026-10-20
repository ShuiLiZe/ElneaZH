package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.List;
import java.util.Locale;
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
        for (String s : new String[]{"UnityCache", "il2cpp", "zh_backup", "elneazh"}) SKIP_DIRS.add(s);
    }

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

    /** Writes one backup zip; returns its file name or null. */
    static String make(Context ctx, String kind) {
        String name = "存档_" + kind + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip";
        OutputStream out = null;
        try {
            out = openOut(ctx, name);
            if (out == null) return null;
            Set<String> tables = tableNames(ctx);
            File data = ctx.getFilesDir().getParentFile();
            try (ZipOutputStream z = new ZipOutputStream(out)) {
                StringBuilder lst = new StringBuilder();
                int n = add(z, new File(data, "files"), "files/", tables, 0, lst);
                n += add(z, new File(data, "shared_prefs"), "shared_prefs/", tables, 0, lst);
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) n += add(z, ext, "ext/", tables, 0, lst);
                z.putNextEntry(new ZipEntry("elneazh_backup.txt"));
                z.write(("ElneaZH save backup\nfiles=" + n + "\ntime=" + new Date() + "\ndata=" + data + "\next=" + ext
                        + "\n\n" + lst).getBytes("UTF-8"));
                z.closeEntry();
            }
            out = null;
            Log.i(TAG, "backup " + name);
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
        if (tables.contains(nm) && (path.endsWith("/zh/" + nm) || path.endsWith("/Contents/" + nm))) return 0;
        if (path.startsWith("shared_prefs/elneazh_backup")) return 0;
        lst.append(path).append('\t').append(f.length()).append('\n');
        z.putNextEntry(new ZipEntry(path));
        try (InputStream in = new FileInputStream(f)) { pipe(in, z); }
        z.closeEntry();
        return 1;
    }

    private static Set<String> tableNames(Context ctx) {
        Set<String> s = new HashSet<>();
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
            return u == null ? null : ctx.getContentResolver().openOutputStream(u);
        }
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR);
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return new FileOutputStream(new File(d, name));
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

    // ---------------- 存档管理 ----------------

    static final class Item { String name; Uri uri; long size; }

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
        return r;
    }

    private static String label(Item it) {
        String n = it.name.replace("存档_", "").replace(".zip", "");
        String[] p = n.split("_");
        if (p.length >= 3 && p[1].length() == 8 && p[2].length() >= 4) {
            n = p[0] + "  " + p[1].substring(0, 4) + "-" + p[1].substring(4, 6) + "-" + p[1].substring(6, 8)
                    + " " + p[2].substring(0, 2) + ":" + p[2].substring(2, 4);
        }
        return n + "  (" + Math.max(1, it.size / 1024) + " KB)";
    }

    /** In-game backup manager: list, back up now, restore, delete. */
    static void manage(final Activity a) {
        new Thread(new Runnable() { @Override public void run() {
            final List<Item> items = list(a);
            new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() { showList(a, items); }});
        }}).start();
    }

    private static void showList(final Activity a, final List<Item> items) {
        try {
            String[] labels = new String[items.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = label(items.get(i));
            AlertDialog.Builder b = new AlertDialog.Builder(a)
                    .setTitle(items.isEmpty() ? "还没有备份" : "存档备份 " + items.size() + " 份（" + (rootOk(a) ? "根目录" : "Documents") + "）")
                    .setPositiveButton("立即备份", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            toast(a, "正在备份存档…");
                            new Thread(new Runnable() { @Override public void run() {
                                String n = make(a, "手动");
                                toast(a, n == null ? "备份失败（手机存储不可写？）" : "已备份：" + n);
                                if (n != null) manage(a);
                            }}).start();
                        }
                    })
                    .setNeutralButton("从文件选择", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { Support.restore(a); }
                    })
                    ;
            final boolean root = rootOk(a);
            if (root) b.setNegativeButton("关闭", null);
            else b.setNegativeButton("改存根目录", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { askRoot(a); }
            });
            String where = root ? "备份位置：手机存储根目录 /" + DIR
                    : "备份位置：Documents/" + DIR + "。想放根目录 /" + DIR + "，点「改存根目录」授权一次。";
            if (items.isEmpty()) b.setMessage(where + "\n重装过游戏的话旧备份可能列不出来，点「从文件选择」去找。");
            else b.setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { itemMenu(a, items.get(w)); }
            });
            b.show();
        } catch (Throwable t) { Log.w(TAG, "showList", t); }
    }

    private static void itemMenu(final Activity a, final Item it) {
        try {
            new AlertDialog.Builder(a)
                    .setTitle(label(it))
                    .setItems(new String[]{"恢复这个备份", "删除这个备份", "查看备份了哪些文件"}, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            if (w == 0) confirmRestore(a, it.uri);
                            else if (w == 1) confirmDelete(a, it);
                            else showInfo(a, it);
                        }
                    })
                    .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { manage(a); }
                    }).show();
        } catch (Throwable t) { Log.w(TAG, "itemMenu", t); }
    }

    private static void showInfo(final Activity a, final Item it) {
        String txt;
        try (ZipInputStream z = new ZipInputStream(a.getContentResolver().openInputStream(it.uri))) {
            StringBuilder sb = new StringBuilder(); ZipEntry e; int n = 0;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals("elneazh_backup.txt")) continue;
                n++; sb.append(e.getName()).append('\n');
            }
            txt = "共 " + n + " 个文件：\n" + sb;
        } catch (Throwable t) { txt = "读不了：" + t; }
        try {
            new AlertDialog.Builder(a).setTitle(label(it)).setMessage(txt)
                    .setPositiveButton("返回", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { manage(a); }
                    }).show();
        } catch (Throwable t) { Log.w(TAG, "info", t); }
    }

    private static void confirmDelete(final Activity a, final Item it) {
        try {
            new AlertDialog.Builder(a)
                    .setTitle("删除备份")
                    .setMessage("确定删除「" + label(it) + "」？删了就找不回来了。")
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { manage(a); }
                    })
                    .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            boolean ok = false;
                            try {
                                if ("file".equals(it.uri.getScheme())) ok = new File(it.uri.getPath()).delete();
                                else ok = a.getContentResolver().delete(it.uri, null, null) > 0;
                            } catch (Throwable t) { Log.w(TAG, "delete", t); }
                            toast(a, ok ? "已删除" : "删不掉（可能是重装前的旧备份，请用文件管理删除）");
                            manage(a);
                        }
                    }).show();
        } catch (Throwable t) { Log.w(TAG, "confirmDelete", t); }
    }

    // ---------------- UI ----------------

    static void manual(final Activity a) {
        toast(a, "正在备份存档…");
        new Thread(new Runnable() { @Override public void run() {
            final String n = make(a, "手动");
            toast(a, n == null ? "备份失败（手机存储不可写？）" : "已备份：" + n);
        }}).start();
    }

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
            new AlertDialog.Builder(a)
                    .setTitle("恢复存档")
                    .setMessage("会用这个备份覆盖当前存档（当前存档会先自动备份一份）。恢复后游戏会关闭，请重新打开。")
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { closeIfRestoreOnly(a); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) { closeIfRestoreOnly(a); }
                    })
                    .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { restore(a, u); }
                    }).show();
        } catch (Throwable t) { Log.w(TAG, "confirm", t); }
    }

    private static void closeIfRestoreOnly(Activity a) {
        try { if (a.getIntent() != null && a.getIntent().getBooleanExtra("restore", false)) a.finish(); } catch (Throwable ignored) {}
    }

    private static void restore(final Activity a, final Uri u) {
        toast(a, "正在恢复…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                if (!looksLikeBackup(a, u)) { toast(a, "这不是汉化版的存档备份"); return; }
                make(a, "恢复前");
                File data = a.getFilesDir().getParentFile();
                String base = data.getCanonicalPath() + File.separator;
                int n = 0;
                try (ZipInputStream z = new ZipInputStream(a.getContentResolver().openInputStream(u))) {
                    ZipEntry e;
                    while ((e = z.getNextEntry()) != null) {
                        String p = e.getName();
                        if (e.isDirectory()) continue;
                        File t;
                        if (p.startsWith("files/") || p.startsWith("shared_prefs/")) {
                            t = new File(data, p);
                            if (!t.getCanonicalPath().startsWith(base)) continue;
                        } else if (p.startsWith("ext/") && a.getExternalFilesDir(null) != null) {
                            File ex = a.getExternalFilesDir(null);
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
                Log.i(TAG, "restored " + n + " files");
                toast(a, "已恢复 " + n + " 个文件，游戏即将关闭，请重新打开");
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() {
                    try { a.finishAffinity(); } catch (Throwable ignored) {}
                    android.os.Process.killProcess(android.os.Process.myPid());
                }}, 2500);
            } catch (Throwable t) {
                Log.e(TAG, "restore failed", t);
                toast(a, "恢复失败：" + t.getClass().getSimpleName());
            }
        }}).start();
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
