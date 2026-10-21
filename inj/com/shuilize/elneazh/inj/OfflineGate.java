package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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
 *   <li>{@link #TARGET_BUTTON}：按钮 / 菜单格的点击回调。<b>单机时不执行原回调</b>，
 *       并把入口的 GameObject {@code SetActive(false)} —— 入口从界面上消失、点了也没反应。</li>
 *   <li>{@link #TARGET_GATE}：{@code Add*Panel} 工厂。单机时工厂照跑（拿到实例），
 *       但造出来的面板立刻走面板自己的 {@code CloseAll} / {@code CloseButtonClick} 收掉，
 *       绝不留一个打不开又关不掉的框。<b>绝不返回 NULL</b>（调用方会拿返回值继续用）。</li>
 *   <li>{@link #TARGET_HIDE}：刷新 / 显示路径（{@code set_active}/{@code set_visible}/
 *       {@code set_alpha}/{@code SetActive} 的 wrapper）。单机时先跑原方法、再把对象
 *       隐藏掉（{@code SetActive(false)}）并让所在 Grid/Table 重排，入口不会在界面刷新时又冒出来。</li>
 *   <li>{@link #TARGET_PANEL}：纯副作用型的打开入口（{@code Open*Panel}），返回值没人用，
 *       单机时不执行，入口不创建。</li>
 *   <li>{@link #TARGET_HTTP}：{@code HttpSystem} 的 {@code httpConnect} / {@code AddHttpQueue} /
 *       {@code connectWWW}。单机时不发，直接返回「失败」，让游戏走它自己的通信失败/离线分支
 *       （游戏里有 {@code OfflineData}/{@code IsOfflineActive}、{@code errorOfflineCheckHttp}）。
 *       <b>不伪造任何服务器回包。</b></li>
 *   <li>{@link #TARGET_MASKS}：遮罩系列。单机时不加遮罩，页面保持可用。</li>
 *   <li><b>请求出口兜底闸</b>（{@code EG_TARGET_ENC}，native 内部）：挂在
 *       {@code Cipher::EncryptRJ256} 这个**已经确认生效**的入口上（名字闸门用的同一个）。
 *       它是每个 sns04 请求体的唯一出口；单机时直接返回 NULL，调用方走它自己的
 *       「加密/发送失败」分支 —— 请求一个字节也出不了手机，且**不伪造任何业务回包**。
 *       这条不依赖 il2cpp 按名解析，所以即使上面的方法名对不上，上传/邮箱/登录请求也漏不出去。</li>
 * </ol>
 *
 * <p>为什么必须走 native：游戏的 UI 与网络都在 il2cpp 里，Java 层既反射不到
 * {@code MenuCell}，也没有 JNI 出口（详见 HANDOFF）。Java 侧的
 * {@link NetBlocker}（URLStreamHandlerFactory）与 {@link NetGuard} 保留作兜底。
 *
 * <p>几条硬约束：
 * <ul>
 *   <li>解析不到就跳过并打日志，绝不崩、绝不阻断游戏启动；</li>
 *   <li>开关状态每次 hook 都现读 native 里的缓存值，切换开关不用重进游戏；</li>
 *   <li>关掉单机后一切恢复：回调原样转发、工厂照常执行、界面重新构建时入口自然回来；</li>
 *   <li>日志统一打 {@code ElneaOffline}，真机一条 {@code adb logcat -s ElneaOffline} 就能看。</li>
 * </ul>
 */
final class OfflineGate {
    private static final String TAG = "ElneaOffline";
    private static final String LIB = "elneagate";

    private static boolean loaded;
    /** 加载成功只打一次日志，失败每条都打（真机 logcat 才有东西可看）。 */
    private static boolean loggedReady;
    /** 安装结束后重写一次 selfcheck（安装前写的那份必然是 not loaded）。 */
    private static volatile Context ctxRef;
    private static volatile int[] lastResults = new int[0];
    private static volatile boolean installedAny;
    private static volatile boolean workerRunning;
    /**
     * 后台安装窗口：从进程起来算，最多等这么久让 libil2cpp 出现。
     *
     * <p>90s → 30s：真机事故里「黑屏 + 一会儿后退出」至少有一份就是这类长等待叠出来的。
     * 30s 足够 Unity 起完 il2cpp（正常 3~8s），再长就是在赌；到点放弃，Java 层照旧兜底，
     * selfcheck 里写「il2cpp 未就绪」，下一轮（回前台 / 切开关）还会补装。
     */
    static final long INSTALL_WINDOW_MS = 30_000L;

    /** 给 crash guard 的 native 侧用：同一份窗口值（秒级精度够）。 */
    static long windowMs() {
        return INSTALL_WINDOW_MS;
    }

    private OfflineGate() {}

    // ---------------- native 声明 ----------------

    private static native int nativeInstall(int target, String names);

    private static native int nativeUninstall();

    private static native int nativePush(String state);

    private static native int[] nativeStats();

    /** native 侧现在读到的「单机开」值（1/0），用于切开关后对账。 */
    private static native int nativeOff();

    /** 兜底闸（EncryptRJ256）状态：1 = 已挂上。请求发不出去的最后一道保证。 */
    private static native int nativeEncState();

    private static native int nativeResetStats();

    private static native int nativeProbe();

    private static native boolean nativeResolvable(String ns, String klass, String method);

    private static native boolean nativeClassPresent(String klass);

    /** 逐条 hook 台账：每个目标的名字 / 解析到的地址 / 挂的方式 / 成功或失败原因。 */
    private static native String nativeHookReport();

    /** 台账的安全包装：native 没加载或旧 so 没有这个符号时给一句人话，绝不抛出去。 */
    static String hookReport() {
        try {
            String s = nativeHookReport();
            return s != null ? s : "hooks: n/a (native returned null)";
        } catch (Throwable t) {
            return "hooks: n/a (" + t.getClass().getSimpleName() + ")";
        }
    }

    // ---------------- 六族钩子（编号必须与 native/elneagate/eg_offline.h 一致） ----------------

    /** 纯副作用型的打开入口（返回 NULL 安全）：单机时不执行，入口不创建。 */
    static final int TARGET_PANEL = 1;
    /** 请求发送：单机时不发。 */
    static final int TARGET_HTTP = 2;
    /** {@code Add*Panel} 工厂：单机时工厂照跑，但造出来的面板立刻收掉；绝不返回 NULL。 */
    static final int TARGET_GATE = 3;
    /** 按钮 / 菜单格回调：单机时不执行原回调，并把入口 GameObject {@code SetActive(false)}。 */
    static final int TARGET_BUTTON = 4;
    /** 刷新 / 显示路径：单机时先跑原方法再把对象隐藏，入口不会在刷新时又出现。 */
    static final int TARGET_HIDE = 5;
    /** 遮罩：单机时不加，页面保持可用。 */
    static final int TARGET_MASKS = 6;
    /** 本地装玩：单机时把 {@code TitlePanel} 的状态按成「用本地存档」，不弹账号输入面板。 */
    static final int TARGET_LPLAY = 7;

    // ---------------- 该挂哪些方法 ----------------

    /**
     * 六族各自一份名单，交给 native 逐行解析。
     *
     * <p>名字全部来自 global-metadata.dat（面板类名、成员名、{@code Add*Panel} 工厂方法族），
     * 每行 {@code 命名空间 \t 类 \t 方法}；命名空间写 {@code *} 表示不限。
     * 解析不到的条目由 native 记一笔 {@code resolve miss} 日志跳过，不影响其它条目。
     */
    static int[][] groups() {
        return new int[][] {
                { TARGET_BUTTON, 0 }, { TARGET_GATE, 0 }, { TARGET_HIDE, 0 },
                { TARGET_PANEL, 0 }, { TARGET_HTTP, 0 }, { TARGET_MASKS, 0 },
                { TARGET_LPLAY, 0 },
        };
    }

    /**
     * 按 target 取名单。参数 {@code target} 必须是上面那些常量之一。
     *
     * <p><b>单机关着时一律返回空串</b>：这时候 native 侧只该做拼音闸门与兜底闸
     * （{@code EncryptRJ256}），UI 隐藏 / 请求拦截 / 遮罩 / 本地游玩这些表一个字节都不该递过去。
     * 这条不是「顺手优化」——真机上六族全装要几十次 {@code il2cpp} 按名解析加写槽，
     * 它在 Unity 主线程跑起来的那几秒里做，就是黑屏的一条来路。
     */
    static String table(int target) {
        if (ctxRef != null && !Offline.isOn(ctxRef)) return "";
        switch (target) {
            case TARGET_BUTTON: return buttonTable();
            case TARGET_GATE: return gateTable();
            case TARGET_HIDE: return hideTable();
            case TARGET_PANEL: return panelTable();
            case TARGET_HTTP: return httpTable();
            case TARGET_MASKS: return masksTable();
            case TARGET_LPLAY: return lplayTable();
            default: return "";
        }
    }

    /**
     * 按钮 / 入口的点击回调（{@link #TARGET_BUTTON}）。
     *
     * <p>这些是「点了就打开付费 / 联网界面」的函数，名字取自 global-metadata.dat 里
     * {@code UI\Menu\InfoMenuPanel}（27470 行起）、{@code UI\Shop\*} 等类的成员表。
     * 单机时<b>原回调一行都不跑</b>，并把入口 GameObject {@code SetActive(false)}
     * —— 入口本来已经不可见，这一层是双保险。不调 {@code interactable}、不弹提示。
     */
    static String buttonTable() {
        StringBuilder b = new StringBuilder(3072);
        // 主菜单那几个入口（InfoMenuPanel）
        add(b, "InfoMenuPanel", "GiftBoxButtonClick");
        add(b, "InfoMenuPanel", "WhatsNewButtonClick");
        add(b, "InfoMenuPanel", "SerialCodeInputButtonClick");
        add(b, "InfoMenuPanel", "BoardMenuOpenButtonClick");
        add(b, "InfoMenuPanel", "CheckBrokenPanelButtonClick");
        // 商店 / 内购 / 付费
        add(b, "ShopFooterButtonClick");
        add(b, "shopButtonClick");
        add(b, "ShopButtonClick");
        add(b, "ChargeButtonClick");
        add(b, "chargeButtonClick");
        add(b, "gemBuyButtonClick");
        add(b, "GemShopButtonClick");
        add(b, "buyButtonClick");
        add(b, "BuyButtonClick");
        add(b, "TradeShopButtonClick");
        // 邮箱 / 好友 / PVP / 排行 / 公告 / 活动 / 广告
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
     * 面板工厂（{@link #TARGET_GATE}）：单机时<b>工厂本身不执行</b>，面板不会被造出来。
     *
     * <p>返回值仍然是 NULL，但这里的调用方全是「入口已经不可达」的路径（入口已被
     * {@link #TARGET_BUTTON}/{@link #TARGET_HIDE} 藏掉），不会再有人解引用它；
     * 且 native 侧对「外面已经造出来的面板」会补一刀 {@code CloseAll}。
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
        add(b, "AddLoginPanel");
        add(b, "AddMigrantPasswordIssueListPanel");
        add(b, "AddReportPanel");
        return b.toString();
    }

    /**
     * 刷新 / 显示路径（{@link #TARGET_HIDE}）：这些 setter 一被调用，界面就把入口显示出来，
     * 单机时先让原方法跑完（不打断游戏自己的状态），紧接着把对象 {@code SetActive(false)}，
     * 入口不会在刷新时又冒出来。
     *
     * <p>名字取自 global-metadata.dat（{@code MenuCell} 见 27487 行起）。注意：{@code set_active}
     * / {@code set_visible} / {@code set_alpha} 这类名字被很多类共用，**不挂**（一处误伤就是
     * 大面积隐藏）；只挂明确属于入口菜单格的成员，解析不到就跳过。
     */
    static String hideTable() {
        StringBuilder b = new StringBuilder(768);
        add(b, "MenuCell", "set_ActiveFlag");
        add(b, "MenuCell", "set_EnableFlag");
        add(b, "InfoMenuPanel", "set_isActiveCheckBrokenPanelButton");
        add(b, "MenuCell", "set_IsActive");
        add(b, "MenuCell", "set_Visible");
        add(b, "MenuCell", "set_Alpha");
        add(b, "MenuCell", "SetActiveSelf");
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
        // 真机「仍弹账号输入面板」的直接来源：这些是「发登录/建号请求」的函数，
        // 名字取自 global-metadata 里 LoginRequest / AccountInputPanel 附近的成员表。
        // 解析不到就跳过（miss 计数会加一），不影响其它条目。
        add(b, "LoginRequest", "Request");
        add(b, "LoginRequest", "Send");
        add(b, "LoginRequest", "Start");
        add(b, "AccountInputPanel", "SendButtonClick");
        add(b, "AccountInputPanel", "LoginButtonClick");
        add(b, "AccountInputPanel", "createButtonClick");
        add(b, "AccountInputPanel", "SendLogin");
        return b.toString();
    }

    /**
     * 本地游玩（{@link #TARGET_LPLAY}）：把 {@code TitlePanel} 的「没有服务器也要能进」
     * 开关按游戏自己的那套字段按下去，于是登录失败时不再弹账号输入面板，直接进本地存档。
     *
     * <p>依据：{@code TitlePanel} 的成员表里就有一整套本地游玩状态（global-metadata 顺序）：
     * {@code isChangeUnity} {@code disableLocalPlay} {@code closeLocalPlay}
     * {@code isAccountInput} {@code isEmytyLocalAccount} {@code isViewLocalPlayWarning}
     * {@code accountCheck} {@code startLoginRequest} {@code openAccountPanel}
     * {@code requestErrorAlert}。也就是说「单机」是游戏原生就支持的状态，
     * 我们只要不让它把状态翻成「需要输入账号」。
     *
     * <p>注意：这里<b>不让任何方法返回伪造的回包</b>，只是把「登录没了 → 去要账号」这一步
     * 换成「登录没了 → 用本地存档」。native 侧对 {@code openAccountPanel} /
     * {@code requestErrorAlert} 这类纯副作用入口做法与 PANEL 族一致：不执行。
     */
    static String lplayTable() {
        StringBuilder b = new StringBuilder(512);
        // 「弹账号框 / 弹通信错误」的入口：单机时不执行（返回值没人用）
        add(b, "TitlePanel", "openAccountPanel");
        add(b, "TitlePanel", "AccountInputButtonClick");
        add(b, "TitlePanel", "requestErrorAlert");
        // 纯状态 setter：单机时强制方向为「本地装玩」，见 native 的 EG_TARGET_LPLAY
        add(b, "TitlePanel", "set_disableLocalPlay");
        add(b, "TitlePanel", "set_isAccountInput");
        add(b, "TitlePanel", "set_isEmytyLocalAccount");
        return b.toString();
    }

    /**
     * 遮罩（{@link #TARGET_MASKS}）：{@code AbstractPanel} 的遮罩系列
     * （成员名见 global-metadata.dat 22949 行起）。单机时不加遮罩，
     * 页面保持可用，玩家不会被困在遮罩后面。
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
        // 与名字闸门共用同一个 libelneagate.so，也共用同一个加载器（NativeLoad）：
        // 解包到 files/lib 后走 System.load(绝对路径)，失败会重试（不再有 loadTried 一次性锁死）。
        // 早先两边各自解包再 System.loadLibrary 同一个名字，谁先谁后都可能让后者拿到
        // 一个「名字已占用但没真正加载」的状态，闸门就永远不会挂上 —— 真机 selfcheck 的
        // `native: not loaded` 就是这么来的。
        if (NativeLoad.tryLoad(ctx)) {
            loaded = true;
            if (!loggedReady) {
                loggedReady = true;
                Log.i(TAG, "libelneagate.so ready (" + NativeLoad.describe() + ")");
            }
        } else {
            Log.w(TAG, "libelneagate.so NOT loaded (offline gate): " + NativeLoad.describe());
        }
        return loaded;
    }

    /** 自检用：加载状态 + 上次 dlopen 错误串。 */
    static String loadState() {
        return NativeLoad.describe();
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
        if (!loaded) {
            Log.w(TAG, "push(" + on + "): native not loaded yet, state not pushed");
            return false;
        }
        try {
            int r = nativePush(on ? "1" : "0");
            int now = nativeOff();
            Log.i(TAG, "state pushed: on=" + on + " ok=" + (r != 0) + " nativeOff=" + now
                    + " enc=" + encState());
            return r != 0;
        } catch (Throwable t) {
            Log.w(TAG, "push failed: " + t);
            return false;
        }
    }

    /**
     * 对账：prefs 里的值 vs native 里现读的值。
     *
     * <p>真机反馈「单机开着仍然上传/仍弹登录」时，第一步就是看这两个数对不对得上：
     * prefs=true 而 native=0 说明状态没推到 native（push 没跑 / .so 没装 / native 早退），
     * 这时闸门一律当「没开」，请求照常出去 —— 正是那批现象。
     */
    static boolean stateMatches(Context ctx) {
        boolean want;
        try {
            want = Offline.isOn(ctx);
        } catch (Throwable t) {
            return false;
        }
        if (!loaded) return !want;            // 没加载：只有「关着」才算对得上
        try {
            return (nativeOff() != 0) == want;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 兜底闸（EncryptRJ256）是否挂上。挂上 = 单机时请求体一个字节也出不去。 */
    static boolean encHooked() {
        if (!loaded) return false;
        try {
            return nativeEncState() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 安装 ----------------

    /**
     * 安装六族 hook + 兜底闸。返回**立刻**，真正的安装在后台线程里做完。
     *
     * <p>早先这里等 {@code waitMs} 毫秒后返回，调用方传的是 1ms，结果：
     * 后台线程还在第一次 {@code nativeInstall} 里（il2cpp 那时还没 dlopen），
     * {@code lastResults} 全是 0，弹窗里的「原生闸门已挂钩 0 处」就是这么来的。
     * 现在后台线程一直重试到 libil2cpp 出现为止（最长 {@value #INSTALL_WINDOW_MS}ms），
     * 每族的结果一出来就写进 {@link #lastResults}，界面刷新时能看到真实数字。
     *
     * <p>本方法**绝不抛异常**，也绝不让游戏的启动等它。
     *
     * @return 上次已知的合计挂载数（后台还在装时可能是 0）
     */
    static int install(final Context ctx, int waitMs) {
        if (ctx != null) ctxRef = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        if (!load(ctx)) {
            // 没加载上：照样把 selfcheck 重写一遍，把 dlopen 错误串写进去（用户一条命令就能看到原因）。
            SelfCheck.rewriteAsync(ctxRef);
            return -100;
        }
        push(ctx);
        if (workerRunning) {
            Log.i(TAG, "install already running; push only");
            return total();
        }
        workerRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    installAll();
                } catch (Throwable e) {
                    Log.w(TAG, "install failed", e);
                } finally {
                    workerRunning = false;
                }
            }
        }, "ElneaOffline-install");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "install started in background (waitMs=" + waitMs + " ignored)");
        return 0;
    }

    /** 应用回到前台时再补一次：进程里可能已经装过，这里只补没挂上的族。 */
    static void ensureInstalled(final Context ctx) {
        if (ctx == null) return;
        push(ctx);
        if (workerRunning) return;
        install(ctx, 0);
    }

    /** 逐个 target 装，每个都单独 try/catch：一族失败不影响其它族。 */
    private static void installAll() {
        long t0 = System.currentTimeMillis();
        if (!NativeGuard.allowed()) {
            // crash guard 停用了 native（或用户在 Support 关了「原生加速」）：native 根本没加载，
            // 这里什么都不该做，更不能去等 libil2cpp。
            Log.i(TAG, "install skipped: " + NativeGuard.reason());
            SelfCheck.rewriteAsync(ctxRef);
            return;
        }
        if (!Offline.isOn(ctxRef)) {
            // 单机关着：六族（界面隐藏 / 请求拦截 / 遮罩 …）一个都不需要。
            // 只留 native 侧的兜底闸（EncryptRJ256），由 eg_of_install 自己处理。
            // 早先这里照样把六族全装一遍 —— 几十次 il2cpp 按名解析 + 写槽，
            // 真机上（尤其 Android 16 / 16KB 页）就是启动期卡顿与黑屏的一条来路。
            try {
                nativeInstall(0, "");   // target 0 非法，native 直接返回 -5；这里只是让日志有对账
            } catch (Throwable ignored) {
            }
            lastResults = new int[groups().length];
            installedAny = false;
            Log.i(TAG, "install skipped: offline is OFF, no UI/Http hooks installed ("
                    + (System.currentTimeMillis() - t0) + "ms)");
            SelfCheck.rewriteAsync(ctxRef);
            return;
        }
        int[][] gs = groups();
        int[] results = new int[gs.length];
        // 第一轮：等 libil2cpp 出现（最长 INSTALL_WINDOW_MS）。只要有一族挂上就说明运行时起来了。
        long deadline = System.currentTimeMillis() + INSTALL_WINDOW_MS;
        boolean api = false;
        while (System.currentTimeMillis() < deadline && !api) {
            api = probe() >= 5;
            if (!api) sleep(100);
            // 等待期间用户可能关掉单机 / crash guard 生效：立刻停手
            if (!Offline.isOn(ctxRef) || !NativeGuard.allowed()) break;
        }
        Log.i(TAG, "il2cpp api probe=" + probe() + "/5 after wait");
        // libil2cpp 映射了、导出可见了，不代表 il2cpp_init 已跑完：那之前 class_from_name 一律找不到，
        // 每族都会「0 个解析到」然后当终态放弃（真机 18:58：api 5/5 但 hooks 0/0）。
        // 所以再等到 TitlePanel 这个类真的出现（每 100ms 看一次），再装 —— 越早装越好，
        // 必须赶在标题画面跑登录检查之前。
        while (System.currentTimeMillis() < deadline && api && !classPresent("TitlePanel")) {
            if (!Offline.isOn(ctxRef) || !NativeGuard.allowed()) break;
            sleep(100);
        }
        Log.i(TAG, "TitlePanel present=" + classPresent("TitlePanel") + " -> installing families");
        for (int i = 0; i < gs.length; i++) {
            if (!Offline.isOn(ctxRef)) {
                Log.i(TAG, "install aborted mid-way: offline turned off");
                break;
            }
            int target = gs[i][0];
            try {
                int n = installRetry(target, table(target));
                results[i] = n;
                lastResults = results.clone();      // 一族完成就对外可见
                Log.i(TAG, "family " + target + " -> " + n);
            } catch (Throwable t) {
                results[i] = -102;
                Log.w(TAG, "install target " + target + " threw: " + t);
            }
        }
        lastResults = results;
        installedAny = total() > 0;
        Log.i(TAG, "install done in " + (System.currentTimeMillis() - t0) + "ms: " + describe());
        // 进程刚起来时 ZhInit 里的 selfcheck 记的是「还没加载 / 还没挂」的状态；挂完这一轮再把
        // 真实数字重写一遍，真机上 files/elneazh_selfcheck.txt 才是最终态。
        SelfCheck.rewriteAsync(ctxRef);
    }

    /**
     * 反复重试直到不再返回 -1（il2cpp 还没可见）。
     *
     * <p>注意：<b>0 也是终态</b>。0 的意思是「il2cpp 起来了，但这一族的名字一个都没解析到」，
     * 再等下去也不会变；早先这里把 0 当成成功立即返回，其它族也就跟着跑完了，
     * 但日志里看不出「解析到了几次」，所以 {@link #describe()} 现在会把 miss 一起打出来。
     */
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
            // 负数里只有 -1（il2cpp 还没可见）值得重试；-2/-3/-4/-5 是永久性的
            if (last != -1) return last;
            sleep(500);
        }
        return last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /** 清空计数（切开关时调一次，真机看日志更直观）。 */
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

    /** {@code [已挂, 入口不创建, 请求拦截, 界面隐藏, 遮罩跳过, 解析失败, 回调异常]}；未加载返回 null。 */
    static int[] stats() {
        if (!loaded) return null;
        try {
            return nativeStats();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- assets 里的 .so 解包（已合并到 NativeLoad） ----------------

    /** 兼容旧调用点：解包并返回绝对路径。加载本身见 {@link NativeLoad}。 */
    static String unpack(Context ctx, String name) {
        return NativeLoad.ensure(ctx, name);
    }

    static String describe() {
        int[] s = stats();
        if (s == null) return "native: not loaded (" + NativeLoad.describe() + ")";
        List<String> parts = new ArrayList<>();
        parts.add("hooked=" + s[0]);
        parts.add("hidden=" + s[1]);
        parts.add("req=" + s[2]);
        parts.add("ui=" + s[3]);
        parts.add("mask=" + s[4]);
        parts.add("miss=" + s[5]);
        parts.add("fault=" + s[6]);
        parts.add("enc=" + (s.length > 7 ? s[7] : 0));
        parts.add("off=" + safeOff());
        parts.add("families=" + java.util.Arrays.toString(lastResults));
        return "native: " + parts;
    }

    private static int safeOff() {
        try {
            return loaded ? nativeOff() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    static int encState() {
        try {
            return loaded ? nativeEncState() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 合计挂载数（六族之和）。 */
    static int total() {
        int n = 0;
        for (int r : lastResults) n += Math.max(0, r);
        return n;
    }

    /**
     * 本进程的 native 是否「只做拼音闸门 / 兜底闸」——即单机关着时的那条路。
     *
     * <p>判据同时看两边：单机开关是关的，且六族一张表都没有可递的名字。
     * 自检与测试都用它来确认「关单机不装 UI/Http」这件事没有被改回去。
     */
    static boolean encGateOnly() {
        if (ctxRef != null && Offline.isOn(ctxRef)) return false;
        for (int kind : new int[]{TARGET_BUTTON, TARGET_GATE, TARGET_HIDE, TARGET_PANEL,
                TARGET_HTTP, TARGET_MASKS, TARGET_LPLAY}) {
            String t;
            try {
                t = table(kind);
            } catch (Throwable e) {
                return false;
            }
            if (t != null && !t.trim().isEmpty()) return false;
        }
        return true;
    }

    /**
     * 给 SelfCheck / 支援弹窗用的一句话现状（不含任何路径与账号信息）。
     *
     * <p>「生效」的判据不是「挂了多少个」，而是**兜底闸在不在**：请求体最后一道出口是
     * {@code Cipher::EncryptRJ256}，只要它挂上了，单机时任何 sns04 请求都发不出去。
     */
    static String summary() {
        if (!loaded) {
            return "原生闸门未加载（仅 Java 层拦截生效）";
        }
        int[] s = stats();
        if (s == null) return "原生闸门未就绪";
        boolean enc = s.length > 7 && s[7] != 0;
        if (enc) {
            return "单机闸门已生效：请求出口已封（EncryptRJ256），另挂钩 " + s[0] + " 处入口";
        }
        if (s[0] <= 0) {
            return "原生闸门未挂上（il2cpp 未就绪；请求出口未封）";
        }
        return "已挂钩 " + s[0] + " 处，但请求出口（EncryptRJ256）未挂上";
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
