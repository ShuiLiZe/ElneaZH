package com.shuilize.elneazh.core;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * bspatch for the classic BSDIFF40 format (as written by Colin Percival's bsdiff and Python bsdiff4.file_diff).
 * Streams: old file is read with random access, output is written sequentially; neither is held in RAM.
 */
public final class BsPatch {
    private BsPatch() {}

    public interface Progress { void onProgress(long done, long total); }

    private static long offtin(byte[] b, int o) {
        long y = b[o + 7] & 0x7F;
        for (int i = 6; i >= 0; i--) y = (y << 8) | (b[o + i] & 0xFF);
        if ((b[o + 7] & 0x80) != 0) y = -y;
        return y;
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(buf, off, len);
            if (n < 0) throw new EOFException("补丁数据不完整");
            off += n; len -= n;
        }
    }

    private static InputStream block(File patch, long offset) throws IOException {
        FileInputStream fin = new FileInputStream(patch);
        long left = offset;
        while (left > 0) {
            long s = fin.skip(left);
            if (s <= 0) { fin.close(); throw new EOFException("补丁数据不完整"); }
            left -= s;
        }
        return new BZip2CompressorInputStream(new BufferedInputStream(fin, 1 << 16));
    }

    /** Apply patch to oldFile and write the result to out. Returns the new size. */
    public static long apply(File oldFile, File patch, OutputStream out, Progress progress) throws IOException {
        byte[] header = new byte[32];
        try (DataInputStream h = new DataInputStream(new FileInputStream(patch))) {
            h.readFully(header);
        }
        if (!new String(header, 0, 8, "US-ASCII").equals("BSDIFF40")) throw new IOException("补丁文件格式错误（不是 BSDIFF40）");
        long ctrlLen = offtin(header, 8), diffLen = offtin(header, 16), newSize = offtin(header, 24);
        if (ctrlLen < 0 || diffLen < 0 || newSize < 0) throw new IOException("补丁文件头损坏");

        try (InputStream ctrl = block(patch, 32);
             InputStream diff = block(patch, 32 + ctrlLen);
             InputStream extra = block(patch, 32 + ctrlLen + diffLen);
             RandomAccessFile old = new RandomAccessFile(oldFile, "r")) {
            long oldSize = old.length();
            byte[] c = new byte[24];
            byte[] dbuf = new byte[1 << 16];
            byte[] obuf = new byte[1 << 16];
            long newPos = 0, oldPos = 0, lastReport = 0;
            while (newPos < newSize) {
                readFully(ctrl, c, 0, 24);
                long x = offtin(c, 0), y = offtin(c, 8), z = offtin(c, 16);
                if (x < 0 || y < 0 || newPos + x > newSize) throw new IOException("补丁控制数据损坏");
                // diff block: new = diff + old
                long left = x;
                while (left > 0) {
                    int n = (int) Math.min(left, dbuf.length);
                    readFully(diff, dbuf, 0, n);
                    // fetch old bytes [oldPos, oldPos+n) where in range
                    long start = oldPos, end = oldPos + n;
                    long rs = Math.max(start, 0), re = Math.min(end, oldSize);
                    if (re > rs) {
                        old.seek(rs);
                        old.readFully(obuf, (int) (rs - start), (int) (re - rs));
                        for (int i = (int) (rs - start); i < (int) (re - start); i++) dbuf[i] += obuf[i];
                    }
                    out.write(dbuf, 0, n);
                    oldPos += n; newPos += n; left -= n;
                }
                // extra block: copied verbatim
                if (newPos + y > newSize) throw new IOException("补丁控制数据损坏");
                left = y;
                while (left > 0) {
                    int n = (int) Math.min(left, dbuf.length);
                    readFully(extra, dbuf, 0, n);
                    out.write(dbuf, 0, n);
                    newPos += n; left -= n;
                }
                oldPos += z;
                if (progress != null && newPos - lastReport > (1 << 20)) { progress.onProgress(newPos, newSize); lastReport = newPos; }
            }
            if (progress != null) progress.onProgress(newSize, newSize);
            return newSize;
        }
    }

    public static long apply(File oldFile, File patch, File outFile, Progress progress) throws IOException {
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16)) {
            return apply(oldFile, patch, o, progress);
        }
    }
}
