package com.shuilize.elneazh.inj;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Injected into the localized game APK by 艾尔尼亚汉化器 (no root).
 * Runs inside the game process before the game starts and copies the Chinese master tables and the
 * Chinese map bundle (shipped as assets/elneazh/...) into the game's own directories:
 *   files/zh/            <- libil2cpp is patched to read master tables from /data/data/<pkg>/files/zh/
 *   UnityCache/Shared/map_large_en.android/<hash>/   (external + internal)
 * Existing non-table files from files/Contents (account token etc.) are carried over once.
 */
public final class ZhInit extends ContentProvider {
    private static final String TAG = "ElneaZH";
    private static final String MAP_DIR = "UnityCache/Shared/map_large_en.android/9e41473f5b511831105ea0f1695a843f";

    @Override public boolean onCreate() {
        try { deploy(getContext()); } catch (Throwable t) { Log.e(TAG, "deploy failed", t); }
        return true;
    }

    static void deploy(Context ctx) throws Exception {
        AssetManager am = ctx.getAssets();
        File zh = new File(ctx.getFilesDir(), "zh");
        if (!zh.isDirectory() && !zh.mkdirs()) throw new Exception("mkdir " + zh);
        // carry over files the game keeps next to its tables (only those we don't ship, only once)
        File old = new File(ctx.getFilesDir(), "Contents");
        File[] olds = old.listFiles();
        if (olds != null) for (File f : olds) {
            File t = new File(zh, f.getName());
            if (f.isFile() && !t.exists()) copy(new FileInputStream(f), t);
        }
        String[] tables = am.list("elneazh/Contents");
        if (tables != null) for (String n : tables) copyIfDiff(am, "elneazh/Contents/" + n, new File(zh, n));
        String[] map = am.list("elneazh/map");
        if (map != null) {
            File[] roots = { ctx.getExternalFilesDir(null), ctx.getFilesDir() };
            for (File root : roots) {
                if (root == null) continue;
                File d = new File(root, MAP_DIR);
                if (!d.isDirectory() && !d.mkdirs()) continue;
                for (String n : map) copyIfDiff(am, "elneazh/map/" + n, new File(d, n));
            }
        }
        Log.i(TAG, "deployed " + (tables == null ? 0 : tables.length) + " tables");
    }

    private static void copyIfDiff(AssetManager am, String asset, File dst) throws Exception {
        byte[] a = readAll(am.open(asset));
        if (dst.isFile() && dst.length() == a.length) {
            byte[] b = readAll(new FileInputStream(dst));
            if (java.util.Arrays.equals(a, b)) return;
        }
        File tmp = new File(dst.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(a); }
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new Exception("rename " + dst); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void copy(InputStream in, File dst) throws Exception {
        try (InputStream i = in; OutputStream o = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
