package com.shuilize.elneazh.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Minimal ZIP reader/writer that copies APK entries byte-for-byte (keeping their original compression),
 * drops the old v1 signature files, replaces one entry, and aligns STORED entries
 * (4 bytes; native libraries to 16 KB pages).
 */
public final class ApkRebuilder {
    private ApkRebuilder() {}

    public interface Progress { void onProgress(long done, long total); }

    public static final class Entry {
        public String name;
        byte[] rawName;
        int versionMadeBy, versionNeeded, flags, method, time, date;
        long crc, compSize, size;
        int internalAttr;
        long externalAttr;
        long localOffset;
        int alignment;
        public int method() { return method; }
        public long size() { return size; }
    }

    // ---------------------------------------------------------------- reading

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static long u32(byte[] b, int o) { return (u16(b, o) | ((long) u16(b, o + 2) << 16)) & 0xFFFFFFFFL; }

    public static List<Entry> readCentralDirectory(RandomAccessFile f) throws IOException {
        long len = f.length();
        int tail = (int) Math.min(len, 65557);
        byte[] t = new byte[tail];
        f.seek(len - tail);
        f.readFully(t);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("不是有效的 APK/ZIP 文件（找不到中央目录）");
        int count = u16(t, eocd + 10);
        long cdSize = u32(t, eocd + 12), cdOff = u32(t, eocd + 16);
        if (count == 0xFFFF || cdOff == 0xFFFFFFFFL) throw new IOException("暂不支持 ZIP64 格式的 APK");
        byte[] cd = new byte[(int) cdSize];
        f.seek(cdOff);
        f.readFully(cd);
        List<Entry> out = new ArrayList<>(count);
        int p = 0;
        for (int i = 0; i < count; i++) {
            if (u32(cd, p) != 0x02014b50L) throw new IOException("APK 中央目录损坏");
            Entry e = new Entry();
            e.versionMadeBy = u16(cd, p + 4);
            e.versionNeeded = u16(cd, p + 6);
            e.flags = u16(cd, p + 8);
            e.method = u16(cd, p + 10);
            e.time = u16(cd, p + 12);
            e.date = u16(cd, p + 14);
            e.crc = u32(cd, p + 16);
            e.compSize = u32(cd, p + 20);
            e.size = u32(cd, p + 24);
            int nl = u16(cd, p + 28), xl = u16(cd, p + 30), cl = u16(cd, p + 32);
            e.internalAttr = u16(cd, p + 36);
            e.externalAttr = u32(cd, p + 38);
            e.localOffset = u32(cd, p + 42);
            e.rawName = new byte[nl];
            System.arraycopy(cd, p + 46, e.rawName, 0, nl);
            e.name = new String(e.rawName, StandardCharsets.UTF_8);
            if (e.compSize == 0xFFFFFFFFL || e.size == 0xFFFFFFFFL || e.localOffset == 0xFFFFFFFFL)
                throw new IOException("暂不支持 ZIP64 条目：" + e.name);
            out.add(e);
            p += 46 + nl + xl + cl;
        }
        return out;
    }

    static long dataOffset(RandomAccessFile f, Entry e) throws IOException {
        byte[] h = new byte[30];
        f.seek(e.localOffset);
        f.readFully(h);
        if (u32(h, 0) != 0x04034b50L) throw new IOException("APK 本地文件头损坏：" + e.name);
        return e.localOffset + 30 + u16(h, 26) + u16(h, 28);
    }

    /** Bounded stream over a region of a RandomAccessFile. */
    static final class RegionStream extends InputStream {
        private final RandomAccessFile f; private long pos; private long left;
        RegionStream(RandomAccessFile f, long pos, long len) { this.f = f; this.pos = pos; this.left = len; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = (int) Math.min(len, left);
            f.seek(pos);
            n = f.read(b, off, n);
            if (n < 0) return -1;
            pos += n; left -= n; return n;
        }
    }

    /** Uncompressed content stream of an entry. Caller must not use f concurrently. */
    public static InputStream open(RandomAccessFile f, Entry e) throws IOException {
        InputStream raw = new BufferedInputStream(new RegionStream(f, dataOffset(f, e), e.compSize), 1 << 16);
        if (e.method == 0) return raw;
        if (e.method == 8) return new InflaterInputStream(raw, new Inflater(true), 1 << 16);
        throw new IOException("不支持的压缩方式 " + e.method + "：" + e.name);
    }

    public static Entry find(List<Entry> entries, String name) {
        for (Entry e : entries) if (e.name.equals(name)) return e;
        return null;
    }

    public static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /** SHA-256 (hex) of an entry's uncompressed content, or null if missing. */
    public static String sha256OfEntry(File apk, String name, Progress progress) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            Entry e = find(readCentralDirectory(f), name);
            if (e == null) return null;
            MessageDigest md = sha256();
            try (InputStream in = open(f, e)) {
                byte[] buf = new byte[1 << 16];
                long done = 0; int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n); done += n;
                    if (progress != null) progress.onProgress(done, e.size);
                }
            }
            return hex(md.digest());
        }
    }

    /** Extract an entry's uncompressed content to a file. */
    public static void extract(File apk, String name, File out) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            Entry e = find(readCentralDirectory(f), name);
            if (e == null) throw new IOException("APK 中没有 " + name);
            try (InputStream in = open(f, e); OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
                copy(in, o, Long.MAX_VALUE);
            }
        }
    }

    public static boolean hasEntry(File apk, String name) {
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            return find(readCentralDirectory(f), name) != null;
        } catch (IOException e) {
            return false;
        }
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    public static String sha256OfFile(File file) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    static long copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        while (total < max) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, max - total));
            if (n < 0) break;
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    // ---------------------------------------------------------------- writing

    public static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String rest = name.substring("META-INF/".length());
        if (rest.contains("/")) return false;
        String up = rest.toUpperCase(Locale.ROOT);
        return up.equals("MANIFEST.MF") || up.endsWith(".SF") || up.endsWith(".RSA")
                || up.endsWith(".DSA") || up.endsWith(".EC") || up.startsWith("SIG-");
    }

    private static final class CountingOut extends OutputStream {
        final OutputStream o; long n;
        CountingOut(OutputStream o) { this.o = o; }
        @Override public void write(int b) throws IOException { o.write(b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { o.write(b, off, len); n += len; }
        @Override public void flush() throws IOException { o.flush(); }
        @Override public void close() throws IOException { o.close(); }
    }

    private static void w16(OutputStream o, int v) throws IOException { o.write(v & 0xFF); o.write((v >>> 8) & 0xFF); }
    private static void w32(OutputStream o, long v) throws IOException { w16(o, (int) (v & 0xFFFF)); w16(o, (int) ((v >>> 16) & 0xFFFF)); }

    /**
     * Rebuild {@code inApk} into {@code outApk} (unsigned): all entries except old signature files are copied raw,
     * entry {@code replaceName} gets the content of {@code replacement} using the entry's original compression method.
     */
    public static void rebuild(File inApk, String replaceName, File replacement, File outApk, File tmpDir, Progress progress) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(inApk, "r");
             CountingOut out = new CountingOut(new BufferedOutputStream(new FileOutputStream(outApk), 1 << 16))) {
            List<Entry> entries = readCentralDirectory(in);
            List<Entry> written = new ArrayList<>();
            long total = 0, done = 0;
            for (Entry e : entries) total += e.compSize;
            for (Entry src : entries) {
                if (isSignatureFile(src.name)) { done += src.compSize; continue; }
                Entry e = new Entry();
                e.name = src.name; e.rawName = src.rawName; e.versionMadeBy = src.versionMadeBy;
                e.versionNeeded = src.versionNeeded; e.flags = src.flags & ~0x0008; // sizes are in the local header
                e.method = src.method; e.time = src.time; e.date = src.date;
                e.internalAttr = src.internalAttr; e.externalAttr = src.externalAttr;
                e.crc = src.crc; e.compSize = src.compSize; e.size = src.size;

                File deflated = null;
                boolean replace = replacement != null && src.name.equals(replaceName);
                if (replace) {
                    CRC32 crc = new CRC32();
                    try (InputStream r = new BufferedInputStream(new FileInputStream(replacement), 1 << 16)) {
                        byte[] buf = new byte[1 << 16]; int n;
                        while ((n = r.read(buf)) > 0) crc.update(buf, 0, n);
                    }
                    e.crc = crc.getValue();
                    e.size = replacement.length();
                    if (e.size >= 0xFFFFFFFFL) throw new IOException("新文件过大");
                    if (e.method == 0) {
                        e.compSize = e.size;
                    } else if (e.method == 8) {
                        deflated = new File(tmpDir, "replace.deflate");
                        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
                        try (InputStream r = new BufferedInputStream(new FileInputStream(replacement), 1 << 16);
                             OutputStream dout = new DeflaterOutputStream(new BufferedOutputStream(new FileOutputStream(deflated), 1 << 16), d, 1 << 16)) {
                            copy(r, dout, Long.MAX_VALUE);
                        } finally { d.end(); }
                        e.compSize = deflated.length();
                    } else throw new IOException("不支持的压缩方式 " + e.method);
                }

                // local header with alignment padding (zipalign-style 0xD935 extra field)
                e.localOffset = out.n;
                long headerEnd = out.n + 30 + e.rawName.length;
                int extraLen = 0;
                if (e.method == 0) {
                    int align = e.name.endsWith(".so") ? 16384 : 4;
                    long pad = (align - ((headerEnd + 6) % align)) % align;
                    extraLen = (int) (6 + pad);
                    e.alignment = align;
                }
                w32(out, 0x04034b50L); w16(out, e.versionNeeded); w16(out, e.flags); w16(out, e.method);
                w16(out, e.time); w16(out, e.date); w32(out, e.crc); w32(out, e.compSize); w32(out, e.size);
                w16(out, e.rawName.length); w16(out, extraLen);
                out.write(e.rawName);
                if (extraLen > 0) {
                    w16(out, 0xD935); w16(out, extraLen - 4); w16(out, e.alignment);
                    for (int i = 6; i < extraLen; i++) out.write(0);
                }
                if (replace) {
                    File body = deflated != null ? deflated : replacement;
                    try (InputStream r = new BufferedInputStream(new FileInputStream(body), 1 << 16)) {
                        if (copy(r, out, Long.MAX_VALUE) != e.compSize) throw new IOException("写入替换文件失败");
                    }
                    if (deflated != null) deflated.delete();
                } else {
                    try (InputStream r = new BufferedInputStream(new RegionStream(in, dataOffset(in, src), src.compSize), 1 << 16)) {
                        if (copy(r, out, src.compSize) != src.compSize) throw new IOException("读取原 APK 失败：" + src.name);
                    }
                }
                written.add(e);
                done += src.compSize;
                if (progress != null) progress.onProgress(done, total);
            }
            long cdStart = out.n;
            for (Entry e : written) {
                w32(out, 0x02014b50L); w16(out, e.versionMadeBy); w16(out, e.versionNeeded); w16(out, e.flags);
                w16(out, e.method); w16(out, e.time); w16(out, e.date); w32(out, e.crc); w32(out, e.compSize);
                w32(out, e.size); w16(out, e.rawName.length); w16(out, 0); w16(out, 0); w16(out, 0);
                w16(out, e.internalAttr); w32(out, e.externalAttr); w32(out, e.localOffset);
                out.write(e.rawName);
            }
            long cdSize = out.n - cdStart;
            w32(out, 0x06054b50L); w16(out, 0); w16(out, 0); w16(out, written.size()); w16(out, written.size());
            w32(out, cdSize); w32(out, cdStart); w16(out, 0);
        }
    }
}
