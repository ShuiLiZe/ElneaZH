package com.shuilize.elneazh.inj;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * {@code libelneagate.so} 的**唯一**加载入口（名字闸门 / 单机闸门共用同一份）。
 *
 * <p>真机事故（selfcheck 里 {@code offline native: native: not loaded}、logcat 三条闸门标签全空）：
 * 旧的 {@code NameGate.load()} 把 .so 从 assets 解到 {@code files/lib/} 之后却调
 * {@code System.loadLibrary("elneagate")} —— 那是让 linker 去<b>安装包自己的 lib 目录</b>里找，
 * 而游戏 APK 的 {@code lib/arm64-v8a/} 里根本没有我们后塞进去的 .so（也没有 {@code android:extractNativeLibs}
 * 之外的任何登记），于是必然 {@link UnsatisfiedLinkError}；更糟的是两个闸门都用了
 * {@code loadTried} 一次性开关，失败一次之后<b>永不重试</b>，整个进程里 native 一直是空的。
 *
 * <p>现在：
 * <ol>
 *   <li>解包（{@code assets/elneagate/lib<abi>/libelneagate.so} → {@code files/lib/libelneagate.so}，
 *       大小一致直接复用），拿到**绝对路径**；</li>
 *   <li>{@code System.load(绝对路径)} —— 私有 files 目录不在 W^X 限制路径下，targetSdk 36 也能 load；</li>
 *   <li>失败**不锁死**：下一次调用重新解包 + 重新 load（间隔 {@value #RETRY_GAP_MS}ms），
 *       并把最后一次的 dlopen 错误串留在 {@link #err()} 里，selfcheck 原样写出来；</li>
 *   <li>每次尝试都由 {@link #tryLoad(Context)} 打一条 {@code ElneaGate} 日志（成功/失败都打），
 *       真机 {@code adb logcat -s ElneaGate} 就能看到到底卡在哪一步。</li>
 * </ol>
 *
 * <p>标签统一 {@code ElneaGate}：两个闸门本来就共用一个 .so，分开打标签只会让人以为是两次不同的失败。
 */
final class NativeLoad {
    /** 两个闸门共用的日志标签。 */
    static final String TAG = "ElneaGate";
    static final String LIB = "elneagate";
    /** 失败后多久允许重试（毫秒）。 */
    static final long RETRY_GAP_MS = 2000;

    private static boolean loaded;
    private static boolean loading;
    private static long lastTry;
    private static String lastErr = "";
    private static String lastPath = "";
    private static int attempts;
    private static int okAttempt;

    private NativeLoad() {}

    /**
     * 只回答「.so 已经在进程里了吗」，**绝不加载**（不解包、不 System.load）——
     * 别处（日志、守卫判定）只能问这个，避免顺手把加载拖到主线程上。
     */
    static synchronized boolean ready() {
        return loaded;
    }

    /** 正在加载中（后台线程里 System.load 还没返回）：用来合并重复请求，避免两个线程同时 load。 */
    static synchronized boolean loading() {
        return loading;
    }

    /** **只给自检/后台用**：清掉「2 秒内不重试」的节流，让安装线程立刻再试一次。 */
    static synchronized void resetBackoff() {
        lastTry = 0;
    }

    /** native 崩溃保护的 JNI 入口（eg_guard.c）：把这一轮的允许标记与安装窗口推给 native。 */
    static native int nativeGuard(int allowed, int windowMs);

    static native String nativeBootReport();

    /**
     * 同步加载；已加载直接 true。失败会返回 false，但**下一次调用还会再试**
     * （这就是「后台重试真的跑」的落点：{@link NameGate#install} / {@link OfflineGate#installAll} 每轮都调它）。
     */
    static synchronized boolean tryLoad(Context ctx) {
        if (loaded) return true;
        if (ctx == null) {
            lastErr = "no Context";
            return false;
        }
        // crash guard / 用户关掉「原生加速」时：一次也不 load。
        // 这里必须在加载**之前**问，因为 .so 一旦进进程就收不回来。
        if (!NativeGuard.allowed()) {
            lastErr = NativeGuard.reason();
            Log.i(TAG, "load skipped: " + lastErr);
            return false;
        }
        long now = System.currentTimeMillis();
        if (loading) return false;                       // 另一个线程正在 load，别抢
        if (lastTry != 0 && now - lastTry < RETRY_GAP_MS) return false;
        lastTry = now;
        attempts++;
        String path = null;
        loading = true;
        try {
            path = ensure(ctx, LIB);
            if (path == null) {
                lastErr = "unpack failed: assets/elneagate/lib" + abi()
                        + "/lib" + LIB + ".so -> " + libDir(ctx)
                        + " (asset missing or files dir not writable)";
                Log.w(TAG, "attempt#" + attempts + " " + lastErr);
                return false;
            }
            System.load(path);
            loaded = true;
            okAttempt = attempts;
            lastPath = path;
            lastErr = "";
            Log.i(TAG, "lib" + LIB + ".so loaded: " + path + " (" + new File(path).length()
                    + " B, abi=" + abi() + ", attempt#" + attempts + ")");
            return true;
        } catch (Throwable t) {
            lastErr = describe("System.load(" + path + ")", t);
            Log.w(TAG, "attempt#" + attempts + " lib" + LIB + ".so NOT loaded: " + lastErr);
            return false;
        } finally {
            loading = false;
        }
    }

    static synchronized boolean loaded() {
        return loaded;
    }

    /** 加载失败的原因之一：被 crash guard / 「原生加速」开关拦下来了。 */
    static synchronized boolean blockedByGuard() {
        return !loaded && !NativeGuard.allowed();
    }

    /** 最后一次失败的完整错误串（成功则为空串）。selfcheck 原样写出来。 */
    static synchronized String err() {
        return lastErr;
    }

    static synchronized String path() {
        return loaded ? lastPath : "";
    }

    static synchronized int attempts() {
        return attempts;
    }

    static synchronized int okAttempt() {
        return okAttempt;
    }

    /** 自检用的一行：`loaded=true path=… attempt=1/1` 或 `loaded=false attempts=3 err=…`。 */
    static synchronized String describe() {
        if (loaded) {
            return "loaded=true path=" + lastPath + " size=" + new File(lastPath).length()
                    + " abi=" + abi() + " attempt=" + okAttempt + "/" + attempts;
        }
        if (!NativeGuard.allowed()) {
            // crash guard / 开关关掉时，这一行要自己说明白，别让人以为是加载失败
            return "loaded=false (native disabled by crash guard: " + NativeGuard.reason() + ")";
        }
        return "loaded=false attempts=" + attempts + " abi=" + abi()
                + " dir=" + "files/lib" + " err=" + (lastErr.isEmpty() ? "(not tried yet)" : lastErr);
    }

    // ---------------- 解包 ----------------

    /** 主 ABI（arm64-v8a / armeabi-v7a / …）。 */
    static String abi() {
        try {
            String[] a = Build.SUPPORTED_ABIS;
            if (a != null && a.length > 0 && a[0] != null && !a[0].isEmpty()) return a[0];
        } catch (Throwable ignored) {
        }
        return "arm64-v8a";
    }

    static File libDir(Context ctx) {
        return new File(ctx.getFilesDir(), "lib");
    }

    /**
     * 把 {@code assets/elneagate/lib<abi>/lib<name>.so} 解到 {@code files/lib/}，返回绝对路径。
     * 已经解过且大小一致就直接返回。找不到 asset 返回 null（绝不抛）。
     */
    static String ensure(Context ctx, String name) {
        File dir = libDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            if (!dir.isDirectory()) return null;
        }
        File out = new File(dir, "lib" + name + ".so");
        String asset = "elneagate/lib" + abi() + "/lib" + name + ".so";
        try {
            long want = -1;
            AssetFileDescriptor fd = null;
            try {
                fd = ctx.getAssets().openFd(asset);
                want = fd.getLength();
            } finally {
                if (fd != null) fd.close();
            }
            if (out.isFile() && out.length() == want && want > 0) return out.getAbsolutePath();
            InputStream in = ctx.getAssets().open(asset);
            try {
                File tmp = new File(dir, "lib" + name + ".so.tmp");
                FileOutputStream fo = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[65536];
                    int k;
                    while ((k = in.read(buf)) > 0) fo.write(buf, 0, k);
                    fo.getFD().sync();
                } finally {
                    fo.close();
                }
                if (out.exists() && !out.delete()) { /* rename 会覆盖 */ }
                if (!tmp.renameTo(out)) throw new IOException("rename " + tmp + " -> " + out);
            } finally {
                in.close();
            }
            // 解包后补上可执行位（游戏私有目录下 dlopen 需要能 mmap；多数 ROM 默认 600 也行，
            // 这里显式 setExecutable 只为避免个别 ROM 的 seccomp/mount 组合）。
            try {
                out.setReadable(true, false);
                out.setExecutable(true, false);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "unpacked " + asset + " -> " + out + " (" + out.length() + " B)");
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "unpack " + asset + " failed: " + t);
            return null;
        }
    }

    /** 把 Throwable 变一行：`类名: message | cause: 类名: message`（dlopen 的真话在 cause 里）。 */
    static String describe(String what, Throwable t) {
        StringBuilder b = new StringBuilder(what).append(" -> ").append(t.getClass().getName());
        if (t.getMessage() != null) b.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        int guard = 0;
        while (c != null && guard++ < 4) {
            b.append(" | cause ").append(c.getClass().getName());
            if (c.getMessage() != null) b.append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return b.toString();
    }
}
