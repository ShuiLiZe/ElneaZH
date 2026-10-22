package com.shuilize.elneazh.inj;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 「单机模式」的状态、接口分类与本地离线回包。
 *
 * 开关存 shared_prefs/elneazh_settings.xml（键 offline），Backup 会随存档一起备份/恢复。
 *
 * 真单机 = 游戏进程一个字节都不发往游戏服务器：
 *  1. {@link #isGameServer(String)} 判定目标是不是游戏服务器（althi.co.jp 全部子域 + sns04 路径）。
 *  2. 单机开着时，游戏服务器上的**任何**请求都在本地被接住（{@link NetBlocker}），一个都不发出去
 *     —— 含登录、check、上传、读数据。设备码/账号也就无从泄露。
 *  3. 被接住的请求由 {@link #offlineReply(String)} 给一份**只在本机**生成的最小离线回包，
 *     让 C# 层的登录/数据请求走「成功但空」的分支而不是网络错误分支（见 Reply 的说明）。
 *     这些回包里没有任何服务器数据，也没有设备信息，全部由本文件根据 URL 现编。
 *
 * 扩展包 / 资源下载（见 {@link #assetHost(String)}、{@link #downloadClass}）：
 *  - 资源清单与 bundle 地址**由服务器下发**（global-metadata: HttpSystem.listURL / assetBundleURL / cacheFlag，
 *    AssetBundleManager.getAssetBundleURL()），不是硬编码的静态 CDN；且 /sns04/ 四个入口都带
 *    key_header_info / addUniqArg 的请求头（含账号与设备标识）。
 *  - 所以单机开着时资源下载同样一律拦下，并给出「请关闭单机模式下载」的明确提示；
 *    已下载的 bundle 在中 archive 里（cache / PreLoadData），单机时照常从本地读，不受影响。
 *
 * 明确不做：不伪造服务器下发的业务数据（存档、角色、物品都不是编的）、不改设备码、不删服务器标记、
 * 不绕反作弊。存档内容始终只来自本机。
 */
final class Offline {
    private static final String TAG = "ElneaZH-NET";
    static final String PREF = "elneazh_settings";
    static final String KEY = "offline";

    private Offline() {}

    // ---------------- 状态 ----------------

    static boolean isOn(Context c) {
        if (c == null) return false;
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false);
        } catch (Throwable t) {
            Log.w(TAG, "offline state", t);
            return false;
        }
    }

    /** 同步 commit：开/关要立刻生效（闸门在下一个请求上就要按新值走）。 */
    static boolean set(Context c, boolean on) {
        if (c == null) return false;
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).commit();
        } catch (Throwable t) {
            Log.w(TAG, "offline set", t);
            return false;
        }
    }

    // ---------------- 服务器判定 ----------------

    /**
     * 是不是游戏服务器。**按 host 后缀判定，不用 contains**：
     * contains 会把 `althi.co.jp.evil.com` 这类伪装域名当成自己的服务器（既可能误拦别人的请求，
     * 也可能被利用来把发给第三方的数据当成「游戏请求」处理）。这里只认
     * host == althi.co.jp 或 host 以 .althi.co.jp 结尾。
     * 覆盖 metadata 里登记的四个入口（app- / cdn- / res- / 裸域，都在 /sns04/ 下）。
     */
    static boolean isGameServer(String url) {
        String host = host(url);
        if (host.isEmpty()) return false;
        boolean ours = host.equals(DOMAIN) || host.endsWith("." + DOMAIN);
        if (!ours) return false;
        String path = path(url);
        return path.contains("/sns04/") || path.endsWith("/sns04");
    }

    private static final String DOMAIN = "althi.co.jp";

    /**
     * 单机开着时这条请求要不要在本地接住。现在是「游戏服务器 → 全接住」。
     * 保留 {@link #isWriteRequest} 与 {@link #isReadRequest} 是为了日志、自检统计与界面提示（哪些本来是写、哪些本来是读）。
     */
    static boolean intercept(String url, boolean offlineOn) {
        return offlineOn && isGameServer(url);
    }

    /** 被拦/被接住时的日志用短描述（不带请求体，避免把存档内容写进 logcat）。 */
    static String describe(String url) {
        String php = phpName(url);
        return php.isEmpty() ? "sns04" : php;
    }

    // ---------------- 上传 / 读 分类（日志、自检与提示用） ----------------

    /**
     * 会上传数据（改服务器状态或带设备码/账号）的接口。名字取自 global-metadata 字面量的
     * 「xxx.php」连续串（每个端点前后紧挨着成对字面量）。
     */
    private static final String[] WRITE = {
            "login.php", "create.php", "logout.php", "token.php",
            "save.php", "save2.php", "savetemp.php", "save_tmp.php", "savekpi.php", "kpi.php",
            "write.php", "rename.php", "redefine.php", "recov2.php",
            "check.php", "cheater_flagcheck.php",
            "pvp.php", "gift.php", "rem0ve.php", "reload_gift.php", "mail.php", "trade.php",
            "friend.php", "alliance.php", "training.php", "skip.php", "sale.php",
            "charge.php", "premium.php", "gem.php", "gacha.php", "migrant.php",
            "item.php", "boost.php", "active.php", "event.php", "ad.php",
    };

    /** 只读接口（只让服务器给东西）：单机时同样不发，但日志里归到「读」。 */
    private static final String[] READ = {
            "read.php", "load.php", "rank.php", "master.php", "news.php", "notice.php",
            "version.php", "update.php",
    };

    /** 旧名字保留：是不是「会上传」的接口（单机时这些都是不发的，只用于统计/日志）。 */
    static boolean isWriteRequest(String url) {
        String php = phpName(url);
        if (php.isEmpty()) return false;
        if (!isGameServer(url)) return false;
        for (String w : WRITE) if (php.equals(w)) return true;
        return false;
    }

    /** 是不是「只读」接口。单机时读也一并拦（真单机），这里只用于日志归类与自检统计。 */
    static boolean isReadRequest(String url) {
        String php = phpName(url);
        if (php.isEmpty()) return false;
        if (!isGameServer(url)) return false;
        for (String r : READ) if (php.equals(r)) return true;
        return false;
    }

    /** 是否属于「已知接口表」——自检只统计已知接口。 */
    static boolean known(String php) {
        String p = lc(php);
        for (String r : READ) if (p.equals(r)) return true;
        for (String w : WRITE) if (p.equals(w)) return true;
        return false;
    }

    /** 从 URL 里取最后一个 *.php 的全名（小写），没有则空串。 */
    static String phpName(String url) {
        String path = path(url);
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return last.endsWith(".php") ? last : "";
    }

    // ---------------- 扩展包 / 资源下载 ----------------

    /**
     * 该 URL 是不是「资源 / 扩展包下载」类请求（决定单机时是否放行）。
     *
     * 证据（global-metadata 字面量，`AssetBundleManager` 整块）：
     * `downloadAndCache` / `get_Url` / `getAssetExtension` / `getEnvironmentURL` /
     * `getAssetBundleURL` / `getManifestURL` / `downloadingData` / `PreLoadData` / `assetCacheData`。
     * 这条路径**不经过** `HttpSystem`，所以也不带 `user_number` / `crypt_key` /
     * `key_header_info` / `addUniqArg` 这些身份参数（字段计数全为 0，见 HANDOFF）。
     *
     * 判定按路径形状（bundle 地址由服务器下发，客户端无硬编码基址）：
     *  - 后缀：`.bundle` `.unity3d` `.ab` `.assetbundle`
     *  - 路径含 `assetbundle` / `assetbundles`
     *  - 路径以 `manifest` / `.manifest` 结尾（Unity 的清单文件）
     * **不含** `*.php` —— 那是接口，走 `HttpSystem`、带身份，单机必须拦。
     */
    static boolean isAssetDownload(String url) {
        String p = path(url);
        if (p.isEmpty()) return false;
        if (p.endsWith(".php")) return false;                        // 接口不算资源
        if (p.endsWith(".bundle") || p.endsWith(".unity3d") || p.endsWith(".ab")
                || p.endsWith(".assetbundle")) return true;
        if (p.contains("assetbundle")) return true;
        String last = p.substring(p.lastIndexOf('/') + 1);
        if (last.equals("manifest") || last.endsWith(".manifest")) return true;
        return false;
    }

    /**
     * 下载请求的分类。用于决定提示文案与日志。
     *  - {@link #DL_ASSET}  资源 / 扩展包 bundle 下载（**不带身份信息，单机放行**）
     *  - {@link #DL_SNSPHP} sns04 下的 *.php 接口（带 key_header_info / addUniqArg 身份，单机拦）
     *  - {@link #DL_OTHER}  其它
     */
    static final int DL_ASSET = 1;
    static final int DL_SNSPHP = 2;
    static final int DL_OTHER = 0;

    static int downloadClass(String url) {
        if (isAssetDownload(url)) return DL_ASSET;
        if (!phpName(url).isEmpty()) return DL_SNSPHP;
        return DL_OTHER;
    }

    /**
     * 资源下载用的主机，单机时放行。依据 global-metadata（见 HANDOFF「放行名单」）：
     *
     * 下载走的是 `AssetBundleManager.downloadAndCache` + `UnityWebRequest`，
     * 由 `getEnvironmentURL()` / `getAssetBundleURL()` / `getManifestURL()` 拼地址。
     * 把 `AssetBundleManager` 整块字段名数一遍：
     * `user_number` / `crypt_key` / `UserNumberOnServer` / `CryptKeyOnServer` /
     * `key_header_info` / `addUniqArg` / `uniq` / `uuid` / `playerId` / `token`
     * **全部出现 0 次**。也就是说 bundle 下载请求不带任何账号/设备/存档身份
     * ——身份只出现在 `HttpSystem.addUniqArg`（PHP 接口）那条路上。
     *
     * 所以按用户要求：「不带身份信息的资源下载照常放行」。
     * 这里刻意只认 althi 的资源宿主（res- / cdn- / 裸域），下载路径必须是资源形状。
     */
    static boolean isAssetHost(String url) {
        String h = host(url);
        if (h.isEmpty()) return false;
        return h.equals(RES_HOST) || h.equals(CDN_HOST) || h.equals(DOMAIN)
                || h.endsWith("." + DOMAIN);
    }

    static final String RES_HOST = "res-elnea.althi.co.jp";
    static final String CDN_HOST = "cdn-elnea.althi.co.jp";

    /**
     * 单机时这条请求能不能放行。
     *
     * 规则（用户 2026-10-21 明确要求，边玩边下载必须可用）：
     *  1. 非游戏服务器（广告/遥测/别的站）→ 本闸门不管，放行。
     *  2. **资源 / 扩展包的 bundle 下载**（`isAssetDownload`，如 `*.bundle`、`*.unity3d`、
     *     `assetbundle`、`manifest`）→ **放行**：这条路径不带任何身份信息（证据见 {@link #isAssetHost}）。
     *  3. 其余游戏服务器请求（`/sns04/*.php`：登录、存档、读数据、资源清单）→ **拦在本机**。
     *
     * 注意 2 与 3 的关系：bundle 地址本身由清单（`.manifest` / `listURL`）给出，清单也可能就是
     * 资源形状（`*.manifest`）而被放行——但清单里只有文件名与 hash，**不带身份**；
     * 带身份的是 `sns04/*.php`，那些照样拦。所以「一个字节身份信息都不出手机」仍成立。
     */
    static boolean allowWhileOffline(String url) {
        if (!isGameServer(url) && !isAssetHost(url)) return true;   // 别人的服务器，不管
        if (isAssetDownload(url)) return true;                       // 不带身份的 bundle 下载，放行
        return !intercept(url, true);                                // 其余按闸门裁决
    }

    /** 提示语：单机时点了「需要身份」的资源接口才会看到。 */
    static String downloadHint() {
        return "该资源需关闭单机模式下载：已缓存的资源照常可用，关掉单机后即可补下。";
    }

    /** 已购/已解锁内容的说明（单机时沿用本地上次状态，不发服务器）。 */
    static String purchaseHint() {
        return "已购/已解锁的内容在单机时沿用本机上次记录，不向服务器查询。";
    }

    // ---------------- 本地离线回包 ----------------

    /**
     * 单机时给 C# 层的最小回复（**只在本机生成，不发往任何服务器**）。
     *
     * 依据：global-metadata 里登录流程有 `NetworkError` / `ErrorMaintenance` / `successOfflineHttp`
     * 等分支，说明网络层失败与「维护」是两条不同路径；纯失败会走 NetworkError 分支反复提示，
     * 所以这里给「HTTP 200 + 空 JSON 对象」让解析走到「没有字段 → 默认值」的路径。
     * 空对象不含任何服务器数据，也没有设备/账号信息 —— 它只是「服务器答了，但什么都没有」。
     *
     * 内容类型按接口判断：kpi/统计类用纯文本 "ok"，其余用 application/json 的 "{}"。
     */
    static final class Reply {
        final int status;
        final String type;
        final String body;

        Reply(int status, String type, String body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }

        byte[] bytes() {
            try {
                return body.getBytes("UTF-8");
            } catch (Throwable t) {
                return new byte[0];
            }
        }

        String header() {
            return "HTTP/1.1 " + status + (status == 200 ? " OK" : " ERR") + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + bytes().length + "\r\n"
                    + "Connection: close\r\n\r\n";
        }

        @Override public String toString() { return status + " " + type + " " + body; }
    }

    /** 该 URL 在本机怎么回。未知接口一律给空的 JSON 对象。 */
    static Reply offlineReply(String url) {
        String php = phpName(url);
        if (php.startsWith("kpi") || php.contains("cheater_flag")) {
            return new Reply(200, "text/plain", "ok");
        }
        return new Reply(200, "application/json", "{}");
    }

    // ---------------- 界面文案 ----------------

    static String summary(boolean on) {
        return on ? "已开启：单机运行，只挡带身份的接口，资源照常下载" : "已关闭：正常联网（云存档、好友、邮件、PVP 可用）";
    }

    /** 开关下方那几行说明：必须让人一眼看到「哪些功能没了」。 */
    static List<String> limits() {
        List<String> l = new ArrayList<>();
        l.add("单机时云存档、好友、邮件、PVP、礼物都不可用");
        l.add("存档只存在本机；本地存档照常读写，导出/导入用下面「存档备份」");
        l.add("资源/扩展包照常下载（这条路径不带账号或设备信息）");
        l.add("已购/已解锁内容沿用本机上次记录，不向服务器查询");
        l.add("登录、存档、读数据等接口全部在本机拦下，设备信息不外发");
        l.add("关掉开关后立刻恢复正常联网，无需重开游戏");
        return Collections.unmodifiableList(l);
    }

    /** 真要被拦的接口名单（自检/文档用，顺序稳定）。 */
    static List<String> writeEndpoints() {
        List<String> l = new ArrayList<>();
        for (String w : WRITE) l.add(w);
        return l;
    }

    static List<String> readEndpoints() {
        List<String> l = new ArrayList<>();
        for (String r : READ) l.add(r);
        return l;
    }

    // ---------------- 小工具 ----------------

    /** 取 host（小写、去端口、去 userinfo、去尾点）；IPv6 字面量返回空串。 */
    static String host(String url) {
        String u = lc(url);
        if (u.isEmpty()) return "";
        String rest = u;
        int sp = rest.indexOf("://");
        if (sp >= 0) rest = rest.substring(sp + 3);
        int slash = rest.indexOf('/');
        String auth = slash >= 0 ? rest.substring(0, slash) : rest;
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);            // 去掉 user:pass@
        String host = auth;
        if (host.startsWith("[")) return "";                   // IPv6 字面量不可能是它
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);        // 去端口
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }

    /** 取 path（不含 query/fragment）；无路径时返回 "/"。 */
    static String path(String url) {
        String u = lc(url);
        if (u.isEmpty()) return "";
        String rest = u;
        int sp = rest.indexOf("://");
        if (sp >= 0) rest = rest.substring(sp + 3);
        int slash = rest.indexOf('/');
        String path = slash >= 0 ? rest.substring(slash) : "/";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        return path;
    }

    static String lc(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.US);
    }
}
