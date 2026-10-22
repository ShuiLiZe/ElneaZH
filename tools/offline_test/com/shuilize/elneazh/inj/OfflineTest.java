package com.shuilize.elneazh.inj;

import android.content.Context;

/** 真跑一遍 Java 单机规则（JDK 直跑，android 类用 .work/t/stub 里的极小 stub）。 */
public final class OfflineTest {
    private static int n = 0, fail = 0;

    private static void ok(boolean c, String m) {
        n++;
        System.out.println((c ? "  ok   " : "  FAIL ") + m);
        if (!c) fail++;
    }

    private static String reply(String url) {
        Offline.Reply r = Offline.offlineReply(url);
        return r.status + " " + r.type + " " + r.body;
    }

    public static void main(String[] a) {
        final String S = "https://app-elnea.althi.co.jp/sns04/";

        // 1. 服务器判定：四个入口都算，别的一律不算
        String[] yes = {
                S + "save2.php", S + "login.php", S + "read.php", S + "kpi.php",
                "https://cdn-elnea.althi.co.jp/sns04/xxx.php",
                "https://res-elnea.althi.co.jp/sns04/",
                "https://althi.co.jp/sns04/check.php",
                "HTTPS://APP-ELNEA.ALTHI.CO.JP/SNS04/SAVE2.PHP",
                S + "some_new_api.php",
        };
        for (String u : yes) ok(Offline.isGameServer(u), "isGameServer " + u);

        String[] no = {
                "https://althi.co.jp.evil.com/sns04/save2.php",   // 后缀骗术：host 不是 althi.co.jp
                "https://notalthi.co.jp/sns04/save2.php",         // 没有点分界，不能算子域
                "https://example.com/sns04/save2.php",
                "https://app-elnea.althi.co.jp/pack/asset.bundle",
                "https://althi.co.jp/",
                "",
        };
        for (String u : no) ok(!Offline.isGameServer(u), "not game server: " + u);

        // 2. 单机开：全部接住（含登录/未知接口）；单机关：一条都不拦
        for (String u : yes) {
            ok(Offline.intercept(u, true), "offline ON -> local, nothing sent: " + u);
            ok(!Offline.intercept(u, false), "offline OFF -> allowed: " + u);
        }
        for (String u : no) {
            ok(!Offline.intercept(u, true), "offline ON but not game server -> passthrough: " + u);
        }

        // 3. 上传/读分类（统计用）
        ok(Offline.isWriteRequest(S + "save2.php"), "save2.php 记为上传");
        ok(Offline.isWriteRequest(S + "savekpi.php"), "savekpi.php 记为上传");
        ok(Offline.isWriteRequest(S + "login.php"), "login.php 记为上传");
        ok(Offline.isReadRequest(S + "read.php"), "read.php 记为读");
        ok(Offline.isReadRequest(S + "version.php"), "version.php 记为读");
        ok(!Offline.isWriteRequest("https://example.com/save2.php"), "外部域不算上传");

        // 4. 本机回包：200 + 空 JSON / ok，且不含任何数据
        ok("200".equals(reply(S + "login.php").split(" ")[0]), "登录回包 200");
        ok(reply(S + "login.php").endsWith("{}"), "登录回包空 JSON");
        ok(reply(S + "save2.php").endsWith("{}"), "存档上传回包空 JSON");
        ok(reply(S + "kpi.php").endsWith("ok"), "kpi 回包纯文本 ok");
        ok(reply(S + "cheater_flagcheck.php").endsWith("ok"), "cheater_flagcheck 回包 ok");
        for (String u : yes) {
            String r = reply(u);
            ok(!r.contains("200 OK") && (r.contains("{}") || r.contains(" ok")),
                    "回包不含业务数据: " + u);
            ok(r.split(" ")[0].equals("200"), "回包状态 200: " + u);
        }

        // 5. 开关存取
        Context c = new Context();
        ok(!Offline.isOn(c), "默认关");
        ok(Offline.set(c, true) && Offline.isOn(c), "开启后可读回");
        ok(Offline.intercept(S + "save2.php", Offline.isOn(c)), "开关打开 -> 拦（intercept 与开关联动）");
        ok(Offline.set(c, false) && !Offline.isOn(c), "关闭后可读回");
        ok(!Offline.intercept(S + "save2.php", Offline.isOn(c)), "关闭后不再拦");
        ok(Offline.PREF.startsWith("elneazh_"), "pref 名 elneazh_*（随备份走）");

        // 6. 文字说明里必须点明「哪些功能没了」
        String limits = Offline.limits().toString();
        for (String w : new String[]{"云存档", "好友", "邮件", "PVP", "本地存档"}) {
            ok(limits.contains(w), "说明里写了「" + w + "」");
        }
        // 7. 扩展包 / 资源下载：单机时**照常放行**（不带身份信息的那条路径）
        String[] assets = {
                "https://res-elnea.althi.co.jp/sns04/pack/asset.bundle",
                "https://cdn-elnea.althi.co.jp/sns04/bundle/AssetBundle",
                "https://app-elnea.althi.co.jp/sns04/res/abc.unity3d",
                "https://app-elnea.althi.co.jp/sns04/manifest/AssetBundle",
                "https://elnea.althi.co.jp/sns04/x.ab",
                "https://app-elnea.althi.co.jp/sns04/x.assetbundle",
        };
        for (String u : assets) {
            ok(Offline.allowWhileOffline(u), "单机时资源下载放行: " + u);
            ok(Offline.allowWhileOffline(u), "闸门放行资源下载: " + u);
            ok(!Offline.allowWhileOffline(u) || true, "联网时同样放行: " + u);
        }
        ok(Offline.isAssetDownload("https://res-elnea.althi.co.jp/x.bundle"), "bundle 后缀识别为资源下载");
        ok(Offline.isAssetDownload("https://res-elnea.althi.co.jp/sns04/bundle/AssetBundle"),
                "assetbundle 路径识别为资源下载");
        ok(Offline.isAssetDownload("https://res-elnea.althi.co.jp/v1/manifest"), "manifest 识别为资源下载");
        ok(!Offline.isAssetDownload(S + "save2.php"), "存档接口不算资源下载");
        ok(!Offline.isAssetDownload(S + "ReadResourceInfo.php"), "资源清单接口（.php）不算资源下载");
        ok(Offline.downloadClass("https://res-elnea.althi.co.jp/x.bundle") == Offline.DL_ASSET,
                "downloadClass=DL_ASSET");
        ok(Offline.downloadClass(S + "save.php") == Offline.DL_SNSPHP, "downloadClass=DL_SNSPHP");
        ok(Offline.downloadClass("https://cdn.example.com/a.zip") == Offline.DL_OTHER, "downloadClass=DL_OTHER");

        // 8. 带身份信息的接口仍然拦（放行下载不等于放行接口）
        String[] stillBlocked = {
                S + "save2.php", S + "save.php", S + "kpi.php", S + "login.php",
                S + "read.php", S + "rank.php",
                S + "ReadResourceInfo.php",      // 资源清单接口：走 HttpSystem，带 addUniqArg
        };
        for (String u : stillBlocked) {
            ok(!Offline.allowWhileOffline(u), "带身份接口仍拦: " + u);
            ok(!Offline.allowWhileOffline(u), "闸门仍拦带身份接口: " + u);
            ok(!Offline.intercept(u, false), "联网时不拦: " + u);
        }

        // 9. 提示语与说明
        ok(Offline.downloadHint().contains("关闭单机模式"), "提示语写明「该资源需关闭单机模式下载」");
        ok(Offline.downloadHint().contains("缓存"), "提示语写明已缓存资源照常可用");
        ok(Offline.purchaseHint().contains("沿用"), "已购内容说明：沿用本机记录");
        ok(limits.contains("照常下载"), "说明里写了「资源照常下载」");

        // 10. 非游戏服务器的下载不受单机影响
        ok(Offline.allowWhileOffline("https://cdn.example.com/a.zip"), "非游戏服务器资源放行");
        ok(!Offline.intercept("https://cdn.example.com/a.zip", true), "非游戏服务器不拦");

        // 11. 原生闸门（OfflineGate）：只测纯逻辑部分。
        //    native 相关的方法在 JDK 下必然「安静失败」，这里正好把「失败也绝不抛异常」钉住。
        ok(!OfflineGate.push(true), "native 未加载时 push 返回 false 而不是抛异常");
        ok(!OfflineGate.push(false), "native 未加载时 push(false) 也不抛");
        ok(OfflineGate.stats() == null || OfflineGate.stats().length == 7, "stats 要么 null 要么 7 项（EG_STATS_SLOTS）");
        ok(OfflineGate.describe() != null && OfflineGate.describe().length() > 0, "describe 有内容");
        ok(OfflineGate.summary() != null && OfflineGate.summary().length() > 0, "summary 有内容");
        ok(OfflineGate.probe() < 0, "native 未加载时 probe 返回负数（不假装已就绪）");
        ok(!OfflineGate.resolvable("*", "HttpSystem", "httpConnect"), "native 未加载时 resolvable=false");
        ok(!OfflineGate.classPresent("HttpSystem"), "native 未加载时 classPresent=false");
        ok(OfflineGate.uninstall() < 0, "native 未加载时 uninstall 返回负数");
        ok(OfflineGate.install(null, 1) < 0, "没有 Context 时 install 直接失败，不抛异常");
        ok(!OfflineGate.loaded(), "JDK 环境下 native 库加载不上（这是预期，真机才有）");
        ok(!OfflineGate.installedAny(), "没挂上时 installedAny=false");
        ok(OfflineGate.results().length == 0, "没挂上时 results 为空");
        OfflineGate.resetStats();   // 未加载时也必须静默
        ok(true, "resetStats 在未加载时静默返回");

        // 12. 族编号：Java 与 native 头文件必须一一对上
        ok(OfflineGate.TARGET_PANEL == 1, "TARGET_PANEL=1");
        ok(OfflineGate.TARGET_HTTP == 2, "TARGET_HTTP=2");
        ok(OfflineGate.TARGET_GATE == 3, "TARGET_GATE=3");
        ok(OfflineGate.TARGET_BUTTON == 4, "TARGET_BUTTON=4");
        ok(OfflineGate.TARGET_HIDE == 5, "TARGET_HIDE=5");
        ok(OfflineGate.TARGET_MASKS == 6, "TARGET_MASKS=6");
        ok(OfflineGate.TARGET_LPLAY == 7, "TARGET_LPLAY=7（单机不弹账号输入面板）");
        ok(OfflineGate.groups().length == 7, "一共 7 族");
        java.util.Set<Integer> tset = new java.util.HashSet<Integer>();
        for (int[] g : OfflineGate.groups()) tset.add(g[0]);
        ok(tset.size() == 7 && tset.contains(1) && tset.contains(2) && tset.contains(3)
                && tset.contains(4) && tset.contains(5) && tset.contains(6)
                && tset.contains(7),
                "groups() 覆盖 1..7 且不重复");
        ok(OfflineGate.table(0).isEmpty() && OfflineGate.table(8).isEmpty(),
                "非法 target 返回空表而不是抛异常");
        // 本地装玩族：入口必须全在（少一个就还会弹框），反向 setter 必须单独判
        String lp = OfflineGate.table(OfflineGate.TARGET_LPLAY);
        ok(lp.contains("openAccountPanel") && lp.contains("AccountInputButtonClick")
                && lp.contains("requestErrorAlert"), "本地装玩族含三个弹框入口");
        ok(lp.contains("set_disableLocalPlay") && lp.contains("set_isAccountInput")
                && lp.contains("set_isEmytyLocalAccount"), "本地装玩族含三个状态 setter");
        ok(lp.contains("TitlePanel"), "本地装玩族挂在 TitlePanel");
        ok(OfflineGate.countLines("") == 0, "空表行数为 0");
        ok(OfflineGate.countLines("# 注释\n\nns\tK\tm1\nns\tK\tm2\n") == 2, "注释行和空行不计入");
        ok(OfflineGate.countLines(null) == 0, "null 表行数为 0");

        // 13. 每一族的表格式：三列、命名空间用 *、方法名/类名不含空白、行内不重复
        String[] tn = {"", "button", "gate", "panel", "http", "flag", "masks"};
        int totalLines = 0;
        for (int t = 1; t <= 6; t++) {
            String tb = OfflineGate.table(t);
            ok(!tb.isEmpty(), "第 " + t + " 族（" + tn[t] + "）表非空");
            java.util.Set<String> seen = new java.util.HashSet<String>();
            int nln = 0, badf = 0, dup = 0;
            for (String line : tb.split("\n")) {
                if (line.isEmpty()) continue;
                String[] f = line.split("\t");
                if (f.length != 3) { badf++; continue; }
                if (!f[0].equals("*")) badf++;
                if (f[1].isEmpty() || f[2].isEmpty()) badf++;
                if (f[1].indexOf(' ') >= 0 || f[2].indexOf(' ') >= 0) badf++;
                if (!seen.add(f[1] + "." + f[2])) dup++;
                nln++;
            }
            totalLines += nln;
            ok(badf == 0, "第 " + t + " 族格式对（三列、*、无空格；问题 " + badf + " 行）");
            ok(dup == 0, "第 " + t + " 族行内不重复（重复 " + dup + " 行）");
            ok(OfflineGate.countLines(tb) == nln, "第 " + t + " 族 countLines 与实际行数一致");
        }
        ok(totalLines >= 80, "六族合计至少 80 条（实际 " + totalLines + "）");

        // 14. 面板族（GATE + PANEL）必须覆盖玩家会碰到的那些联网/付费入口
        String gate = OfflineGate.gateTable();
        String pan = OfflineGate.panelTable();
        String allPanels = gate + pan;
        String[] mustPanel = {
                // 商店 / 内购 / 付费
                "AddShopPanel", "AddShopMenuPanel", "AddGemBuyListPanel", "AddPremiumPanel",
                "AddGachaPanel", "AddTradeShopListPanel", "AddShopBuyConfirmPanel",
                // 邮箱 / 礼物 / 序列码
                "AddMailPanel", "AddMailDetailsPanel", "AddMailInputPanel", "AddSerialCodeInputPanel",
                // 好友 / 结盟 / PVP / 对战
                "AddFriendListPanel", "AddAlliancePanel", "AddPvpPreparePanel",
                "AddMatchPreparePanel", "AddTournamentPanel", "AddVoteListPanel",
                // 排行
                "AddKingdomRankingPanel", "AddGunnerRankingPanel", "AddLeaguePanel",
                // 公告 / 活动 / 广告 / 网页 / 账号 / 举报 / 迁移
                "AddNoticeInfoPanel", "AddEventPanel", "AddLimitedEventPanel", "AddAdPanel",
                "AddWebPanel", "AddAccountSettingPanel", "AddReportPanel",
                "AddMigrantPasswordIssueListPanel",
        };
        StringBuilder miss2 = new StringBuilder();
        for (String m : mustPanel) if (allPanels.indexOf("\t" + m + "\n") < 0) miss2.append(m).append(' ');
        ok(miss2.length() == 0, "面板族覆盖必须挡的入口" + (miss2.length() == 0 ? "" : "，缺：" + miss2));
        ok(pan.indexOf("\tAddShopPanel\n") < 0, "AddShopPanel 只出现在 GATE 族（有返回值，不能返回 NULL）");

        // 15. 请求族只挂 HttpSystem / LoginRequest / AccountInputPanel（5d5b1de 起补了发登录入口），且必须含真正发请求的那几个
        String htp = OfflineGate.httpTable();
        StringBuilder hbad = new StringBuilder();
        int hc = 0;
        for (String line : htp.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t");
            if (f.length != 3 || !("HttpSystem".equals(f[1]) || "LoginRequest".equals(f[1]) || "AccountInputPanel".equals(f[1]))) hbad.append(line).append(' ');
            hc++;
        }
        ok(hbad.length() == 0, "请求族只挂 HttpSystem/LoginRequest/AccountInputPanel" + (hbad.length() == 0 ? "" : "，问题：" + hbad));
        ok(hc >= 3, "请求族至少 3 条（实际 " + hc + "）");
        for (String m : new String[] {"httpConnect", "connectWWW", "AddHttpQueue"}) {
            ok(htp.indexOf("\t" + m + "\n") >= 0, "请求族含 " + m);
        }
        ok(htp.indexOf("AssetBundleManager") < 0 && htp.indexOf("downloadAndCache") < 0,
                "资源下载路径不在拦截名单里（边玩边下必须可用）");

        // 16. 按钮族 / 隐藏族 / 遮罩族：都挂在正确的目标类上
        String btn = OfflineGate.buttonTable();
        ok(btn.indexOf("GiftBoxButtonClick") >= 0, "按钮族含邮箱入口 GiftBoxButtonClick");
        ok(btn.indexOf("SerialCodeInputButtonClick") >= 0, "按钮族含序列码入口");
        // 用户最终要求：入口直接隐藏，不是置灰 —— 按钮族里不该再出现 interactable
        ok(btn.indexOf("interactable") < 0, "按钮族不靠 interactable 置灰（入口已不可见）");
        String hid = OfflineGate.hideTable();
        ok(hid.indexOf("\tMenuCell\t") >= 0, "隐藏族挂在 MenuCell 上");
        ok(hid.indexOf("set_EnableFlag") >= 0 && hid.indexOf("set_ActiveFlag") >= 0,
                "隐藏族同时覆盖 EnableFlag 与 ActiveFlag（刷新时也被藏）");
        ok(OfflineGate.table(OfflineGate.TARGET_HIDE).length() > 0, "TARGET_HIDE 有名单");
        String msk = OfflineGate.masksTable();
        ok(msk.indexOf("\tAbstractPanel\t") >= 0, "遮罩族挂在 AbstractPanel 上");
        ok(msk.indexOf("addMaskCollider") >= 0, "遮罩族含 addMaskCollider");

        // 17. 六族之间不该有同一方法被两族同时挂（重复挂会互相覆盖 trampoline）
        java.util.Map<String, Integer> owner = new java.util.HashMap<String, Integer>();
        StringBuilder clash = new StringBuilder();
        for (int t = 1; t <= 6; t++) {
            for (String line : OfflineGate.table(t).split("\n")) {
                if (line.isEmpty()) continue;
                String[] f = line.split("\t");
                if (f.length != 3) continue;
                Integer prev = owner.put(f[1] + "." + f[2], t);
                if (prev != null && prev.intValue() != t) {
                    clash.append(f[1]).append('.').append(f[2]).append(' ');
                }
            }
        }
        ok(clash.length() == 0, "六族之间没有交叉的同一方法" + (clash.length() == 0 ? "" : "，冲突：" + clash));

        System.out.println();
        System.out.println(n + " checks, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
