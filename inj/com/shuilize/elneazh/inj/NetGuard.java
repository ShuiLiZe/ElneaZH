package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.nio.charset.Charset;

/**
 * 上传方向的闸门（单向：只改「发出去的东西」，服务器响应原样不动）。
 *
 * <p>真机已确认的挂点（本机 .so 上逐字节核对过）：
 * <ul>
 *   <li>加密函数 {@code Assembly-CSharp Cipher::EncryptRJ256(string)}，libil2cpp.so
 *       file offset 0x18a97e4 / VA 0x18ad7e4（入口 {@code str x30,[sp,#-0x30]!}，x0 = 明文，
 *       0x18ad97c 处 {@code b} 到返回 string 的构造器）；紧邻 0x18ad984 是 DecryptRJ256。</li>
 *   <li>它上面压栈 0x30 字节（{@code str x30,[sp,#-0x30]!} + 两次 stp），
 *       所以 16 字节的 inline hook 有地方放：保存 x0..x3，跳 Java 处理，恢复后执行原指令。</li>
 * </ul>
 *
 * <p>为什么还要 Java 侧这一层：改 .so 需要把机器码 + 跳板一起维护，风险比 Java 高；
 * 本类先把「明文 → 明文'」这段纯逻辑做成可离线验证的实现（{@link UploadNames}），
 * 并给出 native 入口 {@link #ensure(Context, String)}，等 so 补丁里那 16 字节跳板接进来即可。
 * 没接上时 {@link #installed()} 返回 false，日志里写明「闸门未激活」，绝不影响启动与游戏本身。
 *
 * <p>另外保留两个纯函数入口（{@link #body(String)} / {@link #blobs(String)}），
 * 供单元测试与以后可能出现的 Java 可见上传路径直接调用。
 */
final class NetGuard {
    static final String TAG = "ElneaZH-NET";
    private static boolean installed;
    private static Context APP;

    private NetGuard() {}

    static synchronized boolean install(Context ctx) {
        if (ctx != null) {
            APP = ctx.getApplicationContext();
            try { NameFix.load(ctx); } catch (Throwable t) { Log.w(TAG, "names load", t); }
        }
        if (installed) return true;
        // Java 层的 UnityWebRequest 导出：本机 APK 里没有 com.unity3d.player.UnityWebRequest，
        // 传输走 libunity.so 的 native curl，Java 侧没有可替换的入口（见 HANDOFF「网络栈」）。
        installed = probe();
        if (!installed) {
            Log.w(TAG, "upload gate: no Java-visible upload entry; name conversion runs on the"
                    + " EncryptRJ256 path (see HANDOFF) / ensure() is ready for the so hook");
        }
        return installed;
    }

    static boolean installed() { return installed; }

    private static boolean probe() {
        String[] classes = {
                "com.unity3d.player.UnityWebRequest",
                "com.unity3d.player.UnityPlayer",
        };
        for (String n : classes) {
            try {
                Class<?> c = Class.forName(n);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    String mn = m.getName().toLowerCase();
                    if (mn.contains("upload") || mn.contains("request")) {
                        Log.i(TAG, "candidate upload hook: " + n + "." + m.getName());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- native 入口（so 跳板调用）

    /**
     * 给 so 跳板准备的入口：入参是待加密的明文（EncryptRJ256 的 x0），返回替换后的明文。
     *
     * <p>规则：只处理 ws_*（base64(zlib)）区块里的名字槽，其它内容一律不改；
     * 处理不了（词表没加载、异常）就返回原串 —— 上传内容绝不因为这里而缺失或损坏。
     */
    static String ensure(Context ctx, String plain) {
        try {
            if (ctx != null) NameFix.load(ctx);
            String s = blobs(plain);
            if (s != plain) Log.i(TAG, "EncryptRJ256 plaintext: ws_* names -> ascii");
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "ensure failed, sending original plaintext", t);
            return plain;
        }
    }

    // ---------------------------------------------------------------- 纯函数（离线可测）

    /** 存档区块清洗：NPC 中文名 → 英文原名，玩家自定义中文名 → 固定拼音（同一中文名恒定同一结果）。 */
    static String blobs(String plain) {
        if (plain == null || !Pinyin.hasHan(plain)) return plain;
        final Context ctx = APP;
        String out = UploadNames.filterJsonBlobs(plain, new UploadNames.Mapper() {
            @Override public String ascii(String zh) {
                String a = ctx == null ? null : NameFix.ascii(ctx, zh);
                if (a == null) a = Pinyin.tidy(Pinyin.toAscii(zh));   // 词表没就绪时的兜底，同样是纯函数
                return a;
            }
        });
        NameFix.warnLeftover(out, "ws_* upload body");   // 只记 WARN，不阻断
        return out;
    }

    /**
     * 表单体（{@code acc=..&lang=en}）的值清洗：真机上 acc 已经是密文，这里只作为通用兜底 ——
     * 任何值里还有汉字就以同样的规则换掉，并打 ElneaZH-NET 日志。
     */
    static String body(String plain) {
        if (plain == null || !Pinyin.hasNonAscii(plain)) return plain;
        StringBuilder out = new StringBuilder(plain.length() + 32);
        int i = 0;
        while (i < plain.length()) {
            int amp = plain.indexOf('&', i);
            String seg = amp < 0 ? plain.substring(i) : plain.substring(i, amp);
            int eq = seg.indexOf('=');
            if (eq < 0) {
                out.append(seg);
            } else {
                String k = seg.substring(0, eq);
                String v = seg.substring(eq + 1);
                String nv = value(v);
                if (!nv.equals(v)) Log.i(TAG, "upload field " + k + ": CJK -> ASCII");
                out.append(k).append('=').append(nv);
            }
            if (amp < 0) break;
            out.append('&');
            i = amp + 1;
        }
        return out.toString();
    }

    private static String value(String v) {
        if (!Pinyin.hasNonAscii(v)) return v;
        String s = blobs(v);
        if (s == null) return v;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = Character.charCount(cp);
            if (cp >= 0x20 && cp < 0x7F) {
                b.append((char) cp);
            } else if (Pinyin.isHan(cp)) {
                String py = Pinyin.tidy(Pinyin.toAscii(new String(Character.toChars(cp))));
                if (py == null || py.isEmpty()) py = "N" + Integer.toHexString(cp).toUpperCase();
                Log.i(TAG, "CJK U+" + Integer.toHexString(cp) + " -> " + py);
                b.append(py);
            } else {
                Log.i(TAG, "drop U+" + Integer.toHexString(cp));
            }
            i += w;
        }
        return b.toString();
    }
    /** 响应方向：按用户决定不做任何反向替换，原样返回。 */
    static String resp(String plain) { return plain; }

    static final Charset UTF8 = Charset.forName("UTF-8");
}