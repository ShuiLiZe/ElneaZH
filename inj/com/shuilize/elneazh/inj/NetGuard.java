package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 上传方向的兜底闸（单向：只改「发出去的」，不回改服务器响应）。
 *
 * 目标：游戏把存档里的中文名（NPC 名、玩家自取名）上传给 sns04/*.php 时，把明文里的汉字换成固定的
 * ASCII 拼音，让服务器看到的东西和原版客户端同构；响应里回来的拼音就原样当名字显示，不做反向替换。
 *
 * 挂钩点选择依据（静态验证过的）：
 *  - 游戏 HTTP 全部走 UnityWebRequest（APK 里 UnityWebRequestModule.dll；libunity.so 有 41 个
 *    UnityEngine.Networking.UnityWebRequest::* 符号），传输是 native curl，**不经过 okhttp/java.net**，
 *    所以拦 Java 的 HTTP 客户端无效。
 *  - libil2cpp.so 里 acc=/lang=/sns04/althi 字符串 0 命中，请求体是 C# 层用 il2cpp String 拼的内联代码，
 *    没有可替换的命名导出函数；il2cpp 字符串表指针（0x389c9a8）静态为 0、运行时才填，静态定位不到加密入口。
 *  - 因此可见的、稳定的明文出口是 **Unity 的 JNI 侧**：libunity.so 的 Java 辅助类方法
 *    （UnityPlayer/UnityWebRequest 的 Java 端）。这里用反射在「Java 可见的 upload handler / 请求体字节」
 *    上做替换——找不到就整体降级为空操作，绝不抛异常影响启动。
 *
 * 单向策略：只实现 body()/bytes()（请求方向）。响应方向一律不动（resp 保留原样）。
 */
final class NetGuard {
    private static final String TAG = "ElneaZH-NET";
    private static boolean installed;
    private static Context APP;

    private NetGuard() {}

    static synchronized boolean install(Context ctx) {
        if (installed) return true;
        if (ctx != null) APP = ctx.getApplicationContext();
        // Unity 的 JNI 上传路径在 Java 层没有稳定公开 API 可反射替换（见类注释），
        // 这里保留探测：若宿主暴露了可用的 upload hook 就装上，装不上就说清楚。
        installed = Hook.tryInstall();
        if (!installed) {
            Log.w(TAG, "upload gate: no Java-visible Unity upload hook; relying on name conversion at source"
                    + " (存档/取名时已转拼音) + body() helper");
        } else {
            Log.i(TAG, "upload gate installed");
        }
        return installed;
    }

    static boolean installed() { return installed; }

    /** Unity 上传钩子的探测与安装；单列一个类，失败不污染主流程。 */
    private static final class Hook {
        static boolean tryInstall() {
            // 逐条探测已知的 Unity Android 上传辅助类（不同 Unity 版本类名不同）。
            // 命中条件：类存在 + 有可写的 byte[]/String 字段或 setter。当前版本实测无命中 → 返回 false。
            String[] classes = new String[]{
                    "com.unity3d.player.UnityWebRequest",             // 部分版本
                    "com.unity3d.player.UnityPlayer",
                    "com.unity3d.player.ReflectionHelper",
            };
            for (String n : classes) {
                try {
                    Class<?> c = Class.forName(n);
                    for (Method m : c.getDeclaredMethods()) {
                        String mn = m.getName().toLowerCase();
                        if (mn.contains("upload") || mn.contains("request")) {
                            Log.i(TAG, "candidate upload hook: " + n + "." + m.getName());
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return false;   // 无可用 hook（见类注释的静态证据）
        }
    }

    // ---------------- 纯替换逻辑（离线可测，也被 SelfCheck 覆盖） ----------------

    /**
     * 一段明文请求体（形如 acc=<base64>&lang=en）：值里只要有汉字就换成 ASCII。
     * 关键：同一中文名恒定得到同一拼音（纯函数 + 别名表），不会因为两次上传拼法不同被服务器当成改名。
     * 无汉字时原样返回同一对象（零开销、不动 base64）。
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
                if (!nv.equals(v)) Log.i(TAG, "upload " + k + ": CJK -> ASCII (" + v + " -> " + nv + ")");
                out.append(k).append('=').append(nv);
            }
            if (amp < 0) break;
            out.append('&');
            i = amp + 1;
        }
        return out.toString();
    }

    /** 原始请求体（json/xml/base64 之外的明文）：整串过一遍。 */
    static String bytes(String plain) {
        if (plain == null || !Pinyin.hasNonAscii(plain)) return plain;
        String s = APP == null ? null : NameFix.sanitize(APP, plain);
        if (s == null) s = plain;
        if (!s.equals(plain)) Log.i(TAG, "upload body: CJK -> ASCII");
        return s;
    }

    /** 只把非 ASCII 换掉：汉字→拼音（走 NameFix，别名表优先，保证恒定），其它→丢掉并记日志。 */
    private static String value(String v) {
        if (!Pinyin.hasNonAscii(v)) return v;
        if (APP != null) {
            String s = NameFix.sanitize(APP, v);
            if (s != null && !Pinyin.hasNonAscii(s)) return s;
        }
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); ) {
            int cp = v.codePointAt(i);
            int w = Character.charCount(cp);
            if (cp > 0x20 && cp < 0x7F) {
                b.append((char) cp);
            } else if (Pinyin.isHan(cp)) {
                String py = Pinyin.toAscii(new String(Character.toChars(cp)));
                Log.i(TAG, "CJK U+" + Integer.toHexString(cp) + " -> " + py);
                b.append(py);
            } else {
                Log.i(TAG, "drop U+" + Integer.toHexString(cp));
            }
            i += w;
        }
        return b.toString();
    }

    /** 响应方向：按用户决定，不做任何反向替换，原样返回。 */
    static String resp(String plain) { return plain; }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int k;
        while ((k = in.read(b)) > 0) o.write(b, 0, k);
        return o.toByteArray();
    }

    static final Charset UTF8 = Charset.forName("UTF-8");
}
