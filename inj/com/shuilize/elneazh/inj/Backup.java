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
        p.msg((root ? "备份位置：手机存储根目录 /" : "备份位置：Documents/") + DIR
                + (items.isEmpty() ? "\n还没有备份。" : "\n共 " + items.size() + " 份，点一份可以恢复或删除。"));
        for (final Item it : items) p.row(head(it), size(it), new Runnable() { @Override public void run() { itemMenu(a, it); } });
        p.btn("立即备份", 0, new Runnable() { @Override public void run() {
            toast(a, "正在备份存档…");
            new Thread(new Runnable() { @Override public void run() {
                String n = make(a, "手动");
                toast(a, n == null ? "备份失败（手机存储不可写？）" : "备份好了");
                manage(a);
            }}).start();
        }});
        if (!root) p.btn("改放到根目录", 1, new Runnable() { @Override public void run() { askRoot(a); } });
        p.btn("从文件里找备份", 1, new Runnable() { @Override public void run() { Support.restore(a); } });
        p.btn("关闭", 3, null);
        p.show();
    }

    private static void itemMenu(final Activity a, final Item it) {
        new Pop(a, head(it))
                .msg("大小 " + size(it))
                .btn("恢复这个备份", 0, new Runnable() { @Override public void run() { confirmRestore(a, it.uri); } })
                .btn("查看里面有哪些文件", 1, new Runnable() { @Override public void run() { showInfo(a, it); } })
                .btn("删除这个备份", 2, new Runnable() { @Override public void run() { confirmDelete(a, it); } })
                .btn("返回", 3, new Runnable() { @Override public void run() { manage(a); } })
                .show();
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
        new Pop(a, "备份内容").msg(txt)
                .btn("返回", 3, new Runnable() { @Override public void run() { itemMenu(a, it); } }).show();
    }

    private static void confirmDelete(final Activity a, final Item it) {
        new Pop(a, "删除备份")
                .msg("确定删除「" + head(it) + "」？删了就找不回来了。")
                .btn("删除", 2, new Runnable() { @Override public void run() {
                    boolean ok = false;
                    try {
                        if ("file".equals(it.uri.getScheme())) ok = new File(it.uri.getPath()).delete();
                        else ok = a.getContentResolver().delete(it.uri, null, null) > 0;
                    } catch (Throwable t) { Log.w(TAG, "delete", t); }
                    toast(a, ok ? "已删除" : "删不掉（可能是重装前的旧备份，请用文件管理删除）");
                    manage(a);
                }})
                .btn("取消", 3, new Runnable() { @Override public void run() { itemMenu(a, it); } })
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
        String[] p = n.split("_");
        String kind = p.length > 0 ? p[0] : n;
        String when = n;
        if (p.length >= 3 && p[1].length() == 8 && p[2].length() >= 4)
            when = p[1].substring(0, 4) + "-" + p[1].substring(4, 6) + "-" + p[1].substring(6, 8) + "  " + p[2].substring(0, 2) + ":" + p[2].substring(2, 4);
        return when + "  ·  " + kind + "备份";
    }

    private static String size(Item it) {
        return it.size >= 1024 * 1024 ? String.format(Locale.US, "%.1f MB", it.size / 1048576.0) : Math.max(1, it.size / 1024) + " KB";
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
            final boolean[] chosen = {false};
            Pop p = new Pop(a, "恢复存档")
                    .msg("会用这个备份覆盖当前存档（当前存档会先自动备份一份）。恢复后游戏会关闭，请重新打开。")
                    .btn("恢复", 0, new Runnable() { @Override public void run() { chosen[0] = true; restore(a, u); } })
                    .btn("取消", 3, null);
            p.dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) { if (!chosen[0]) closeIfRestoreOnly(a); }
            });
            p.show();
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
