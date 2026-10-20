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
        // 7. 扩展包 / 资源下载：单机时一律拦，且说明里写明要先关单机
        String[] assets = {
                S + "pack/asset.bundle",
                S + "assetbundle/manifest",
                S + "res/abc.unity3d",
                "https://app-elnea.althi.co.jp/sns04/ReadResourceInfo.php",
                "https://res-elnea.althi.co.jp/sns04/bundle/AssetBundle",
        };
        for (String u : assets) {
            ok(Offline.intercept(u, true), "单机时资源下载被拦: " + u);
            ok(!Offline.allowWhileOffline(u), "allowWhileOffline=false: " + u);
        }
        ok(Offline.isAssetDownload(S + "pack/asset.bundle"), "bundle 后缀识别为资源下载");
        ok(Offline.isAssetDownload(S + "assetbundle/manifest"), "assetbundle 路径识别为资源下载");
        ok(!Offline.isAssetDownload(S + "save2.php"), "存档接口不算资源下载");
        ok(Offline.downloadClass(S + "x.bundle") == Offline.DL_ASSET, "downloadClass=DL_ASSET");
        ok(Offline.downloadClass(S + "save.php") == Offline.DL_SNSPHP, "downloadClass=DL_SNSPHP");
        ok(Offline.downloadClass("https://cdn.example.com/a.zip") == Offline.DL_OTHER, "downloadClass=DL_OTHER");
        ok(Offline.downloadHint().contains("关闭单机模式"), "下载提示语写明要先关单机");
        ok(limits.contains("已下载"), "说明里写了「已下载的照常可用」");

        // 非游戏服务器的下载不受单机影响（别的 App 的静态资源）
        ok(Offline.allowWhileOffline("https://cdn.example.com/a.zip"), "非游戏服务器资源放行");
        ok(!Offline.intercept("https://cdn.example.com/a.zip", true), "非游戏服务器不拦");

        System.out.println();
        System.out.println(n + " checks, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
