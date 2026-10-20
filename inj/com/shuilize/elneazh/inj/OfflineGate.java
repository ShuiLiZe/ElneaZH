package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单机模式的 native 侧闸门（Java 外壳）。
 *
 * <p>和名字闸门 {@link NameGate} 共用同一个 {@code libelneagate.so}，但挂的是完全不同的东西：
 * 用 il2cpp 运行时 API（{@code il2cpp_domain_get} → {@code il2cpp_domain_get_assemblies} →
 * {@code il2cpp_assembly_get_image} → {@code il2cpp_class_from_name} →
 * {@code il2cpp_class_get_method_from_name}）**按名字**把游戏自己的方法解析出来，再挂 inline hook：
 *
 * <ol>
 *   <li><b>面板入口（target 1）</b>：{@code AddXxxPanel} 这一族工厂方法。单机时直接返回 NULL，
 *       入口不创建 → 商店 / 邮箱 / 好友 / PVP / 排行 / 公告 / 活动 / 广告等面板打不开。
 *       这比「打开再关掉」安全：不会有一个半初始化的面板实例留在游戏里。</li>
 *   <li><b>请求发送（target 2）</b>：{@code HttpSystem.httpConnect} / {@code AddHttpQueue} /
 *       {@code connectWWW}。单机时不发，直接返回「失败」，让游戏自己走它原本的通信失败分支
 *       （游戏里有 {@code OfflineData}/{@code IsOfflineActive} 与 {@code errorOfflineCheckHttp}
 *       那一套离线状态，交给它处理）。**不伪造任何服务器回包。**</li>
 * </ol>
 *
 * <p>为什么必须走 native：游戏的 UI 与网络都在 il2cpp 里，Java 层既反射不到
 * {@code MenuCell.EnableFlag}，也没有 JNI 出口（详见 HANDOFF）。Java 侧的
 * {@link NetBlocker}（URLStreamHandlerFactory）与 {@link NetGuard} 保留作兜底。
 *
 * <p>几条硬约束：
 * <ul>
 *   <li>解析不到就跳过并打日志，绝不崩、绝不阻断游戏启动；</li>
 *   <li>开关状态每次 hook 都现读 native 里的缓存值，切换开关不用重进游戏；</li>
 *   <li>日志统一打 {@code ElneaOffline}，真机一条 {@code adb logcat -s ElneaOffline} 就能看。</li>
 * </ul>
 */
final class OfflineGate {
    private static final String TAG = "ElneaOffline";
    private static final String LIB = "elneagate";

    private static boolean loaded;
    private static boolean loadTried;
    private static volatile int panelResult = Integer.MIN_VALUE;
    private static volatile int httpResult = Integer.MIN_VALUE;

    private OfflineGate() {}

    // ---------------- native 声明 ----------------

    private static native int nativeInstall(int target, String names);

    private static native int nativeUninstall();

    private static native int nativePush(String state);

    private static native int[] nativeStats();

    private static native int nativeProbe();

    private static native boolean nativeResolvable(String ns, String klass, String method);

    // ---------------- 挂哪些方法 ----------------

    /**
     * 面板入口白名单。名字全部来自 global-metadata.dat 里 {@code Add*Panel} 那一族工厂方法，
     * 每行 {@code 命名空间 \t 类 \t 方法}；方法头字段表示「名字确定、参数个数待试」，
     * native 侧会按 0..6 个参数依次尝试解析。
     *
     * <p>{@code target} 用 {@link #TARGET_PANEL}。
     */
    static String panelTable() {
        StringBuilder b = new StringBuilder(4096);
        // 商店 / 内购 / 付费
        addEntry(b, "Shop", "AddShopPanel");
        addEntry(b, "Shop", "AddShopMenuPanel");
        addEntry(b, "Shop", "AddShopBuyConfirmPanel");
        addEntry(b, "Shop", "AddShopBuyPreviewConfirmPanel");
        addEntry(b, "Shop", "AddDropItemBuyPanel");
        addEntry(b, "Shop", "AddGemBuyListPanel");
        addEntry(b, "Shop", "AddTradeShopListPanel");
        addEntry(b, "Shop", "AddPremiumPanel");
        addEntry(b, "Shop", "AddGachaPanel");
        addEntry(b, "Shop", "AddGachaEffectPanel");
        addEntry(b, "Shop", "AddCostumeGachaPanel");
        addEntry(b, "Shop", "AddFightGachaPanel");
        addEntry(b, "Shop", "AddInteriorGachaPanel");
        addEntry(b, "Charges", "AddChargesShopListPanel");
        // 邮箱 / 礼物 / 序列码
        addEntry(b, "Mail", "AddMailPanel");
        addEntry(b, "Mail", "AddMailDetailsPanel");
        addEntry(b, "Mail", "AddMailInputPanel");
        addEntry(b, "Info", "AddSerialCodeInputPanel");
        // 好友 / 结盟 / PVP / 对战
        addEntry(b, "Friend", "AddFriendListPanel");
        addEntry(b, "Alliance", "AddAlliancePanel");
        addEntry(b, "Alliance", "AddTouchAlliancePanel");
        addEntry(b, "Pvp", "AddPvpPreparePanel");
        addEntry(b, "Pvp", "AddPvpResultPanel");
        addEntry(b, "Match", "AddMatchPreparePanel");
        addEntry(b, "Tournament", "AddTournamentPanel");
        addEntry(b, "Tournament", "AddVoteListPanel");
        // 排行 / 联赛
        addEntry(b, "Ranking", "AddKingdomRankingPanel");
        addEntry(b, "Ranking", "AddGunnerRankingPanel");
        addEntry(b, "Ranking", "AddFishingRankingListPanel");
        addEntry(b, "Ranking", "AddLimitedEventRankingListPanel");
        addEntry(b, "League", "AddLeaguePanel");
        // 公告 / 活动（服务器下发的）
        addEntry(b, "Notice", "AddNoticeInfoPanel");
        addEntry(b, "Notice", "AddNoticeDetailsPanel");
        addEntry(b, "Event", "AddEventPanel");
        addEntry(b, "Event", "AddLimitedEventPanel");
        addEntry(b, "Event", "AddLimitedEventPortalPanel");
        addEntry(b, "Event", "AddGroupEventListPanel");
        // 广告 / 网页 / 账号 / 迁移 / 举报
        addEntry(b, "Ad", "AddAdPanel");
        addEntry(b, "Web", "AddWebPanel");
        addEntry(b, "Account", "AddAccountSettingPanel");
        addEntry(b, "Account", "AddAccountInputPanel");
        addEntry(b, "Migrant", "AddMigrantPasswordIssueListPanel");
        addEntry(b, "Report", "AddReportPanel");
        return b.toString();
    }

    /**
     * 请求发送白名单（{@code HttpSystem} 的成员，取自 global-metadata 的类成员表）。
     * 只挑「真正把请求发出去」的那几个；解析不到的自动跳过。
     */
    static String httpTable() {
        StringBuilder b = new StringBuilder(512);
        addEntry(b, "HttpSystem", "httpConnect");
        addEntry(b, "HttpSystem", "connectWWW");
        addEntry(b, "HttpSystem", "AddHttpQueue");
        return b.toString();
    }

    /** 命名空间写 {@code *} 表示「不限」：native 侧会遍历所有 assembly 找同名类。 */
    private static void addEntry(StringBuilder b, String cls, String method) {
        b.append('*').append('\t').append(cls).append('\t').append(method).append('\n');
    }

    static final int TARGET_PANEL = 1;
    static final int TARGET_HTTP = 2;

    // ---------------- 加载 ----------------

    static synchronized boolean load(Context ctx) {
        if (loaded) return true;
        if (loadTried) return false;
        loadTried = true;
        try {
            unpack(ctx, LIB);   // 和名字闸门同一套解包，但本类自己带一份，测试环境里不依赖 NameGate
            System.loadLibrary(LIB);
            loaded = true;
            Log.i(TAG, "libelneagate.so loaded (offline gate)");
        } catch (Throwable t) {
            Log.w(TAG, "libelneagate.so not loaded (offline gate): " + t);
        }
        return loaded;
    }

    static boolean loaded() {
        return loaded;
    }

    // ---------------- 开关状态 ----------------

    /**
     * 把当前开关状态推给 native（{@code "1"} 开 / {@code "0"} 关）。
     * hook 每次都现读这个值，所以玩家在弹窗里开关之后立刻生效，不用重进游戏。
     *
     * @return 是否推送成功（native 没加载时返回 false，调用方忽略即可）
     */
    static boolean push(Context ctx) {
        if (ctx == null) return false;
        boolean on;
        try {
            on = Offline.isOn(ctx);
        } catch (Throwable t) {
            on = false;
        }
        return push(on);
    }

    static synchronized boolean push(boolean on) {
        if (!loaded) return false;
        try {
            return nativePush(on ? "1" : "0") != 0;
        } catch (Throwable t) {
            Log.w(TAG, "push failed: " + t);
            return false;
        }
    }

    // ---------------- 安装 ----------------

    /**
     * 安装两族 hook。放后台线程并最多等 {@code waitMs} 毫秒：il2cpp 比 ContentProvider 起得晚，
     * 早到的调用会拿不到 API 指针，需要重试。
     *
     * <p>本方法**绝不抛异常**，也绝不让游戏的启动等太久（最长一次等 {@code waitMs}）。
     *
     * @return 两族合计挂上的方法数；0 或负数表示这次没成（日志里有原因码）
     */
    static int install(final Context ctx, int waitMs) {
        if (!load(ctx)) return -100;
        push(ctx);
        final AtomicInteger panel = new AtomicInteger(Integer.MIN_VALUE);
        final AtomicInteger http = new AtomicInteger(Integer.MIN_VALUE);
        final CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    panel.set(installRetry(TARGET_PANEL, panelTable()));
                    http.set(installRetry(TARGET_HTTP, httpTable()));
                } catch (Throwable e) {
                    Log.w(TAG, "install failed", e);
                } finally {
                    done.countDown();
                }
            }
        }, "ElneaOffline-install");
        t.setDaemon(true);
        t.start();
        try {
            done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
        panelResult = panel.get();
        httpResult = http.get();
        int total = Math.max(0, panelResult) + Math.max(0, httpResult);
        Log.i(TAG, "install done: panel=" + panelResult + " http=" + httpResult
                + " total=" + total + " stats=" + describe());
        return total;
    }

    /** 反复重试直到拿到正的挂载数（说明 il2cpp 已经起来、名字也解析上了）。 */
    private static int installRetry(int target, String table) {
        int last = Integer.MIN_VALUE;
        for (int i = 0; i < 60; i++) {
            try {
                last = nativeInstall(target, table);
            } catch (Throwable t) {
                Log.w(TAG, "nativeInstall(" + target + ") threw: " + t);
                return -102;
            }
            if (last > 0) return last;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    /** 卸掉所有 hook（关掉单机模式时也可以用；正常路径靠开关值就够，不必卸）。 */
    static int uninstall() {
        if (!loaded) return -1;
        try {
            return nativeUninstall();
        } catch (Throwable t) {
            Log.w(TAG, "uninstall failed: " + t);
            return -2;
        }
    }

    /** 运行时探测：我们依赖的 il2cpp API 能 dlsym 到几个（期望 8）。 */
    static int probe() {
        if (!loaded) return -1;
        try {
            return nativeProbe();
        } catch (Throwable t) {
            return -2;
        }
    }

    /** 自检用：某个方法现在能不能按名字解析到（不调用，只解析）。 */
    static boolean resolvable(String ns, String klass, String method) {
        if (!loaded) return false;
        try {
            return nativeResolvable(ns == null ? "*" : ns, klass, method);
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code [已挂数量, 面板拦截次数, 请求拦截次数, 解析失败数]}；未加载返回 null。 */
    static int[] stats() {
        if (!loaded) return null;
        try {
            return nativeStats();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- assets 里的 .so 解包 ----------------

    /**
     * 把 {@code assets/elneagate/lib<abi>/lib<name>.so} 解到私有目录再加载。
     * 逻辑与 {@link NameGate.Libs#ensure} 一致（同一个 .so，同一份 assets），
     * 这里单独放一份是为了让本类能在纯 JDK 环境里被测（不牵扯 NameGate 的其它依赖）。
     */
    static String unpack(Context ctx, String name) {
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

    static String describe() {
        int[] s = stats();
        if (s == null) return "native: not loaded";
        List<String> parts = new ArrayList<>();
        parts.add("hooked=" + s[0]);
        parts.add("panel=" + s[1]);
        parts.add("http=" + s[2]);
        parts.add("miss=" + s[3]);
        return "native: " + parts;
    }

    /** 给 SelfCheck / 支援弹窗用的一句话现状（不含任何路径与账号信息）。 */
    static String summary() {
        if (!loaded) {
            return "原生闸门未加载（仅 Java 层拦截生效）";
        }
        int[] s = stats();
        if (s == null) return "原生闸门未就绪";
        return "原生闸门已挂钩 " + s[0] + " 个入口，已挡面板 " + s[1] + " 次、请求 " + s[2] + " 次";
    }
}
