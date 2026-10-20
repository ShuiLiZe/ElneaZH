package com.shuilize.elneazh.inj;

/**
 * 上传前「区块名」清洗：把游戏云存档里每一条 ws_* 值（base64(zlib(二进制))）中的中文名换成固定拼音。
 *
 * <p>数据形状（真机抓包 + 解压确证）：
 * <pre>
 *   {"user_id":"..","crypt_key":"..","savedata":"{\"ws_profiles\":\"&lt;base64(zlib(bytes))&gt;\", ..}","lang":"en"}
 * </pre>
 * 存档二进制里的名字槽是「0x01 标记 + 1 字节长度(UTF-8 字节数) + UTF-8 字节」，例如
 * {@code 01 06 E5 87 AF E6 81 A9} = "凯恩"。只有 0x01 开头、长度对得上、且内容是合法 UTF-8 的槽才算名字，
 * 所以不会误伤整数/外观/link 等二进制字段。
 *
 * <p>安全边界（宁可不动，也不写坏存档）：
 * <ul>
 *   <li>只改「含中文」的槽；没有中文的槽原样拷贝，其它字节（id、25 字节外观、link、噪声串）一个不动。</li>
 *   <li>拼音替换后长度必须 ≤ 255 字节，否则这条保持原样（绝不截断名字）。</li>
 *   <li>zlib 解不开 / base64 非法 / 结构对不上 —— 一律原样返回原串。</li>
 *   <li>单个 ws_* 值重新打包后不得超过原值长度（只许变短或等长），保证外层 JSON 不会撑大。</li>
 *   <li>全程纯函数（映射由调用方传入 {@link Mapper}），同一份算法在电脑上由
 *       tools/verify_upload_names.py 离线验过。</li>
 * </ul>
 */
final class UploadNames {
    static final String TAG = "ElneaZH-NET";

    /** 中文名 → 固定 ASCII；返回 null 表示这条不动。调用方负责「同一中文名永远同一拼音」。 */
    interface Mapper {
        String ascii(String zh);
    }

    private UploadNames() {}

    // ---------------------------------------------------------------- JSON 层

    /** 该字符串是不是 base64(zlib)：首字符解出来必须是 0x78（zlib 头），普通 uuid/token 会被排除。 */
    static boolean looksLikeZlibBase64(String v) {
        if (v == null || v.length() < 24 || v.length() % 4 != 0) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
            if (!ok) return false;
        }
        byte[] head = Base64.decode(v.substring(0, 4).replace('-', '+').replace('_', '/'));
        return head != null && head.length > 0 && (head[0] & 0xFF) == 0x78;
    }

    /**
     * 处理整段上传明文（EncryptRJ256 的入参）。
     * <p>不解析 JSON、不重新序列化：只扫「":" 之后紧跟的 base64(zlib) 字面量原位替换，
     * 其它字段、转义、顺序、空白全部零改动；没有变化时返回**同一个对象**。
     */
    static String filterJsonBlobs(String plain, Mapper mapper) {
        if (plain == null || mapper == null) return plain;
        if (!Pinyin.hasHan(plain) && !Pinyin.hasNonAscii(plain)) return plain;
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        int n = plain.length();
        while (i < n) {
            if (plain.charAt(i) != '"') { i++; continue; }
            int close = findStringEnd(plain, i);
            if (close < 0) break;
            String lit = plain.substring(i + 1, close);
            int k = i - 1;
            while (k >= 0 && isWs(plain.charAt(k))) k--;
            if (k >= 0 && plain.charAt(k) == ':') {
                // savedata 是「字符串里的 JSON」，内层引号是 \"，真正的 base64 还嵌一层
                String nested = rewriteNested(lit, mapper);
                if (nested != null) {
                    if (out == null) out = new StringBuilder(n + 64);
                    out.append(plain, copied, i + 1);
                    out.append(nested);
                    copied = close;
                }
            }
            i = close + 1;
        }
        if (out == null) return plain;
        out.append(plain, copied, n);
        return out.toString();
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    /** JSON 字符串字面量的结束引号位置（i 指向开引号）。 */
    private static int findStringEnd(String s, int i) {
        int n = s.length();
        int j = i + 1;
        while (j < n) {
            char c = s.charAt(j);
            if (c == '\\') {
                if (j + 1 < n) {
                    char d = s.charAt(j + 1);
                    if (d == '"' || d == '\\') { j += 2; continue; }
                }
                j++;
                continue;
            }
            if (c == '"') return j;
            j++;
        }
        return -1;
    }

    /**
     * 在一个已被外层转义的字符串字面量里，找内层 {@code \"key\":\"value\"} 的 value 并原位替换。
     *
     * @return 新字面量；没有改动返回 null
     */
    private static String rewriteNested(String lit, Mapper mapper) {
        StringBuilder out = new StringBuilder(lit.length() + 32);
        int i = 0;
        int n = lit.length();
        boolean touched = false;
        while (i < n) {
            int q = lit.indexOf("\\\"", i);
            if (q < 0) {
                out.append(lit, i, n);
                break;
            }
            out.append(lit, i, q + 2);        // 保留开头的 \"
            int start = q + 2;
            int e = -1;
            int scan = start;
            while (true) {
                int c = lit.indexOf("\\\"", scan);
                if (c < 0) break;
                int bs = 0;
                for (int b = c - 1; b >= start && lit.charAt(b) == '\\'; b--) bs++;
                if (bs % 2 == 0) { e = c; break; }
                scan = c + 2;
            }
            if (e < 0) {
                out.append(lit, q, n);
                break;
            }
            String val = lit.substring(start, e);
            String rep = val;
            if (looksLikeZlibBase64(val)) {
                String cand = filterOne(val, mapper);
                if (!cand.equals(val)) {
                    rep = cand.replace("/", "\\/");
                    touched = true;
                }
            }
            out.append(rep);
            out.append("\\\"");
            i = e + 2;
        }
        return touched ? out.toString() : null;
    }

    /** 单个 base64(zlib) 值：解 → 替换 → 压回。任何一步不合适都返回原串。 */
    static String filterOne(String b64, Mapper mapper) {
        try {
            byte[] packed = Base64.decode(b64);
            if (packed == null || packed.length < 2) return b64;
            if ((packed[0] & 0xFF) != 0x78) return b64;
            byte[] raw = inflate(packed);
            if (raw == null) return b64;
            byte[] fixed = filterNames(raw, mapper);
            if (fixed == raw) return b64;
            byte[] repacked = deflate(fixed);
            if (repacked == null) return b64;
            String out = Base64.encode(repacked);
            if (out.length() > b64.length()) return b64;      // 只许变短
            return out;
        } catch (Throwable t) {
            return b64;
        }
    }

    // ---------------------------------------------------------------- 存档二进制层（纯函数）

    /**
     * 扫一遍存档二进制，把所有「0x01 + 长度 + UTF-8」且含中文的名字槽换成拼音（长度字节同步改写）。
     *
     * @return 改过的数组；无变化时返回**同一个对象**
     */
    static byte[] filterNames(byte[] raw, Mapper mapper) {
        if (raw == null || raw.length < 3 || mapper == null) return raw;
        byte[] cur = null;
        int i = 0;
        while (i < raw.length) {
            byte[] src = cur == null ? raw : cur;
            int len = slotLen(src, i);
            if (len < 0) { i++; continue; }
            String name;
            try {
                name = new String(src, i + 2, len, "UTF-8");
            } catch (Throwable t) {
                i++;
                continue;
            }
            if (!Pinyin.hasHan(name)) { i += 2 + len; continue; }
            String ascii = mapper.ascii(name);
            if (ascii == null || ascii.isEmpty()) { i += 2 + len; continue; }
            byte[] nb;
            try {
                nb = ascii.getBytes("UTF-8");
            } catch (Throwable t) {
                i += 2 + len;
                continue;
            }
            if (nb.length > 255) { i += 2 + len; continue; }   // 太长：原样保留
            byte[] dst = new byte[src.length - len + nb.length];
            System.arraycopy(src, 0, dst, 0, i);
            dst[i] = 0x01;
            dst[i + 1] = (byte) nb.length;
            System.arraycopy(nb, 0, dst, i + 2, nb.length);
            System.arraycopy(src, i + 2 + len, dst, i + 2 + nb.length, src.length - (i + 2 + len));
            cur = dst;
            i += 2 + nb.length;
        }
        return cur == null ? raw : cur;
    }

    /** i 处若是一个合法名字槽，返回它的长度；否则 -1。 */
    static int slotLen(byte[] b, int i) {
        if (b == null || i + 2 > b.length) return -1;
        if ((b[i] & 0xFF) != 0x01) return -1;
        int len = b[i + 1] & 0xFF;
        if (len < 1 || len > 64 || i + 2 + len > b.length) return -1;
        for (int k = i + 2; k < i + 2 + len; k++) {
            int v = b[k] & 0xFF;
            if (v < 0x20 || v == 0x7F) return -1;
        }
        // 必须是合法 UTF-8（截断的多字节序列不算名字槽）
        try {
            byte[] copy = new byte[len];
            System.arraycopy(b, i + 2, copy, 0, len);
            String str = new String(copy, "UTF-8");
            if (!java.util.Arrays.equals(copy, str.getBytes("UTF-8"))) return -1;
        } catch (Throwable t) {
            return -1;
        }
        return len;
    }

    /** 统计名字槽数量（自检用）。 */
    static int countNames(byte[] raw) {
        if (raw == null) return 0;
        int i = 0, names = 0;
        while (i < raw.length) {
            int len = slotLen(raw, i);
            if (len < 0) { i++; continue; }
            names++;
            i += 2 + len;
        }
        return names;
    }

    // ---------------------------------------------------------------- zlib / base64

    static byte[] inflate(byte[] packed) {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        try {
            inf.setInput(packed);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(packed.length * 4 + 64);
            byte[] buf = new byte[16384];
            while (!inf.finished()) {
                int k;
                try {
                    k = inf.inflate(buf);
                } catch (java.util.zip.DataFormatException e) {
                    return null;
                }
                if (k == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                bo.write(buf, 0, k);
            }
            return inf.finished() ? bo.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        } finally {
            inf.end();
        }
    }

    static byte[] deflate(byte[] raw) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        try {
            d.setInput(raw);
            d.finish();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(raw.length / 2 + 64);
            byte[] buf = new byte[16384];
            while (!d.finished()) {
                int k = d.deflate(buf);
                if (k == 0 && d.needsInput()) break;
                bo.write(buf, 0, k);
            }
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            d.end();
        }
    }

    /** 极简 base64：不依赖 android.util.Base64，好让同一份算法在电脑上被 Python 侧逐字节核对。 */
    static final class Base64 {
        private static final char[] A =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        private static final int[] R = new int[128];

        static {
            for (int i = 0; i < R.length; i++) R[i] = -1;
            for (int i = 0; i < A.length; i++) R[A[i]] = i;
            R['-'] = 62;
            R['_'] = 63;
        }

        static String encode(byte[] b) {
            StringBuilder sb = new StringBuilder(((b.length + 2) / 3) * 4);
            int i = 0;
            while (i + 3 <= b.length) {
                int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8) | (b[i + 2] & 0xFF);
                sb.append(A[(v >> 18) & 63]).append(A[(v >> 12) & 63])
                        .append(A[(v >> 6) & 63]).append(A[v & 63]);
                i += 3;
            }
            int rest = b.length - i;
            if (rest == 1) {
                int v = (b[i] & 0xFF) << 16;
                sb.append(A[(v >> 18) & 63]).append(A[(v >> 12) & 63]).append("==");
            } else if (rest == 2) {
                int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8);
                sb.append(A[(v >> 18) & 63]).append(A[(v >> 12) & 63])
                        .append(A[(v >> 6) & 63]).append('=');
            }
            return sb.toString();
        }

        static byte[] decode(String s) {
            if (s == null) return null;
            int len = s.length();
            while (len > 0 && s.charAt(len - 1) == '=') len--;
            int outLen = len * 3 / 4;
            byte[] out = new byte[outLen + 3];
            int acc = 0, bits = 0, o = 0;
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                int v = c < 128 ? R[c] : -1;
                if (v < 0) return null;
                acc = (acc << 6) | v;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out[o++] = (byte) ((acc >> bits) & 0xFF);
                }
            }
            byte[] t = new byte[o];
            System.arraycopy(out, 0, t, 0, o);
            return t;
        }
    }
}
