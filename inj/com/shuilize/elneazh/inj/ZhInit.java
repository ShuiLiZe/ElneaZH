package com.shuilize.elneazh.inj;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Injected into the localized game APK by 爱尔斯亚汉化器 (no root).
 * Runs inside the game process before the game starts and copies the Chinese master tables and the
 * Chinese map bundle (shipped as assets/elneazh/...) into the game's own directories:
 *   files/zh/            <- libil2cpp is patched to read master tables from /data/data/<pkg>/files/zh/
 *   UnityCache/Shared/map_large_en.android/<hash>/   (external + internal)
 *   UnityCache/Shared/<bundle>/<hash>/__data          Chinese tutorial figures / banners (see Bundles)
 * Existing non-table files from files/Contents (account token etc.) are carried over once.
 *
 * <p><b>主线程纪律（真机黑屏事故之后定的，别破坏）</b>：本类的 {@link #onCreate()} 跑在进程启动时的
 * 主线程上（ContentProvider），所以这里只允许做「本地小文件 + 纯内存」的活：
 * 建目录、复制本地资产、装 Java 层闸门。
 * <b>任何 native 加载 / il2cpp 解析 / 等待</b>一律交给后台线程（{@link NameGate#install}、
 * {@link OfflineGate#install} 都已经改成「立刻返回 + 后台做事」），主线程一个 {@code sleep}、
 * 一个 {@code await}、一个 {@code join} 都不许有。
 */
public final class ZhInit extends ContentProvider {
    private static final String TAG = "ElneaZH";
    private static final String MAP_DIR = "UnityCache/Shared/map_large_en.android/9e41473f5b511831105ea0f1695a843f";

    @Override public boolean onCreate() {
        long t0 = System.currentTimeMillis();
        // ① 崩溃保护必须在最前面：它决定这一轮到底允不允许加载 native。
        //    连击 2 次启动没活下来 → 之后自动不加载 native（Java 兜底照旧）。
        try { NativeGuard.boot(getContext()); } catch (Throwable t) { Log.w(TAG, "boot guard failed", t); }
        Backup.setCtx(getContext());
        // 换身份 v2：上一轮点了「更换身份」→ 这里（Unity 起来之前）按「清除数据」口径清空
        try { Backup.runPendingIdentityWipe(getContext()); } catch (Throwable t) { Log.e(TAG, "identity wipe failed", t); }
        // 上传方向的中文名映射（中文名 -> 固定拼音）。只加载词表，不动存档。
        try { NameFix.load(getContext()); } catch (Throwable t) { Log.e(TAG, "names load failed", t); }
        try { Backup.auto(getContext()); } catch (Throwable t) { Log.e(TAG, "auto backup failed", t); }
        try { deploy(getContext()); } catch (Throwable t) { Log.e(TAG, "deploy failed", t); }
        try { Bundles.run(getContext()); } catch (Throwable t) { Log.e(TAG, "bundles failed", t); }
        // 老存档里的英文人名转成中文（先备份原文件）。本地一切照旧是中文。
        try { SaveNames.run(getContext()); } catch (Throwable t) { Log.e(TAG, "names failed", t); }
        try { About.install(getContext()); } catch (Throwable t) { Log.e(TAG, "about failed", t); }
        // 上传方向的兜底闸：请求明文里的中文名换成拼音（单向，响应不处理）。
        try {
            if (!NetGuard.install(getContext())) Log.w(TAG, "NET gate: no Java-visible hook (see NetGuard)");
        } catch (Throwable t) { Log.w(TAG, "NET gate failed", t); }
        // 上传前名字闸门（native）：hook Cipher::EncryptRJ256，把明文里的中文名换成拼音。
        // install() 现在**立刻返回**，加载/解析/写槽全在后台 ElneaGate-install 线程里。
        try { NameGate.install(getContext(), 1); } catch (Throwable t) { Log.w(TAG, "gate install failed", t); }
        // 单机模式的闸门：常驻安装（是否真拦由 Offline.isOn() 现读决定，切开关不用重进游戏）
        try { NetBlocker.install(getContext()); } catch (Throwable t) { Log.w(TAG, "offline gate failed", t); }
        // 单机模式的 native 闸门（离线入口 + HTTP + 请求出口兜底），与名字闸门同一个 .so。
        // 同样立刻返回；单机**关着**时它只留兜底闸，六族 hook 一个都不装（见 eg_of_install）。
        try { OfflineGate.install(getContext(), 0); } catch (Throwable t) { Log.w(TAG, "offline native gate failed", t); }
        // 开关状态与 native 侧对账：真机「单机开着仍联网」第一步就查这个。日志标签 ElneaOffline。
        try {
            boolean on = Offline.isOn(getContext());
            Log.i("ElneaOffline", "boot: offline=" + on
                    + " stateMatch=" + OfflineGate.stateMatches(getContext())
                    + " enc=" + OfflineGate.encHooked()
                    + " " + OfflineGate.describe());
        } catch (Throwable t) { Log.w(TAG, "offline state check failed", t); }
        // 自检报告挪到后台：它要算拼音、跑分类器、读 native 状态，几十毫秒到几百毫秒，
        // 放在 onCreate 里就是白挨一发主线程阻塞（而且以前每次启动都跑一遍）。
        try { SelfCheck.rewriteAsync(getContext()); } catch (Throwable t) { Log.w(TAG, "selfcheck failed", t); }
        Log.i(TAG, "onCreate done in " + (System.currentTimeMillis() - t0) + "ms (all native work moved to background)");
        return true;
    }

    static void deploy(Context ctx) throws Exception {
        AssetManager am = ctx.getAssets();
        File zh = new File(ctx.getFilesDir(), "zh");
        if (!zh.isDirectory() && !zh.mkdirs()) throw new Exception("mkdir " + zh);
        // carry over files the game keeps next to its tables (only those we don't ship, only once)
        File old = new File(ctx.getFilesDir(), "Contents");
        File[] olds = old.listFiles();
        if (olds != null) for (File f : olds) {
            File t = new File(zh, f.getName());
            if (f.isFile() && !t.exists()) copy(new FileInputStream(f), t);
        }
        String[] tables = am.list("elneazh/Contents");
        if (tables != null) for (String n : tables) copyIfDiff(am, "elneazh/Contents/" + n, new File(zh, n));
        String[] map = am.list("elneazh/map");
        if (map != null) {
            File[] roots = { ctx.getExternalFilesDir(null), ctx.getFilesDir() };
            for (File root : roots) {
                if (root == null) continue;
                File d = new File(root, MAP_DIR);
                if (!d.isDirectory() && !d.mkdirs()) continue;
                for (String n : map) copyIfDiff(am, "elneazh/map/" + n, new File(d, n));
            }
        }
        Log.i(TAG, "deployed " + (tables == null ? 0 : tables.length) + " tables");
    }

    private static void copyIfDiff(AssetManager am, String asset, File dst) throws Exception {
        byte[] a = readAll(am.open(asset));
        if (dst.isFile() && dst.length() == a.length) {
            byte[] b = readAll(new FileInputStream(dst));
            if (java.util.Arrays.equals(a, b)) return;
        }
        File tmp = new File(dst.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(a); }
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new Exception("rename " + dst); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void copy(InputStream in, File dst) throws Exception {
        try (InputStream i = in; OutputStream o = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
