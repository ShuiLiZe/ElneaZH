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
 * {@code il2cpp_class_get_method_from_name}，必要时再用 {@code il2cpp_object_get_class}）
 * <b>按名字</b>把游戏自己的方法解析出来，再 inline hook。六族钩子的分工见下面 {@code TARGET_*}：
 *
 * <ol>
 *   <li>{@link #TARGET_BUTTON} / {@link #TARGET_GATE}：商店、内购、邮箱、好友、PVP、排行、公告、
 *       活动、广告的入口。单机时入口<b>照常显示</b>，但按钮变灰、面板一造出来就置为不可用
 *       —— 用原生层把界面改掉，不改游戏自己的显隐判断，也不伪造任何服务器内容。</li>
 *   <li>{@link #TARGET_HTTP}：{@code HttpSystem.httpConnect} / {@code AddHttpQueue} /
 *       {@code connectWWW}。单机时不发，直接返回「失败」，让游戏走它自己的通信失败/离线分支
 *       （游戏里有 {@code OfflineData}/{@code IsOfflineActive}、{@code errorOfflineCheckHttp}）。
 *       <b>不伪造任何服务器回包。</b></li>
 *   <li>{@link #TARGET_FLAG} / {@link #TARGET_MASKS}：菜单项旗帜与遮罩，兜底用。</li>
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
    private static volatile int[] lastResults = new int[0];
    private static volatile boolean installedAny;

    private OfflineGate() {}

    // ---------------- native 声明 ----------------

    private static native int nativeInstall(int target, String names);

    private static native int nativeUninstall();

    private static native int nativePush(String state);

    private static native int[] nativeStats();

    private static native int nativeResetStats();

    private static native int nativeProbe();

    private static native boolean nativeResolvable(String ns, String klass, String method);

    private static native boolean nativeClassPresent(String klass);

    // ---------------- 六族钩子 ----------------

    /** 入口不创建（返回 NULL）。只用在「返回 NULL 也安全」的路径上。 */
    static final int TARGET_PANEL = 1;
    /** 请求发送：单机时不发。 */
    static final int TARGET_HTTP = 2;
    /** 面板造出来后立刻置为不可用（PanelEnabled=0）。 */
    static final int TARGET_GATE = 3;
    /** 按钮点击回调：单机时追加 set_interactable(false)。 */
    static final int TARGET_BUTTON = 4;
    /** 菜单项旗帜：EnableFlag=0 / ActiveFlag=null。 */
    static final int TARGET_FLAG = 5;
    /** 遮罩：单机时不加，页面保持可用。 */
    static final int TARGET_MASKS = 6;

    // ---------------- 该挂哪些方法 ----------------

    /**
     * 全部名单，形如 {@code {target, 一次性字符串}}，交给 native 逐行解析。
     *
     * <p>名字全部来自 global-metadata.dat（面板类名、成员名、{@code Add*Panel} 工厂方法族），
     * 每行 {@code 命名空间 \t 类 \t 方法}；命名空间写 {@code *} 表示不限。
     * 解析不到的条目由 native 记一笔日志跳过，不影响其它条目。
     */
    static int[][] groups() {
        return new int[][] {
                { TARGET_BUTTON, 0 }, { TARGET_GATE, 0 }, { TARGET_PANEL, 0 },
                { TARGET_HTTP, 0 }, { TARGET_FLAG, 0 }, { TARGET_MASKS, 0 },
        };
    }

    /** 按 target 取名单。参数 {@code target} 必须是上面六个常量之一。 */
    static String table(int target) {
        switch (target) {
            case TARGET_BUTTON: return buttonTable();
            case TARGET_GATE: return gateTable();
            case TARGET_PANEL: return panelTable();
            case TARGET_HTTP: return httpTable();
            case TARGET_FLAG: return flagTable();
            case TARGET_MASKS: return masksTable();
            default: return "";
        }
    }

    /**
     * 按钮 / 入口的点击回调（{@link #TARGET_BUTTON}）。
     *
     * <p>这些是「点了就打开付费/联网面板」的按钮函数，名字取自 global-metadata.dat 里
     * {@code UI\Menu\InfoMenuPanel}、{@code UI\Shop\*} 等类的成员表。原函数照常执行，
     * 单机时再去把按钮本身设成不可点 —— 游戏自己的显隐判断一概不动。
     */
    static String buttonTable() {
        StringBuilder b = new StringBuilder(2048);
        add(b, "ShopFooterButtonClick");
        add(b, "shopButtonClick");
        add(b, "GiftBoxButtonClick");
        add(b, "boardMenuOpenButtonClick");
        add(b, "BoardMenuOpenButtonClick");
        add(b, "SerialCodeInputButtonClick");
        add(b, "ChargeButtonClick");
        add(b, "chargeButtonClick");
        add(b, "gemBuyButtonClick");
        add(b, "GemShopButtonClick");
        add(b, "buyButtonClick");
        add(b, "BuyButtonClick");
        add(b, "mailButtonClick");
        add(b, "MailButtonClick");
        add(b, "friendButtonClick");
        add(b, "FriendButtonClick");
        add(b, "pvpButtonClick");
        add(b, "PvpButtonClick");
        add(b, "rankingButtonClick");
        add(b, "RankingButtonClick");
        add(b, "noticeButtonClick");
        add(b, "NoticeButtonClick");
        add(b, "whatsNewButtonClick");
        add(b, "WhatsNewButtonClick");
        add(b, "adButtonClick");
        add(b, "AdButtonClick");
        add(b, "premiumButtonClick");
        add(b, "PremiumButtonClick");
        add(b, "gachaButtonClick");
        add(b, "GachaButtonClick");
        add(b, "allianceButtonClick");
        add(b, "AllianceButtonClick");
        add(b, "tournamentButtonClick");
        add(b, "TournamentButtonClick");
        add(b, "accountButtonClick");
        add(b, "AccountButtonClick");
        add(b, "webButtonClick");
        add(b, "WebButtonClick");
        return b.toString();
    }

    /**
     * 面板创建后置灰（{@link #TARGET_GATE}）：这些 {@code Add*Panel} 有返回值、
     * 调用方会立刻使用返回的对象，所以**不能**返回 NULL，改成「造出来但禁用」。
     */
    static String gateTable() {
        StringBuilder b = new StringBuilder(3072);
        // 商店 / 内购 / 付费
        add(b, "AddShopPanel");
        add(b, "AddShopMenuPanel");
        add(b, "AddShopBuyConfirmPanel");
        add(b, "AddShopBuyPreviewConfirmPanel");
        add(b, "AddDropItemBuyPanel");
        add(b, "AddGemBuyListPanel");
        add(b, "AddTradeShopListPanel");
        add(b, "AddChargesShopListPanel");
        add(b, "AddPremiumPanel");
        add(b, "AddGachaPanel");
        add(b, "AddGachaMenuPanel");
        add(b, "AddGachaEffectPanel");
        add(b, "AddCostumeGachaPanel");
        add(b, "AddFightGachaPanel");
        add(b, "AddInteriorGachaPanel");
        // 邮箱 / 礼物 / 序列码
        add(b, "AddMailPanel");
        add(b, "AddMailDetailsPanel");
        add(b, "AddMailInputPanel");
        add(b, "AddSerialCodeInputPanel");
        // 好友 / 结盟 / PVP / 对战
        add(b, "AddFriendListPanel");
        add(b, "AddAlliancePanel");
        add(b, "AddTouchAlliancePanel");
        add(b, "AddPvpPreparePanel");
        add(b, "AddPvpResultPanel");
        add(b, "AddMatchPreparePanel");
        add(b, "AddTournamentPanel");
        add(b, "AddVoteListPanel");
        // 排行 / 联赛
        add(b, "AddKingdomRankingPanel");
        add(b, "AddGunnerRankingPanel");
        add(b, "AddKnightReceiptPanel");
        add(b, "AddGunnerReceiptPanel");
        add(b, "AddFishingRankingListPanel");
        add(b, "AddLimitedEventRankingListPanel");
        add(b, "AddLeaguePanel");
        // 公告 / 活动（服务器下发的内容）
        add(b, "AddNoticeInfoPanel");
        add(b, "AddNoticeDetailsPanel");
        add(b, "AddEventPanel");
        add(b, "AddLimitedEventPanel");
        add(b, "AddLimitedEventPortalPanel");
        add(b, "AddGroupEventListPanel");
        add(b, "AddInfoMenuPanel");
        // 广告 / 网页 / 账号 / 迁移 / 举报
        add(b, "AddAdPanel");
        add(b, "AddWebPanel");
        add(b, "AddAccountSettingPanel");
        add(b, "AddAccountInputPanel");
        add(b, "AddMigrantPasswordIssueListPanel");
        add(b, "AddReportPanel");
        return b.toString();
    }

    /**
     * 入口不创建（{@link #TARGET_PANEL}）：纯「副作用型」的打开入口，返回值没人用，
     * 返回 NULL 只是「没打开」，游戏照常运行。
     */
    static String panelTable() {
        StringBuilder b = new StringBuilder(1024);
        add(b, "OpenShopPanel");
        add(b, "OpenMailPanel");
        add(b, "OpenFriendListPanel");
        add(b, "OpenAlliancePanel");
        add(b, "OpenPvpPanel");
        add(b, "OpenRankingPanel");
        add(b, "OpenNoticePanel");
        add(b, "OpenEventPanel");
        add(b, "OpenGachaPanel");
        add(b, "OpenPremiumPanel");
        add(b, "OpenAdPanel");
        add(b, "OpenWebPanel");
        return b.toString();
    }

    /**
     * 请求发送（{@link #TARGET_HTTP}）：{@code HttpSystem} 里真正把请求发出去的成员
     * （取自 global-metadata.dat 第 7670 行起的类成员表）。
     */
    static String httpTable() {
        StringBuilder b = new StringBuilder(512);
        add(b, "HttpSystem", "httpConnect");
        add(b, "HttpSystem", "connectWWW");
        add(b, "HttpSystem", "AddHttpQueue");
        add(b, "HttpSystem", "StartHttpQueue");
        add(b, "HttpSystem", "UpdateUserInfoPair");
        return b.toString();
    }

    /**
     * 菜单项旗帜（{@link #TARGET_FLAG}）：{@code MenuCell} 的 ActiveFlag/EnableFlag 写入路径
     * （类名与成员名见 global-metadata.dat 27487 行起）。原逻辑跑完再清零。
     */
    static String flagTable() {
        StringBuilder b = new StringBuilder(512);
        add(b, "MenuCell", "set_EnableFlag");
        add(b, "MenuCell", "set_ActiveFlag");
        add(b, "InfoMenuPanel", "set_isActiveCheckBrokenPanelButton");
        return b.toString();
    }

    /**
     * 遮罩（{@link #TARGET_MASKS}）：{@code AbstractPanel} 的遮罩系列
     * （成员名见 global-metadata.dat 22949 行起）。单机时不加遮罩，避免玩家被
     * 「不可用」提示后面那层黑幕困住。
     */
    static String masksTable() {
        StringBuilder b = new StringBuilder(768);
        add(b, "AbstractPanel", "addMaskCollider");
        add(b, "AbstractPanel", "addMaskColliderBlack");
        add(b, "AbstractPanel", "addMaskColliderWhite");
        add(b, "AbstractPanel", "addMaskColliderClear");
        add(b, "AbstractPanel", "addNowLoadingPanel");
        return b.toString();
    }

    private static void add(StringBuilder b, String method) {
        add(b, "*", method);
    }

    private static void add(StringBuilder b, String klass, String method) {
        b.append('*').append('\t').append(klass).append('\t').append(method).append('\n');
    }

    /** 名单里一共有多少行（不含空行/注释），测试与自检用。 */
    static int countLines(String table) {
        if (table == null || table.isEmpty()) return 0;
        int n = 0;
        for (String line : table.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) n++;
        }
        return n;
    }

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
     * 安装六族 hook。放后台线程并最多等 {@code waitMs} 毫秒：il2cpp 比 ContentProvider 起得晚，
     * 早到的调用会拿不到 API 指针，需要重试。
     *
     * <p>本方法**绝不抛异常**，也绝不让游戏的启动等太久（最长一次等 {@code waitMs}）。
     *
     * @return 六族合计挂上的方法数；0 或负数表示这次没成（日志里有原因码）
     */
    static int install(final Context ctx, int waitMs) {
        if (!load(ctx)) return -100;
        push(ctx);
        final CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    installAll();
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
        int total = 0;
        for (int r : lastResults) total += Math.max(0, r);
        Log.i(TAG, "install done: " + describe());
        return total;
    }

    /** 逐个 target 装，每个都单独 try/catch：一族失败不影响其它族。 */
    private static void installAll() {
        int[][] gs = groups();
        int[] results = new int[gs.length];
        boolean any = false;
        for (int i = 0; i < gs.length; i++) {
            int target = gs[i][0];
            try {
                int n = installRetry(target, table(target));
                results[i] = n;
                if (n > 0) any = true;
            } catch (Throwable t) {
                results[i] = -102;
                Log.w(TAG, "install target " + target + " threw: " + t);
            }
        }
        lastResults = results;
        installedAny = any;
    }

    /** 反复重试直到拿到正的挂载数（说明 il2cpp 已经起来、名字也解析上了）。 */
    private static int installRetry(int target, String table) {
        if (table == null || table.isEmpty()) return 0;
        int last = Integer.MIN_VALUE;
        for (int i = 0; i < 60; i++) {
            try {
                last = nativeInstall(target, table);
            } catch (Throwable t) {
                Log.w(TAG, "nativeInstall(" + target + ") threw: " + t);
                return -102;
            }
            // 负数里只有 -1（il2cpp 还没可见）值得重试；-4/-5 是永久性的
            if (last != -1) return last;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    /** 卸掉所有 hook（正常路径靠开关值就够，不必卸）。 */
    static int uninstall() {
        if (!loaded) return -1;
        try {
            return nativeUninstall();
        } catch (Throwable t) {
            Log.w(TAG, "uninstall failed: " + t);
            return -2;
        }
    }

    /** 运行时探测：我们依赖的 il2cpp API 能 dlsym 到几个（期望 5）。 */
    static int probe() {
        if (!loaded) return -1;
        try {
            return nativeProbe();
        } catch (Throwable t) {
            return -2;
        }
    }

    /** 清空拦截计数（切开关时调一次，真机看日志更直观）。 */
    static void resetStats() {
        if (!loaded) return;
        try {
            nativeResetStats();
        } catch (Throwable ignored) {
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

    /** 自检用：某个类在不在运行时里（不调用任何方法）。 */
    static boolean classPresent(String klass) {
        if (!loaded) return false;
        try {
            return nativeClassPresent(klass);
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code [已挂, 入口不创建, 请求拦截, 置灰, 遮罩, 解析失败, 回调异常]}；未加载返回 null。 */
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
     * 逻辑与 {@link NameGate} 的一致（同一个 .so，同一份 assets），这里单独放一份是为了
     * 让本类能在纯 JDK 环境里被测（不牵扯 NameGate 的其它依赖）。
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
        parts.add("skip=" + s[1]);
        parts.add("req=" + s[2]);
        parts.add("grey=" + s[3]);
        parts.add("mask=" + s[4]);
        parts.add("miss=" + s[5]);
        parts.add("fault=" + s[6]);
        return "native: " + parts;
    }

    /** 给 SelfCheck / 支援弹窗用的一句话现状（不含任何路径与账号信息）。 */
    static String summary() {
        if (!loaded) {
            return "原生闸门未加载（仅 Java 层拦截生效）";
        }
        int[] s = stats();
        if (s == null) return "原生闸门未就绪";
        if (s[0] <= 0) return "原生闸门未挂上（il2cpp 未就绪）";
        return "原生闸门已挂钩 " + s[0] + " 处：挡请求 " + s[2] + " 次、置灰入口 " + s[3]
                + " 次、不放遮罩 " + s[4] + " 次";
    }

    /** 每一族的挂载结果（自检用，顺序与 {@link #groups()} 一致）。 */
    static int[] results() {
        int[] r = lastResults;
        return r == null ? new int[0] : r.clone();
    }

    static boolean installedAny() {
        return installedAny;
    }
}
