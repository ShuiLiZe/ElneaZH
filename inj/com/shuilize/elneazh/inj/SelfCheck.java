package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 自检：把只在设备上能验的东西（名字表解析、拼音覆盖、上传方向的恒定性与兜底闸）写成一份报告，
 * 存进 app 私有目录 elneazh_selfcheck.txt，也从 logcat（tag ElneaZH-SELF）打一遍。
 *
 * 为什么需要它：这些代码跑在游戏进程里，CI 只能证明它编译通过；名字/拼音/替换的真实行为必须上机看。
 *   adb logcat -d -s ElneaZH-SELF ElneaZH-NET
 */
final class SelfCheck {
    private static final String TAG = "ElneaZH-SELF";

    static void run(Context ctx) {
        StringBuilder r = new StringBuilder();
        try {
            NameFix.load(ctx);
            r.append("names: ").append(NameFix.stats()).append('\n');
            r.append("pinyin table: ").append(Pinyin.tableSize()).append(" chars\n");

            // 1) 中文名 -> 拼音（上传方向），并验证「同一中文名恒定同一拼音」
            String[] zhs = {"铁蛋", "王小明", "李雷", "张三"};
            for (String z : zhs) {
                String a = NameFix.ascii(ctx, z);
                String b = NameFix.ascii(ctx, z);
                r.append("ascii ").append(z).append(" -> ").append(a)
                        .append(" (stable=").append(a != null && a.equals(b)).append(")\n");
            }

            // 2) 兜底闸：请求体值里的中文被换掉，纯 ASCII 原样返回
            String body = "acc=" + "中文名" + "&lang=en";
            r.append("gate: ").append(NetGuard.body(body)).append('\n');
            String plain = "acc=abcd&lang=en";
            r.append("gate passthrough: ").append(NetGuard.body(plain).equals(plain)).append('\n');

            // 3) 别名表规模（应随玩家取名增长，并随备份一起走）
            r.append("aliases: ").append(NameFix.aliasSnapshot().size()).append('\n');
            r.append("gate installed: ").append(NetGuard.installed()).append('\n');

            // 4) 单机模式：开关状态 + 分类器对几个真实接口的判断 + 闸门计数 + 分享链路
            r.append("offline: on=").append(Offline.isOn(ctx))
                    .append(" pref=").append(Offline.PREF).append('/').append(Offline.KEY).append('\n');
            String[] probes = {
                    "https://app-elnea.althi.co.jp/sns04/save2.php",
                    "https://app-elnea.althi.co.jp/sns04/savekpi.php",
                    "https://app-elnea.althi.co.jp/sns04/kpi.php",
                    "https://app-elnea.althi.co.jp/sns04/read.php",
                    "https://app-elnea.althi.co.jp/sns04/login.php",
                    "https://app-elnea.althi.co.jp/sns04/version.php",
                    "https://app-elnea.althi.co.jp/sns04/some_new_api.php",
                    "https://res-elnea.althi.co.jp/sns04/pack/asset.bundle",
                    "https://cdn-elnea.althi.co.jp/sns04/bundle/AssetBundle",
                    "https://cdn.example.com/x.zip",
            };
            for (String u : probes) {
                r.append("  classify ").append(Offline.describe(u))
                        .append(" write=").append(Offline.isWriteRequest(u))
                        .append(" asset=").append(Offline.isAssetDownload(u))
                        .append(" blockedWhenOffline=").append(!NetBlocker.allow(u, true))
                        .append(" allowedWhenOnline=").append(NetBlocker.allow(u, false))
                        .append('\n');
            }
            r.append("offline gate installed: ").append(NetBlocker.installed()).append('\n');
            r.append("offline blocked so far: ").append(NetBlocker.hits()).append('\n');
            r.append("offline asset downloads blocked: ").append(NetBlocker.assetHits()).append('\n');
            r.append("offline asset downloads allowed: ").append(NetBlocker.assetAllowed()).append('\n');
            r.append("offline download hint: ").append(Offline.downloadHint()).append('\n');
            r.append("share: ").append(Share.describe()).append('\n');
            r.append("name table lines: ").append(Share.nameTableText(NameFix.aliasSnapshot()).split("\n").length).append('\n');
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            r.append("FAILED: ").append(sw).append('\n');
        }
        String text = r.toString();
        Log.i(TAG, "\n" + text);
        try (FileOutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), "elneazh_selfcheck.txt"))) {
            o.write(text.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private SelfCheck() {}
}
