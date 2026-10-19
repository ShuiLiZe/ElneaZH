package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Converts English resident / family names that are already stored in an existing save into Chinese,
 * so old saves look the same as new games (the name tables only affect newly generated people).
 *
 * Save files files/{zh,Contents}/sv1_ws_profiles[_org] and sv1_ws_familys[_org] are zlib (or raw)
 * streams in which each name is written as 0x01 + length byte + UTF-8 bytes. Only exact dictionary
 * names are replaced; the profile stream is re-validated record by record and left untouched if
 * anything looks unexpected. The original file is backed up once to files/zh_backup/ first.
 */
final class SaveNames {
    private static final String TAG = "ElneaZH";

    static void run(Context ctx) {
        Map<String, byte[]> first = new HashMap<>(), last = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("elneazh/data/names.tsv"), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split("\t");
                if (p.length != 3) continue;
                byte[] z = p[2].getBytes("UTF-8");
                if (z.length == 0 || z.length > 120) continue;
                (p[0].equals("L") ? last : first).put(p[1], z);
            }
        } catch (Throwable t) { Log.w(TAG, "names.tsv missing", t); return; }
        File base = ctx.getFilesDir();
        File bak = new File(base, "zh_backup");
        for (String dir : new String[]{"zh", "Contents"}) {
            for (String f : new String[]{"sv1_ws_profiles", "sv1_ws_profiles_org"}) one(new File(new File(base, dir), f), first, true, bak, dir);
            for (String f : new String[]{"sv1_ws_familys", "sv1_ws_familys_org"}) one(new File(new File(base, dir), f), last, false, bak, dir);
        }
    }

    private static void one(File f, Map<String, byte[]> map, boolean profiles, File bak, String dir) {
        try {
            if (!f.isFile() || f.length() < 16 || f.length() > (8 << 20)) return;
            byte[] raw = read(new FileInputStream(f));
            boolean z = (raw[0] & 0xff) == 0x78;
            byte[] d = z ? inflate(raw) : raw;
            if (d == null) return;
            int[] hits = new int[1];
            byte[] o = convert(d, map, hits);
            if (hits[0] == 0) return;
            if (profiles && validProfiles(d) && !validProfiles(o)) { Log.w(TAG, "skip " + f + ": validation"); return; }
            if (!bak.isDirectory()) bak.mkdirs();
            File b = new File(bak, dir + "_" + f.getName());
            if (!b.exists()) write(b, raw);
            write(f, z ? deflate(o) : o);
            Log.i(TAG, "names " + f.getName() + ": " + hits[0]);
        } catch (Throwable t) { Log.w(TAG, "names " + f, t); }
    }

    static byte[] convert(byte[] d, Map<String, byte[]> map, int[] hits) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(d.length + 1024);
        int i = 0, n = d.length;
        while (i < n) {
            int b = d[i] & 0xff;
            if (b == 0x01 && i + 2 < n) {
                int len = d[i + 1] & 0xff;
                if (len >= 2 && len <= 32 && i + 2 + len <= n && isName(d, i + 2, len)
                        && (i + 2 + len >= n || !isAsciiLetter(d[i + 2 + len]))) {
                    String s = new String(d, i + 2, len, java.nio.charset.StandardCharsets.US_ASCII);
                    byte[] zh = map.get(s);
                    if (zh != null) {
                        o.write(0x01); o.write(zh.length); o.write(zh, 0, zh.length);
                        i += 2 + len; hits[0]++;
                        continue;
                    }
                }
            }
            o.write(b); i++;
        }
        return o.toByteArray();
    }

    private static boolean isAsciiLetter(byte c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); }

    private static boolean isName(byte[] d, int off, int len) {
        if (!isAsciiLetter(d[off])) return false;
        for (int k = off; k < off + len; k++) {
            byte c = d[k];
            if (!(isAsciiLetter(c) || c == '\'' || c == '-' || c == ' ')) return false;
        }
        return true;
    }

    /** [u32 n][9B] then n records [u32 id][0x01][len][name][25B] + 9B link except the last. */
    static boolean validProfiles(byte[] d) {
        if (d.length < 13) return false;
        long n = (d[0] & 0xffL) | (d[1] & 0xffL) << 8 | (d[2] & 0xffL) << 16 | (d[3] & 0xffL) << 24;
        if (n <= 0 || n > 100000) return false;
        int o = 13;
        for (long k = 0; k < n; k++) {
            if (o + 6 > d.length || d[o + 4] != 1) return false;
            o += 6 + (d[o + 5] & 0xff) + (k < n - 1 ? 34 : 25);
        }
        return o == d.length;
    }

    private static byte[] inflate(byte[] raw) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(raw);
            ByteArrayOutputStream o = new ByteArrayOutputStream(raw.length * 3);
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int k = inf.inflate(buf);
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                o.write(buf, 0, k);
            }
            boolean ok = inf.finished();
            inf.end();
            return ok ? o.toByteArray() : null;
        } catch (Throwable t) { return null; }
    }

    private static byte[] deflate(byte[] d) {
        Deflater def = new Deflater(9);
        def.setInput(d); def.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(d.length / 2 + 64);
        byte[] buf = new byte[65536];
        while (!def.finished()) { int k = def.deflate(buf); o.write(buf, 0, k); }
        def.end();
        return o.toByteArray();
    }

    private static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int k;
            while ((k = i.read(buf)) > 0) bo.write(buf, 0, k);
            return bo.toByteArray();
        }
    }

    private static void write(File dst, byte[] a) throws Exception {
        File tmp = new File(dst.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(a); o.getFD().sync(); }
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new Exception("rename " + dst); }
    }
}
