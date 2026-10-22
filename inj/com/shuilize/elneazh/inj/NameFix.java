package com.shuilize.elneazh.inj;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传方向的「中文名 → 拼音」映射（单向，只处理发出去的东西）。
 *
 * 策略（用户定稿）：本地存档保持中文不动；只在数据离开设备（上传 sns04/*.php）时，
 * 把明文里的中文名换成固定拼音。响应里回来的拼音原样显示，不做反向还原。
 *
 * 三条保证：
 *  1. **恒定映射**：同一个中文名永远得到同一个拼音。别名表（elneazh_names）优先，命中即返回；
 *     未命中则由逐字拼音表拼出（纯函数，同一输入同一输出），拼出后立即写进别名表并落盘。
 *  2. **不改存档**：本类不再读写 sv1_ws_* 存档文件；中文名原样留在本地。
 *  3. **零误伤**：整串纯 ASCII 时直接返回原对象，不重建字符串、不动 base64。
 *
 * 词表来源：
 *  - assets/elneazh/data/names.tsv   原版英文名 ↔ 中文译名（kind/en/zh），用于把「译为中文的 NPC 名」换回英文原文，
 *                                    比拼音更贴近原版客户端的观感。
 *  - assets/elneazh/data/pinyin.tsv  GB2312 6763 字拼音兜底表（API 29 以下没有 android.icu 时用）。
 *  - SharedPreferences "elneazh_names"  玩家自定义名的「拼音 → 原中文」，随备份一起走（Backup 已含 shared_prefs）。
 */
final class NameFix {
    private static final String TAG = "ElneaZH";
    private static final String PREF = "elneazh_names";

    /** 中文 → 英文原文（names.tsv 正反两向，按 kind 分名/姓，查找时可跨表）。 */
    private static final Map<String, String> ZH2EN = new HashMap<>();
    /** 中文别名（玩家自己输入的） → 拼音。 */
    private static final Map<String, String> ALIAS_ZH2ASCII = new HashMap<>();
    private static boolean loaded;

    /** 中文名整词表，按长度降序排好，供 sanitize 做「最长优先」替换。 */
    private static List<String> ZH_KEYS = new ArrayList<>();
    private static boolean keysDirty = true;

    private NameFix() {}

    // ---------------- 词表 ----------------

    static synchronized void load(Context ctx) {
        if (loaded) return;
        loaded = true;
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("elneazh/data/names.tsv"), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split("\t");
                if (p.length != 3) continue;
                String en = p[1].trim(), zh = p[2].trim();
                if (en.isEmpty() || zh.isEmpty()) continue;
                if (!ZH2EN.containsKey(zh)) { ZH2EN.put(zh, en); n++; }
            }
        } catch (Throwable t) {
            Log.w(TAG, "names.tsv missing", t);
        }
        Pinyin.attach(ctx.getAssets());
        reloadAliases(ctx);
        Log.i(TAG, "names: " + n + " zh->en, aliases " + ALIAS_ZH2ASCII.size()
                + ", pinyin table " + Pinyin.tableSize());
    }

    static synchronized void reloadAliases(Context ctx) {
        ALIAS_ZH2ASCII.clear();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (!k.startsWith("n:") || !(v instanceof String)) continue;
                String ascii = k.substring(2), orig = (String) v;
                if (ascii.isEmpty() || orig.isEmpty()) continue;
                ALIAS_ZH2ASCII.put(orig, ascii);      // 原中文 -> 拼音
            }
        } catch (Throwable t) {
            Log.w(TAG, "aliases", t);
        }
        keysDirty = true;
    }

    /** 记「拼音 → 原中文」。同一拼音恒对应同一中文：已存在且不同则不覆盖（避免歧义）。 */
    static void remember(Context ctx, String ascii, String orig) {
        if (ctx == null || ascii == null || orig == null || ascii.isEmpty() || orig.isEmpty()) return;
        if (ascii.equals(orig)) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String key = "n:" + ascii;
            if (!orig.equals(sp.getString(key, null))) sp.edit().putString(key, orig).commit();
            synchronized (NameFix.class) {
                ALIAS_ZH2ASCII.put(orig, ascii);
                keysDirty = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "remember " + ascii, t);
        }
    }

    /** 中文名 → 固定拼音（纯函数 + 别名表；转得出就记进别名表，保证下次一模一样）。 */
    static String ascii(Context ctx, String zh) {
        if (zh == null || zh.isEmpty()) return null;
        synchronized (NameFix.class) {
            String a = ALIAS_ZH2ASCII.get(zh);
            if (a != null) return a;
        }
        String py = Pinyin.tidy(Pinyin.toAscii(zh));
        if (py == null || py.isEmpty()) return null;
        if (Pinyin.hasNonAscii(py)) return null;
        remember(ctx, py, zh);
        if (ctx != null) NameGate.remember(ctx, zh, py);   // 同步给 native 闸门，保持两边同一份表
        return py;
    }

    /**
     * 整张「中文 -> ASCII」映射快照（给 native 闸门用）：玩家别名优先，其次是 names.tsv 的英文原文。
     * 返回的是「中文名 -> 上传时使用的 ASCII」，不含任何未映射的项。
     */
    static synchronized java.util.List<java.util.Map.Entry<String, String>> pairs() {
        java.util.Map<String, String> all = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> e : ZH2EN.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) all.put(e.getKey(), e.getValue());
        }
        for (java.util.Map.Entry<String, String> e : ALIAS_ZH2ASCII.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) all.put(e.getKey(), e.getValue());
        }
        return new java.util.ArrayList<>(all.entrySet());
    }

    // ---------------- 上传前的清洗 ----------------

    /**
     * 存档区块（ws_profiles / ws_familys / ws_*）里名字槽的清洗：中文名换成上传用的 ASCII。
     *
     * <p>与 {@link #sanitize} 的区别：这里知道「这一槽是名字」，所以优先按整词查表 ——
     * NPC 名（names.tsv 里译过的人名）换回<b>英文原名</b>，与服务器上原本就有的名字一致；
     * 查不到表的（玩家自己起的中文名）才逐字转拼音。
     *
     * <p>长度：{@code slotLen} 允许 1..255，替换后长度可以变长（拼音比汉字更多字节），
     * 长度字节同步改写；超过 255 才放弃这一条（原样保留，绝不截断）。
     *
     * @return 改过的数组；无变化时返回**同一个对象**
     */
    static byte[] filterNames(Context ctx, byte[] raw, int[] changes) {
        if (raw == null || raw.length < 3) return raw;
        byte[] cur = null;
        int n = 0;
        int i = 0;
        while (i < raw.length) {
            byte[] src = cur == null ? raw : cur;
            int len = UploadNames.slotLen(src, i, 255);
            if (len < 0) { i++; continue; }
            String name;
            try {
                name = new String(src, i + 2, len, "UTF-8");
            } catch (Throwable t) {
                i++;
                continue;
            }
            if (!Pinyin.hasHan(name)) { i += 2 + len; continue; }
            String ascii;
            synchronized (NameFix.class) { ascii = ALIAS_ZH2ASCII.get(name); }
            if (ascii == null) ascii = ZH2EN.get(name);          // NPC：换回英文原名
            if (ascii == null) ascii = ascii(ctx, name);         // 玩家自定义：拼音
            if (ascii == null || ascii.isEmpty() || Pinyin.hasNonAscii(ascii)) { i += 2 + len; continue; }
            byte[] nb;
            try {
                nb = ascii.getBytes("UTF-8");
            } catch (Throwable t) {
                i += 2 + len;
                continue;
            }
            if (nb.length < 1 || nb.length > 255) { i += 2 + len; continue; }   // 超限：原样保留
            byte[] dst = new byte[src.length - len + nb.length];
            System.arraycopy(src, 0, dst, 0, i);
            dst[i] = 0x01;
            dst[i + 1] = (byte) nb.length;
            System.arraycopy(nb, 0, dst, i + 2, nb.length);
            System.arraycopy(src, i + 2 + len, dst, i + 2 + nb.length, src.length - (i + 2 + len));
            cur = dst;
            n++;
            i += 2 + nb.length;
        }
        if (changes != null && changes.length > 0) changes[0] = n;
        return cur == null ? raw : cur;
    }

    /**
     * 洗完一遍后，明文里若还有汉字，按「不阻断」原则只记一条 WARN（用户要求：不阻断请求）。
     *
     * @return 剩下的汉字个数
     */
    static int warnLeftover(String plain, String where) {
        if (plain == null) return 0;
        int n = 0;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            if (Pinyin.isHan(cp)) n++;
            i += Character.charCount(cp);
        }
        if (n > 0) Log.w("ElneaGate", "WARN name filter left " + n + " CJK char(s) in " + where
                + " (not blocking the request)");
        return n;
    }

    /**
     * 明文里还有汉字就替换成 ASCII：先 names.tsv 的中文名（整词，最长优先，换回英文原文），
     * 再玩家别名，最后逐字拼音。返回 ASCII 串；无汉字时原样返回同一个对象（调用方可用引用比较跳过）。
     */
    static String sanitize(Context ctx, String plain) {
        if (plain == null || !Pinyin.hasNonAscii(plain)) return plain;
        String s = plain;
        for (String zh : keys()) {
            if (!s.contains(zh)) continue;
            String a;
            synchronized (NameFix.class) { a = ALIAS_ZH2ASCII.get(zh); }
            if (a == null) a = ZH2EN.get(zh);
            if (a == null) continue;
            s = s.replace(zh, a);
            if (!Pinyin.hasNonAscii(s)) return s;
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int w = Character.charCount(cp);
            if (cp >= 0x20 && cp < 0x7F) {
                b.append((char) cp);
            } else if (Pinyin.isHan(cp)) {
                String py = Pinyin.tidy(Pinyin.toAscii(new String(Character.toChars(cp))));
                if (py == null || py.isEmpty()) py = "N" + Integer.toHexString(cp).toUpperCase();  // 兜底也恒定
                Log.i("ElneaZH-NET", "U+" + Integer.toHexString(cp) + " -> " + py);
                b.append(py);
            } else {
                Log.i("ElneaZH-NET", "drop U+" + Integer.toHexString(cp));
            }
            i += w;
        }
        return b.toString();
    }

    /** 所有已知中文名（names.tsv + 别名），按长度降序；变更时重建。 */
    private static synchronized List<String> keys() {
        if (!keysDirty) return ZH_KEYS;
        List<String> k = new ArrayList<>(ZH2EN.keySet());
        k.addAll(ALIAS_ZH2ASCII.keySet());
        final Map<String, Integer> prio = new HashMap<>();
        for (int i = 0; i < k.size(); i++) prio.put(k.get(i), i);
        Collections.sort(k, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int d = b.length() - a.length();
                return d != 0 ? d : prio.get(a) - prio.get(b);
            }
        });
        ZH_KEYS = k;
        keysDirty = false;
        return ZH_KEYS;
    }

    // ---------------- 工具 ----------------

    static String stats() {
        synchronized (NameFix.class) {
            return "zh2en " + ZH2EN.size() + ", aliases " + ALIAS_ZH2ASCII.size() + ", pinyin " + Pinyin.tableSize();
        }
    }

    static Map<String, String> aliasSnapshot() {
        synchronized (NameFix.class) { return new LinkedHashMap<>(ALIAS_ZH2ASCII); }
    }

    /** names.tsv 里「中文译名 -> 英文原名」的快照（导出名字对照表用，只读）。 */
    static Map<String, String> npcSnapshot() {
        synchronized (NameFix.class) { return new LinkedHashMap<>(ZH2EN); }
    }

    static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int k;
            while ((k = i.read(buf)) > 0) bo.write(buf, 0, k);
            return bo.toByteArray();
        }
    }

    static void write(File dst, byte[] a) throws Exception {
        File tmp = new File(dst.getPath() + ".ntmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(a); o.getFD().sync(); }
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new Exception("rename " + dst); }
    }
}
