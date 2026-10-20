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
                int n = add(z, new File(data, "files"), "files/", tables, 0);
                n += add(z, new File(data, "shared_prefs"), "shared_prefs/", tables, 0);
                z.putNextEntry(new ZipEntry("elneazh_backup.txt"));
                z.write(("ElneaZH save backup\nfiles=" + n + "\ntime=" + new Date() + "\n").getBytes("UTF-8"));
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

    private static int add(ZipOutputStream z, File f, String path, Set<String> tables, int depth) throws Exception {
        if (!f.exists() || depth > 8) return 0;
        if (f.isDirectory()) {
            if (depth == 1 && SKIP_DIRS.contains(f.getName())) return 0;
            File[] ls = f.listFiles();
            if (ls == null) return 0;
            int n = 0;
            for (File c : ls) n += add(z, c, path + (c.isDirectory() ? c.getName() + "/" : c.getName()), tables, depth + 1);
            return n;
        }
        String nm = f.getName();
        if (nm.endsWith(".tmp") || f.length() > MAX_FILE) return 0;
        if (tables.contains(nm) && (path.startsWith("files/zh/") || path.startsWith("files/Contents/"))) return 0;
        if (path.startsWith("shared_prefs/elneazh_backup")) return 0;
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
        for (String d : new String[]{"zh", "Contents"}) {
            File[] ls = new File(ctx.getFilesDir(), d).listFiles();
            if (ls != null) for (File f : ls) if (f.getName().startsWith("sv1_")) return true;
        }
        return false;
    }

    private static OutputStream openOut(Context ctx, String name) throws Exception {
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

    // ---------------- UI ----------------

    static void manual(final Activity a) {
        toast(a, "正在备份存档…");
        new Thread(new Runnable() { @Override public void run() {
            final String n = make(a, "手动");
            toast(a, n == null ? "备份失败（手机存储不可写？）" : "已备份到 Documents/" + DIR + "/" + n);
        }}).start();
    }

    static void pick(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
            a.startActivityForResult(i, REQ_RESTORE);
            toast(a, "请到 Documents/" + DIR + " 里选一个备份");
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
                        if (e.isDirectory() || !(p.startsWith("files/") || p.startsWith("shared_prefs/"))) continue;
                        File t = new File(data, p);
                        if (!t.getCanonicalPath().startsWith(base)) continue;
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
