package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static boolean loadTried;
    private static volatile int installResult = Integer.MIN_VALUE;

    private NameGate() {}

    // ---------------- native 声明 ----------------

    private static native int nativeInstall();

    private static native int nativeSetNames(String table);

    private static native int[] nativeStats();

    private static native String nativeFilterJson(String plain);

    private static native String nativeFilterBlob(String b64);

    private static native boolean nativeHooked();

    // ---------------- 加载 ----------------

    static synchronized boolean load(Context ctx) {
        if (loaded) return true;
        if (loadTried) return false;
        loadTried = true;
        try {
            Libs.ensure(ctx, LIB);
            System.loadLibrary(LIB);
            loaded = true;
            Log.i(TAG, "libelneagate.so loaded");
        } catch (Throwable t) {
            Log.w(TAG, "libelneagate.so not loaded: " + t);
        }
        return loaded;
    }

    static boolean loaded() {
        return loaded;
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

    /**
     * 依次尝试挂载，最多 {@code tries} 次（每次间隔 500ms）。进程里可以多次调用，成功后不再重试。
     *
     * @return 0 = 已挂上；其它 = 失败原因码（见 nativeInstall）
     */
    static int install(final Context ctx, int tries) {
        if (!load(ctx)) return -100;
        pushNames(ctx);
        final AtomicInteger last = new AtomicInteger(Integer.MIN_VALUE);
        final CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    for (int i = 0; i < 40; i++) {
                        int r = nativeInstall();
                        last.set(r);
                        if (r == 0) break;
                        Thread.sleep(500);
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "install failed", e);
                } finally {
                    done.countDown();
                }
            }
        }, "ElneaGate-install");
        t.setDaemon(true);
        t.start();
        try {
            done.await();
        } catch (InterruptedException ignored) {
        }
        installResult = last.get();
        if (installResult != 0) {
            Log.w(TAG, "not hooked (code " + installResult + "); game runs unchanged");
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

    // ---------------- assets 里的 .so 解包 ----------------

    static final class Libs {
        private Libs() {}

        /**
         * 把 {@code assets/elneagate/lib<abi>/lib<name>.so} 解到私有目录，返回绝对路径；
         * 已经解过且大小一致就直接返回。找不到返回 null。
         */
        static String ensure(Context ctx, String name) {
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            java.io.File dir = new java.io.File(ctx.getFilesDir(), "lib");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            java.io.File out = new java.io.File(dir, "lib" + name + ".so");
            String asset = "elneagate/lib" + abi + "/lib" + name + ".so";
            try {
                long want = -1;
                android.content.res.AssetFileDescriptor fd = null;
                try {
                    fd = ctx.getAssets().openFd(asset);
                    want = fd.getLength();
                } finally {
                    if (fd != null) fd.close();
                }
                if (out.isFile() && out.length() == want && want > 0) return out.getAbsolutePath();
                java.io.InputStream in = ctx.getAssets().open(asset);
                try {
                    java.io.File tmp = new java.io.File(dir, "lib" + name + ".so.tmp");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
                    try {
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) fo.write(buf, 0, k);
                        fo.getFD().sync();
                    } finally {
                        fo.close();
                    }
                    if (out.exists() && !out.delete()) { /* 覆盖 rename */ }
                    if (!tmp.renameTo(out)) throw new java.io.IOException("rename " + tmp);
                } finally {
                    in.close();
                }
                Log.i(TAG, "unpacked " + asset + " -> " + out + " (" + out.length() + " B)");
                return out.getAbsolutePath();
            } catch (Throwable t) {
                Log.w(TAG, "unpack " + asset + " failed: " + t);
                return null;
            }
        }
    }

    static String describe() {
        int[] s = stats();
        if (s == null) return "native: not loaded";
        List<String> parts = new ArrayList<>();
        parts.add("hooked=" + (s[2] != 0));
        parts.add("rewrites=" + s[0]);
        parts.add("calls=" + s[1]);
        parts.add("table=" + s[3]);
        return "native: " + parts;
    }
}
