package com.shuilize.elneazh.inj;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Chinese copies of downloaded AssetBundles (tutorial figures, gacha / event banners).
 * Payload: assets/elneazh/bundles/index.tsv  (bundle \t hash \t size, one per line)
 *          assets/elneazh/bundles/<bundle>~<hash>.data / .info
 * Writes them into UnityCache/Shared/<bundle>/<hash>/__data (+__info) under external and internal files dirs,
 * and also over any other hash folder the game already has for the same bundle (the hash comes from a runtime
 * version string we cannot know statically).  Unchanged files (same size) are skipped, so a normal launch only
 * stats a few files.  Every bundle is handled in its own try/catch: a failure never stops the game.
 */
final class Bundles {
    private static final String TAG = "ElneaZH";
    private static final String DIR = "elneazh/bundles";

    static void run(Context ctx) throws Exception {
        AssetManager am = ctx.getAssets();
        String[] names = am.list(DIR);
        if (names == null || names.length == 0) return;
        File[] roots = { ctx.getExternalFilesDir(null), ctx.getFilesDir() };
        int wrote = 0, n = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(am.open(DIR + "/index.tsv"), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.trim().split("\t");
                if (c.length < 3) continue;
                n++;
                try { wrote += one(am, roots, c[0], c[1], Long.parseLong(c[2])); }
                catch (Throwable t) { Log.w(TAG, "bundle " + c[0] + " failed", t); }
            }
        } finally { r.close(); }
        Log.i(TAG, "bundles: " + n + " zh, wrote " + wrote + " file(s)");
    }

    private static int one(AssetManager am, File[] roots, String bundle, String hash, long size) throws Exception {
        String base = DIR + "/" + bundle + "~" + hash;
        int wrote = 0;
        for (File root : roots) {
            if (root == null) continue;
            File bdir = new File(root, "UnityCache/Shared/" + bundle);
            File own = new File(bdir, hash);
            if (!own.isDirectory() && !own.mkdirs()) continue;
            File info = new File(own, "__info");
            if (!info.isFile()) { copy(am, base + ".info", info); wrote++; }
            File[] hs = bdir.listFiles();
            if (hs == null) continue;
            for (File h : hs) {
                if (!h.isDirectory()) continue;
                File d = new File(h, "__data");
                if (!h.equals(own) && !d.isFile()) continue;   // empty / in-progress folder of the game: leave it
                if (d.isFile() && d.length() == size) continue;
                copy(am, base + ".data", d); wrote++;
            }
        }
        return wrote;
    }

    private static void copy(AssetManager am, String asset, File dst) throws Exception {
        File tmp = new File(dst.getPath() + ".zhtmp");
        InputStream in = am.open(asset);
        try {
            OutputStream o = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536]; int k;
                while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
            } finally { o.close(); }
        } finally { in.close(); }
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new Exception("rename " + dst); }
    }
}
