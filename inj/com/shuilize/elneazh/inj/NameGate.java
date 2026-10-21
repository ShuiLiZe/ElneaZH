package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传前名字闸门的 Java 侧外壳。
 *
 * <p>真正的挂钩在 native 侧（{@code libelneagate.so}）：运行时把
 * {@code Assembly-CSharp::Cipher::EncryptRJ256(string)} 挂上，在它加密之前把明文里的中文名换成
 * 固定拼音。见 {@code native/elneagate/elneagate.c} 的注释与 HANDOFF。
 *
 * <p>本类只做三件事：
 * <ol>
 *   <li>把 {@code libelneagate.so} 从 assets 解到私有目录再 {@code System.load}（游戏进程里没法
 *       直接从 base.apk 的 lib 目录加载我们后塞进去的 .so，解出来最稳）；</li>
 *   <li>把「中文名 → 拼音」整张表（names.tsv 的译文 + 玩家别名）灌给 native，保证与 Java 侧
 *       {@link NameFix}/{@link Pinyin} 用的是同一份数据；</li>
 *   <li>反复尝试挂载（游戏 main 比 ContentProvider 起得晚，libil2cpp 可能还没加载）。</li>
 * </ol>
 *
 * <p>日志统一打 {@code ElneaGate}，方便真机 {@code adb logcat -s ElneaGate} 一条命令确认。
 */
final class NameGate {
    private static final String TAG = "ElneaGate";
    private static final String LIB = "elneagate";

    private static boolean loaded;
    private static volatile int installResult = Integer.MIN_VALUE;

    private NameGate() {}

    // ---------------- native 声明 ----------------

    private static native int nativeInstall();

    private static native int nativeSetNames(String table);

    private static native int[] nativeStats();

    private static native String nativeFilterJson(String plain);

    private static native String nativeFilterBlob(String b64);

    private static native boolean nativeHooked();

    /**
     * 自检用：native 自己解释「为什么没挂上」。
     *
     * <p>返回一行 {@code code=… stage=… lib=… verify=… first_mismatch=… actual=… expect=…}；
     * {@code code[0]} 与 nativeInstall 的返回码一致。旧 .so 没有这个符号时返回 null，
     * 不影响任何逻辑（{@link #diag()} 吞掉 UnsatisfiedLinkError）。
     */
    private static native String nativeDiag(int[] code);

    // ---------------- 加载 ----------------

    /**
     * 加载 {@code libelneagate.so}。真正的加载在 {@link NativeLoad}（两个闸门共用一份），
     * 失败**不锁死**：只要还有重试窗口，下一次调用会重新解包 + 重新 {@code System.load}。
     *
     * <p>真机事故就是这一步：旧代码 {@code Libs.ensure()} 解出 .so 之后调的是
     * {@code System.loadLibrary("elneagate")}，linker 去 APK 的 lib 目录找（那里没有这个 .so），
     * 必然 UnsatisfiedLinkError，而且 {@code loadTried} 一次性开关让整个进程再也不重试 ——
     * selfcheck 里于是永远 `native: not loaded`、logcat 一条闸门日志都没有。
     */
    static synchronized boolean load(Context ctx) {
        if (loaded) return true;
        if (NativeLoad.tryLoad(ctx)) {
            loaded = true;
        }
        return loaded;
    }

    static boolean loaded() {
        return loaded;
    }

    /** 自检用：加载状态 + 上次失败的错误串（含 dlopen 原文）。 */
    static String loadState() {
        return NativeLoad.describe();
    }

    // ---------------- 名字表 ----------------

    /**
     * 把「中文 \t 拼音」整表灌进 native。数据来源与 Java 侧完全一致：
     * <ul>
     *   <li>{@code assets/elneazh/data/names.tsv} 的译文（NPC 中文名 → 英文原文）；</li>
     *   <li>{@code SharedPreferences elneazh_names} 里的玩家别名（中文 → 拼音）；</li>
     *   <li>每次 {@link NameFix#ascii} 新增的别名（见 {@link #remember}）。</li>
     * </ul>
     */
    static synchronized void pushNames(Context ctx) {
        if (ctx == null) return;
        if (!load(ctx)) return;
        Map<String, String> table = new HashMap<>();
        try {
            for (Map.Entry<String, String> e : NameFix.pairs()) table.put(e.getKey(), e.getValue());
        } catch (Throwable t) {
            Log.w(TAG, "pair snapshot failed", t);
        }
        StringBuilder b = new StringBuilder(table.size() * 12 + 64);
        for (Map.Entry<String, String> e : table.entrySet()) {
            b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try {
            int n = nativeSetNames(b.toString());
            Log.i(TAG, "names pushed: " + n);
        } catch (Throwable t) {
            Log.w(TAG, "pushNames failed: " + t);
        }
    }

    /** 玩家新取名（或新出现的 NPC 中文名）时补进 native 表。 */
    static synchronized void remember(Context ctx, String zh, String ascii) {
        if (ctx == null || zh == null || ascii == null || zh.isEmpty() || ascii.isEmpty()) return;
        if (!load(ctx)) return;
        try {
            nativeSetNames(zh + "\t" + ascii + "\n" + currentTable(ctx));
        } catch (Throwable t) {
            Log.w(TAG, "remember failed: " + t);
        }
    }

    private static String currentTable(Context ctx) {
        StringBuilder b = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : NameFix.pairs()) {
                b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
        } catch (Throwable ignored) {
        }
        return b.toString();
    }

    // ---------------- 挂载 ----------------

    /** 后台轮询上限：40 × 500ms = 20s。到点就放弃，游戏照常跑（Java 层兜底仍在）。 */
    private static final int MAX_TRIES = 40;
    private static final long RETRY_MS = 500;

    /** 安装线程是否在跑；在跑时再调 {@link #install} 只做一次幂等 push，不重复起线程。 */
    private static volatile Thread worker;

    /**
     * **立刻返回**，真正的挂载在后台线程 {@code ElneaGate-install} 里做。
     *
     * <p>真机黑屏事故的直接来源就是旧版这里：它起完后台线程后 `done.await()` **同步等**，
     * 最长 20s；而它的调用方是 {@code ZhInit.onCreate}（ContentProvider，进程一启动就跑在
     * 主线程上的那个线程）。libil2cpp 那时还没 dlopen，于是整个启动被按住 20 秒，
     * 用户看到的就是「黑屏，一会儿后进程退出」（Unity 看门狗 / ANR），
     * 而且 logcat 里连一条闸门日志都没有 —— 因为进程在打日志之前就被掐了。
     *
     * <p>现在的约定（主线程只做 O(1)）：
     * <ul>
     *   <li>本方法在调用线程上只做「起线程」这一件事，绝不 sleep / 绝不 await / 绝不 join；</li>
     *   <li>加载 .so、灌名字表、解析 il2cpp、写 hook 全在后台线程里，重试用
     *       {@link #MAX_TRIES} 限次、{@link #RETRY_MS} 间隔，到点放弃；</li>
     *   <li>返回值只代表「上次已知结果」，调用方不得据此等待。</li>
     * </ul>
     *
     * @return 上次已知的结果码（后台还在装时可能是 {@link Integer#MIN_VALUE}）
     */
    static int install(final Context ctx, int tries) {
        if (!NativeLoad.ready()) {
            // 加载本身也可能慢（解包 + System.load）；同样挪到后台，主线程不等。
            startWorker(ctx);
            return -100;
        }
        startWorker(ctx);
        return installResult;
    }

    /** 起（或复用）后台安装线程。本方法不做任何阻塞操作。 */
    private static void startWorker(final Context ctx) {
        Thread cur = worker;
        if (cur != null && cur.isAlive()) {
            // 已经在装：只补一次幂等的名字表推送，不重复起线程、不等待
            try { pushNames(ctx); } catch (Throwable ignored) {}
            return;
        }
        final Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                Log.i(TAG, "installer thread started (" + Thread.currentThread().getName()
                        + "/" + android.os.Process.myTid() + ")");
                int last = Integer.MIN_VALUE;
                try {
                    Log.i(TAG, "installer: loading .so (background)");
                    if (!load(ctx)) {
                        installResult = last = -100;
                        Log.w(TAG, "installer: .so not loaded: " + NativeLoad.describe());
                    } else {
                        Log.i(TAG, "installer: pushing names table");
                        pushNames(ctx);
                        for (int i = 0; i < MAX_TRIES; i++) {
                            long s0 = System.currentTimeMillis();
                            int r = nativeInstall();
                            last = r;
                            Log.i(TAG, "installer: nativeInstall try " + (i + 1) + "/" + MAX_TRIES
                                    + " -> " + r + " (" + (System.currentTimeMillis() - s0) + "ms)");
                            if (r == 0) break;
                            try {
                                Thread.sleep(RETRY_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "installer failed", e);
                } finally {
                    installResult = last;
                    worker = null;
                    long ms = System.currentTimeMillis() - t0;
                    Log.i(TAG, "installer thread exit: result=" + installResult + " in " + ms + "ms");
                    if (installResult != 0) {
                        Log.w(TAG, "not hooked (code " + installResult + "): " + diag()
                                + "; game runs unchanged");
                    }
                }
            }
        }, "ElneaGate-install");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 等安装结束**只给自检/测试用**：绝不在主线程或 Unity 线程上调。 */
    static int awaitInstall(long timeoutMs) {
        Thread t = worker;
        if (t == null) return installResult;
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
        return installResult;
    }

    static boolean hooked() {
        if (!loaded) return false;
        try {
            return nativeHooked();
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code [替换次数, 调用次数, 是否已挂, 表大小]}；未加载返回 null。 */
    static int[] stats() {
        if (!loaded) return null;
        try {
            return nativeStats();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 自检用 ----------------

    /**
     * native 侧对「闸门为什么没挂上」的自述；未加载 / 旧 .so 没有该符号时返回一句人话。
     */
    static String diag() {
        if (!loaded) return "code=" + installResult + " (native not loaded: " + NativeLoad.describe() + ")";
        try {
            int[] code = new int[1];
            String s = nativeDiag(code);
            return s == null ? "code=" + installResult + " (nativeDiag unavailable)" : s;
        } catch (Throwable t) {
            return "code=" + installResult + " (nativeDiag unavailable: " + t.getClass().getSimpleName() + ")";
        }
    }

    static String filterJson(String plain) {
        try {
            return nativeFilterJson(plain);
        } catch (Throwable t) {
            return null;
        }
    }

    static String filterBlob(String b64) {
        try {
            return nativeFilterBlob(b64);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- assets 里的 .so 解包（已合并到 NativeLoad） ----------------

    /** 兼容旧调用点：解包并返回绝对路径。加载本身见 {@link NativeLoad}。 */
    static final class Libs {
        private Libs() {}

        static String ensure(Context ctx, String name) {
            return NativeLoad.ensure(ctx, name);
        }
    }

    static String describe() {
        int[] s = stats();
        if (s == null) return "native: not loaded (" + NativeLoad.describe() + ")";
        List<String> parts = new ArrayList<>();
        parts.add("hooked=" + (s[2] != 0));
        parts.add("rewrites=" + s[0]);
        parts.add("calls=" + s[1]);
        parts.add("table=" + s[3]);
        parts.add("diag{" + diag() + "}");
        return "native: " + parts;
    }
}
