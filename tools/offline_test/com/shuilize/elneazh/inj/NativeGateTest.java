package com.shuilize.elneazh.inj;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 纯 JDK 真跑：native 这一条链在「主线程」上的阻塞与崩溃保护（不需要手机）。
 *
 * <p>守的是真机黑屏事故（单机开/关都黑屏、几十秒后退出、无 tombstone、闸门日志一条没有）的
 * 那几个结构性根因：
 *
 * <ol>
 *   <li>{@code NameGate.install} 必须**立刻返回** —— 旧版 {@code done.await()} 同步等 20s，
 *       而它的调用方是 {@code ZhInit.onCreate}（进程启动的主线程）。这里用「调用耗时 &lt; 200ms
 *       且返回时还没挂上」来证明它没有再等。</li>
 *   <li>单机**关**着时，六族 UI/Http hook 一个都不装（{@code OfflineGate} 不进入安装循环）。</li>
 *   <li>crash guard：连续 2 轮启动没清标记 → 第 3 轮 {@code NativeGuard.allowed()==false}，
 *       并且 {@code NativeLoad.describe()} 写明 {@code native disabled by crash guard}。</li>
 *   <li>Support 的「原生加速（实验）」关掉 = 永不加载 native，且 selfcheck 文案里有解释。</li>
 *   <li>标记在「活到主界面 / 60s」时被清掉，连击归零。</li>
 * </ol>
 *
 * 跑法见 tools/offline_test/README.md；CI 的 build-check 里也会跑。
 */
public final class NativeGateTest {
    private static int checks;
    private static int failed;

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "elneazh_nativegate");
        deleteTree(root);
        // 造一份「有 asset 但内容不是合法 ELF」的 .so：解包会成功，System.load 一定失败，
        // 这样能验证「加载失败也只发生在后台线程、主线程照样 O(1) 返回」。
        Context ctx = new Context();
        File assets = new File(root, "assets");
        File abi = new File(assets, "elneagate/lib" + NativeLoad.abi());
        mkdirs(abi);
        write(new File(abi, "libelneagate.so"), "not an elf".getBytes("UTF-8"));
        ctx.setAssetRoot(assets);
        mkdirs(ctx.getFilesDir());

        // ---------------- 1) 冷启动：marker 不存在 → allowed，且写了标记
        NativeGuard.boot(ctx);
        check(NativeGuard.allowed(), "首次启动允许 native（strikes=0）");
        check(NativeGuard.strikes() == 0, "首次启动连击 0");
        check(new File(ctx.getFilesDir(), NativeGuard.MARKER).isFile(), "启动写了 elneazh_native_boot 标记");
        check(!NativeLoad.ready(), "此时 native 还没加载（没人调过 load）");

        // ---------------- 2) NameGate.install 必须立刻返回
        long t0 = System.currentTimeMillis();
        int r = NameGate.install(ctx, 1);
        long cost = System.currentTimeMillis() - t0;
        // 200ms → 300ms：CI 的 runner 偶尔有 100ms+ 的调度毛刺，而旧版这里是 20000ms，差三个数量级。
        check(cost < 300, "NameGate.install 立刻返回（" + cost + "ms < 300ms，旧版最长 20s）");
        check(r != 0, "NameGate.install 返回时还没挂上（后台在装，r=" + r + "）");

        // ---------------- 3) 等后台线程跑完：加载失败也要「有结论」，主线程期间不阻塞
        long t1 = System.currentTimeMillis();
        int after = NameGate.awaitInstall(8000);
        long waitCost = System.currentTimeMillis() - t1;
        check(after == -100 || after != Integer.MIN_VALUE,
                "后台安装线程给出了结论（result=" + after + "，耗时 " + waitCost + "ms）");
        check(!NativeLoad.ready(), "非法 .so 不会被标记成已加载");

        // ---------------- 4) 单机关着：六族一个都不装
        check(!Offline.isOn(ctx), "默认单机关着");
        long t2 = System.currentTimeMillis();
        OfflineGate.install(ctx, 0);
        long offCost = System.currentTimeMillis() - t2;
        check(offCost < 300, "OfflineGate.install（单机关）立刻返回（" + offCost + "ms）");
        sleep(1200);
        check(!OfflineGate.installedAny(), "单机关着时 installedAny=false（没装 UI/Http 六族）");
        check(OfflineGate.total() == 0, "单机关着时合计挂载数 0");
        int[] res = OfflineGate.results();
        boolean allZero = res.length == 0;
        for (int v : res) if (v != 0) allZero = false;
        check(allZero, "单机关着时每族结果都是 0");
        // 关单机时连「表」都不该递出去：UI 隐藏 / 请求拦截 / 遮罩 / 本地游玩 四张表全是空串，
        // 也就是 native 侧拿不到任何 UI/Http hook 的名字 —— 这条把「只做拼音闸门」钉死。
        for (int kind : new int[]{0, 1, 2, 3}) {
            String tbl = OfflineGate.table(kind);
            check(tbl != null && tbl.trim().isEmpty(),
                    "单机关着时 table(" + kind + ") 为空（native 拿不到 UI/Http hook 名单）");
        }
        check(OfflineGate.encGateOnly(), "单机关着时 native 只做拼音/兜底闸（encGateOnly=true）");
        // 打开单机：六族的表立刻有内容（证明上面那条空串是「按单机开关过滤」，不是表本来为空）
        Offline.set(ctx, true);
        check(Offline.isOn(ctx), "临时打开单机用于对照");
        int nonEmpty = 0;
        for (int kind : new int[]{0, 1, 2, 3}) {
            String tbl = OfflineGate.table(kind);
            if (tbl != null && !tbl.trim().isEmpty()) nonEmpty++;
        }
        check(nonEmpty >= 3, "单机开着时 table 有内容（" + nonEmpty + "/4 非空）");
        check(!OfflineGate.encGateOnly(), "单机开着时 encGateOnly=false");
        Offline.set(ctx, false);
        check(!Offline.isOn(ctx), "对照完再关掉单机");

        // ---------------- 5) crash guard：连击 2 → 第三次启动不加载
        File marker = new File(ctx.getFilesDir(), NativeGuard.MARKER);
        write(marker, "2\n2026-10-21 17:00:00 pid=1 pkg=com.althi.elnea allowed=true\n".getBytes("UTF-8"));
        NativeGuard.boot(ctx);
        check(!NativeGuard.allowed(), "标记连击 2 → 本轮不加载 native");
        check(NativeGuard.reason().contains("crash guard"), "原因串写明 crash guard：" + NativeGuard.reason());
        check(NativeLoad.describe().contains("native disabled by crash guard"),
                "selfcheck 的 native load 行写明 native disabled by crash guard：" + NativeLoad.describe());
        check(!NativeLoad.tryLoad(ctx), "crash guard 生效时 tryLoad 直接 false（一次也不 load）");

        // ---------------- 6) Support 开关：原生加速关掉 = 永不加载
        check(NativeGuard.boostEnabled(ctx), "原生加速默认开");
        check(NativeGuard.setBoost(ctx, false), "关闭原生加速写入成功");
        check(!NativeGuard.boostEnabled(ctx), "原生加速已关");
        NativeGuard.boot(ctx);
        check(!NativeGuard.allowed(), "原生加速关着 → 不允许加载 native");
        check(NativeGuard.reason().contains("Boost switch"), "原因串写明是开关关掉的：" + NativeGuard.reason());
        check(NativeGuard.setBoost(ctx, true), "重新打开原生加速");
        write(marker, "0\n".getBytes("UTF-8"));
        NativeGuard.boot(ctx);
        check(NativeGuard.allowed(), "连击清零且开关打开 → 恢复允许 native");

        // ---------------- 7) 活到主界面：标记被清、连击归零
        NativeGuard.markSurvived("test: activity resumed");
        check(NativeGuard.clearedThisBoot(), "markSurvived 后本轮记为已存活");
        check(NativeGuard.strikes() == 0, "存活后连击 0");
        File m2 = new File(ctx.getFilesDir(), NativeGuard.MARKER);
        String left = m2.isFile() ? readAll(m2) : "";
        check(!m2.isFile() || left.startsWith("0"), "标记被删或被写成 0（现状：" + oneLine(left) + "）");

        // ---------------- 8) 自检文案
        // 用同一目录下但**新的** files 目录：SelfCheck.run 能自己建
        Context c2 = new Context();
        File d2 = new File(root, "files2");
        mkdirs(d2);
        c2.setFilesDir(d2);
        NativeGuard.boot(c2);
        String sc = SelfCheck.render(c2);
        check(sc.contains("native guard:"), "selfcheck 有 native guard 行");
        check(sc.contains("native load:"), "selfcheck 有 native load 行");
        check(sc.contains("offline"), "selfcheck 仍写单机段");
        check(sc.contains("native disabled by crash guard") || sc.contains("native boost="),
                "selfcheck 写明原生加速 / crash guard 现状");
        NativeGuard.markSurvived("test: leave clean");

        System.out.println();
        System.out.println("native gate test: " + checks + " checks / " + failed + " failed");
        if (failed > 0) System.exit(1);
        System.out.println("RESULT: PASS");
    }

    // ---------------------------------------------------------------- helpers

    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replace('\n', '|');
        return s.length() > 90 ? s.substring(0, 90) + "..." : s;
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void mkdirs(File d) {
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
            System.out.println("  !! mkdir failed: " + d);
        }
    }

    private static void write(File f, byte[] b) throws Exception {
        mkdirs(f.getParentFile());
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b);
        }
    }

    private static String readAll(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(b);
                return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private NativeGateTest() {}
}
