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

            // 2b) 存档区块名字槽：0x01 + 长度 + UTF-8 —— 中文名换拼音，其它字节必须不动
            byte[] raw = UploadNamesSample.hex(UploadNamesSample.RAW_HEX);
            byte[] want = UploadNamesSample.hex(UploadNamesSample.EXPECT_HEX);
            byte[] got = UploadNames.filterNames(raw, new UploadNames.Mapper() {
                @Override public String ascii(String zh) {
                    for (int i = 0; i < UploadNamesSample.ZH.length; i++) {
                        if (UploadNamesSample.ZH[i].equals(zh)) return UploadNamesSample.ASCII[i];
                    }
                    return null;
                }
            });
            r.append("names slots: ").append(UploadNames.countNames(raw))
                    .append(" -> filtered crc ").append(UploadNamesSample.crc(got))
                    .append(want.length == got.length && java.util.Arrays.equals(want, got) ? " OK" : " MISMATCH")
                    .append('\n');

            // 2c) 整段 ws_* 区块：base64(zlib) 往返 + 长度只许变短
            byte[] packed = UploadNames.deflate(raw);
            String b64 = UploadNames.Base64.encode(packed);
            String b64b = UploadNames.filterOne(b64, new UploadNames.Mapper() {
                @Override public String ascii(String zh) {
                    for (int i = 0; i < UploadNamesSample.ZH.length; i++) {
                        if (UploadNamesSample.ZH[i].equals(zh)) return UploadNamesSample.ASCII[i];
                    }
                    return null;
                }
            });
            boolean round = false;
            if (!b64b.equals(b64)) {
                byte[] back = UploadNames.inflate(UploadNames.Base64.decode(b64b));
                round = back != null && java.util.Arrays.equals(back, want) && b64b.length() <= b64.length();
            }
            r.append("blob round trip: ").append(b64.length()).append(" -> ").append(b64b.length())
                    .append(round ? " OK" : " NOT-CHANGED-OR-BAD").append('\n');

            // 3) 别名表规模（应随玩家取名增长，并随备份一起走）
            r.append("aliases: ").append(NameFix.aliasSnapshot().size()).append('\n');
            r.append("gate installed: ").append(NetGuard.installed()).append('\n');
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
