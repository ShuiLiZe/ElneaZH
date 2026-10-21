package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * native 启动保护（crash guard）：连续两轮启动没「活下来」就自动不加载 native。
 *
 * <p>为什么需要它 —— 真机事故（2026-10-21 17:13 selfcheck，OnePlus Android 16 arm64）：
 * 单机开、单机<b>关</b>都黑屏，过一会儿进程退出；crash 缓冲区没有新 tombstone，
 * 没有 AndroidRuntime 异常，{@code ElneaGate}/{@code ElneaOffline} 一条日志都没有。
 * 这不是崩溃，是<b>主线程被卡住</b>（ANR / Unity 看门狗杀进程）。用户没有任何自愈手段：
 * 卸了重装才会好。所以必须有这一层。
 *
 * <p>规则（本轮按用户明确要求实现）：
 * <ol>
 *   <li>进程起来、决定加载 native 之前，写一个标记文件 {@code files/elneazh_native_boot}
 *       （pid / 时间 / 包名 / 轮次）；</li>
 *   <li>进游戏主界面（{@code About} 首启弹窗出现后，即 Unity 已经跑到 UI 线程活着的阶段）
 *       或运行满 {@value #SURVIVE_MS} 毫秒，清掉标记；</li>
 *   <li>若连续 <b>2</b> 次启动标记都没被清掉（= 上上轮 + 上一轮都没活下来），
 *       下一次启动直接 <b>不加载 native</b>：拼音闸门（{@code NameGate}）与单机 native 闸门
 *       （{@code OfflineGate}）全部跳过，Java 层兜底（{@code NetGuard} / {@code NetBlocker}）照旧；</li>
 *   <li>selfcheck 里写明 {@code native disabled by crash guard}，让人一眼看到为什么没 native；</li>
 *   <li>Support 里有开关「原生加速（实验）」，默认开；关掉等价于永远不加载 native
 *       （给玩家一个「这机器上 native 就是不行」的硬开关）。</li>
 * </ol>
 *
 * <p>本类**绝不阻塞**：所有读写都在调用线程上做 O(1) 的小文件操作（&lt; 200 字节），
 * 并且对外只用 {@code synchronized} 包一次；不在主线程上重试、不 sleep。
 */
final class NativeGuard {
    private static final String TAG = "ElneaGate";

    /** 标记文件：存在 = 「上一轮启动 native 加载了，但进程没活到主界面 / 60s」。 */
    static final String MARKER = "elneazh_native_boot";
    /** 连击多少次启动失败就停用 native。 */
    static final int MAX_STRIKES = 2;
    /** 活过这么久就算「这一轮没坏」：运行满就清标记。 */
    static final long SURVIVE_MS = 60_000;
    /** 实际使用的存活阈值（单测会临时改小）。 */
    private static long surviveMs = SURVIVE_MS;
    /** Support 里「原生加速（实验）」的开关键。 */
    static final String PREF = "elneazh_settings";
    static final String KEY_NATIVE = "native_boost";

    private static Context app;
    /** 连续失败的轮次（读标记文件里累积的值）。 */
    private static int strikes = -1;
    private static boolean allowed = true;
    private static String reason = "";
    private static volatile boolean cleared;

    private NativeGuard() {}

    // ---------------------------------------------------------------- 启动

    /**
     * 进程启动时调一次（{@code ZhInit.onCreate} 最前面）。做三件事，全是 O(1) 文件操作：
     * 读上次的标记 → 累计连击 → 写本轮的标记。
     */
    static synchronized void boot(Context ctx) {
        if (ctx == null) return;
        app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        strikes = readStrikes();
        cleared = false;
        boolean boost = boostEnabled(app);
        if (!boost) {
            allowed = false;
            reason = "native off (Native Boost switch is off in Support)";
        } else if (strikes >= MAX_STRIKES) {
            allowed = false;
            reason = "native disabled by crash guard (" + strikes + " consecutive boots did not survive)";
        } else {
            allowed = true;
            reason = "native allowed (strikes=" + strikes + ")";
        }
        writeMarker();
        Log.i(TAG, "boot guard: allowed=" + allowed + " strikes=" + strikes + "/" + MAX_STRIKES
                + " boost=" + boost + " pid=" + android.os.Process.myPid() + " — " + reason);
        // 把结论也推给 native（它只读这个标记，不自己判断），安装窗口一起传过去。
        if (NativeLoad.ready()) {
            try { NativeLoad.nativeGuard(allowed ? 1 : 0, (int) OfflineGate.windowMs()); }
            catch (Throwable ignored) {}
        }
        scheduleSurvive();
    }

    /** 这一轮允许加载 native 吗。启动后随时可问。 */
    static synchronized boolean allowed() {
        return allowed;
    }

    /** 连续失败轮次（给 selfcheck 写）。 */
    static synchronized int strikes() {
        return Math.max(0, strikes);
    }

    /** 为什么被停用 / 为什么允许（给 selfcheck 与 logcat 用）。 */
    static synchronized String reason() {
        return reason;
    }

    static synchronized boolean clearedThisBoot() {
        return cleared;
    }

    // ---------------------------------------------------------------- 标记文件

    private static File markerFile() {
        return new File(app.getFilesDir(), MARKER);
    }

    /**
     * 标记文件内容：第一行 = 累积连击数，第二行 = 上一轮写的说明。
     * 只写小文件、不用 SharedPreferences（prefs 会被 Backup 一起备份/恢复，这个不该跟着走）。
     */
    private static int readStrikes() {
        try {
            File f = markerFile();
            if (!f.isFile()) return 0;
            byte[] b = new byte[256];
            int n;
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                n = in.read(b);
            }
            if (n <= 0) return 1;      // 文件存在但空了：也算上一轮没清
            String s = new String(b, 0, n, "UTF-8").trim();
            int nl = s.indexOf('\n');
            String first = nl < 0 ? s : s.substring(0, nl);
            int v = Integer.parseInt(first.trim());
            return Math.max(0, Math.min(99, v));
        } catch (Throwable t) {
            return 1;                 // 解析不了 = 上一轮没正常清掉
        }
    }

    private static void writeMarker() {
        if (app == null) return;
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String body = Math.min(strikes + 1, 99) + "\n"
                + when + " pid=" + android.os.Process.myPid()
                + " pkg=" + app.getPackageName()
                + " allowed=" + allowed + "\n";
        try (FileOutputStream o = new FileOutputStream(markerFile())) {
            o.write(body.getBytes("UTF-8"));
            o.getFD().sync();
        } catch (Throwable t) {
            Log.w(TAG, "crash guard: write marker failed: " + t);
        }
    }

    /**
     * 清标记：进主界面 / 运行满 60s 时调。清掉 = 这一轮活下来了，连击归零。
     * 可以重复调（第二次起直接返回）。
     */
    static void markSurvived(String why) {
        synchronized (NativeGuard.class) {
            if (cleared) return;
            cleared = true;
            strikes = 0;
            try {
                File f = markerFile();
                // 删掉最干净；删不掉（某些 ROM 的 fs 怪癖 / 文件被占）就写一份「0 连击」：
                // 下次读出来是 0，效果一样。
                if (f.exists() && !f.delete()) {
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        o.write("0\n".getBytes("UTF-8"));
                    }
                }
                Log.i(TAG, "boot guard: survived (" + why + "), marker cleared, strikes=0");
            } catch (Throwable t) {
                Log.w(TAG, "boot guard: clear marker failed: " + t);
            }
        }
    }

    /** 起一个后台看门狗：{@value #SURVIVE_MS} 后还没进主界面也清掉（进程确实活着）。 */
    private static void scheduleSurvive() {
        final long ms = surviveMs;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                markSurvived("alive " + (ms / 1000) + "s");
            }
        }, "ElneaGate-survive");
        t.setDaemon(true);
        t.start();
    }

    /** 只给测试用：把存活看门狗的等待时间临时改短，免得单测要跑满 60s。 */
    static void surviveAfterForTest(long ms) {
        surviveMs = Math.max(50, ms);
    }

    // ---------------------------------------------------------------- Support 开关

    /** 「原生加速（实验）」默认**开**。 */
    static boolean boostEnabled(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_NATIVE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 切开关。关掉 native 后要重启游戏才彻底生效（.so 一旦 load 进进程就收不回来），
     * 这里把连击与标记一起清掉：用户显式关掉，不该再看到 "crash guard" 的提示。
     */
    static boolean setBoost(Context c, boolean on) {
        boolean ok;
        try {
            ok = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_NATIVE, on).commit();
        } catch (Throwable t) {
            return false;
        }
        if (ok) {
            synchronized (NativeGuard.class) {
                app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
                strikes = 0;
                cleared = true;
                try {
                    File f = markerFile();
                    if (f.exists()) {
                        try (FileOutputStream o = new FileOutputStream(f)) {
                            o.write("0\n".getBytes("UTF-8"));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "native boost -> " + on + " (takes full effect after the game restarts)");
        }
        return ok;
    }

    /** 给 Support / selfcheck 的一行说明。 */
    static String describe() {
        StringBuilder b = new StringBuilder();
        b.append("native boost=").append(app == null ? "?" : (boostEnabled(app) ? "on" : "off"))
                .append(" allowed=").append(allowed)
                .append(" strikes=").append(Math.max(0, strikes)).append('/').append(MAX_STRIKES)
                .append(" survived=").append(cleared);
        if (!reason.isEmpty()) b.append(" — ").append(reason);
        return b.toString();
    }
}
