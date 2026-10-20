package com.shuilize.elneazh.inj;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 汉字 → 拼音（ASCII，无声调，首字母大写）。
 *
 * 首选系统 ICU：android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")（API 29+，全覆盖、带多音字规则）。
 * API 24-28 没有 android.icu 的 Han-Latin，退回打包在 assets/elneazh/data/pinyin.tsv 里的 GB2312 6763 字表（tools/pinyin_tables.py 生成）。
 * 两者都拿不到时返回 null，调用方应提示玩家改用英文名（不静默丢名字）。
 */
final class Pinyin {
    private static final String TAG = "ElneaZH";
    private static final Map<Integer, String> TABLE = new HashMap<>();
    private static boolean tableLoaded;

    private Pinyin() {}

    static synchronized void attach(AssetManager am) {
        if (tableLoaded) return;
        tableLoaded = true;
        try (InputStream in = am.open("elneazh/data/pinyin.tsv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.isEmpty() || l.charAt(0) == '#') continue;
                int t = l.indexOf('\t');
                if (t <= 0 || t + 1 >= l.length()) continue;
                String c = l.substring(0, t), p = l.substring(t + 1).trim();
                if (c.isEmpty() || p.isEmpty()) continue;
                TABLE.put(c.codePointAt(0), p);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pinyin.tsv missing, ICU only", t);
        }
    }

    // ---------------- 字符判定 ----------------

    static boolean isHan(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F)
                || cp == 0x3007;
    }

    static boolean hasHan(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (isHan(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** 有没有 ASCII 之外的字符（汉字、全角标点、假名…）。 */
    static boolean hasNonAscii(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp > 0x7E || cp < 0x20) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    static boolean isClean(String s) {
        return s != null && !s.isEmpty() && !hasNonAscii(s);
    }

    // ---------------- 转换 ----------------

    /** 整串转拼音（每个汉字一段，首字母大写，其余小写，无声调）。非汉字保留。 */
    static String toAscii(String s) {
        if (s == null) return null;
        Object tr = transliterator();
        StringBuilder b = new StringBuilder(s.length() * 4);
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int w = Character.charCount(cp);
            String one = s.substring(i, i + w);
            i += w;
            if (cp < 0x80) {
                b.append((char) cp);
                continue;
            }
            String py = null;
            if (tr != null) {
                try {
                    py = (String) tr.getClass().getMethod("transliterate", String.class).invoke(tr, one);
                } catch (Throwable t) {
                    py = null;
                }
            }
            if (py == null || py.trim().isEmpty() || py.equals(one)) py = TABLE.get(cp);
            if (py == null || py.trim().isEmpty()) continue;   // 转不了就不产生字符
            b.append(cap(py.replace(" ", "")));
        }
        return b.toString();
    }

    /** 只保留可存档字符：ASCII 字母数字与 '_'，首字母大写；连续空白去掉。 */
    static String tidy(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        boolean up = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '-' || c == '\'' || c == '.') { continue; }
            if (c >= 'a' && c <= 'z') { b.append(up ? (char) (c - 32) : c); up = false; }
            else if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') { b.append(c); up = false; }
            else if (c == '_') { b.append(c); up = true; }
            else if (c > 0x7E || c < 0x20) { continue; }
            else { b.append(c); up = false; }
        }
        return b.toString();
    }

    private static String cap(String s) {
        if (s.isEmpty()) return s;
        char c = s.charAt(0);
        if (c >= 'a' && c <= 'z') return (char) (c - 32) + s.substring(1);
        if (c >= '0' && c <= '9') return "N" + s;   // 纯数字音节（如 〇）前面加 N 防歧义
        return s;
    }

    // android.icu.text.Transliterator 只有 API 29+ 才有；用反射拿，保证 classes3.dex 在 API 24 上也能加载。
    private static Object transliterator() {
        if (ICU_TRIED) return ICU;
        ICU_TRIED = true;
        try {
            Class<?> c = Class.forName("android.icu.text.Transliterator");
            Object t = c.getMethod("getInstance", String.class).invoke(null, "Han-Latin; Latin-ASCII");
            ICU = t;
            Log.i(TAG, "pinyin via android.icu Transliterator");
        } catch (Throwable t) {
            Log.i(TAG, "pinyin via builtin table (" + TABLE.size() + " chars): " + t);
            ICU = null;
        }
        return ICU;
    }

    private static boolean ICU_TRIED;
    private static Object ICU;

    static synchronized int tableSize() { return TABLE.size(); }
}
