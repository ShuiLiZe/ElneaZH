package com.shuilize.elneazh.inj;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分享 / 导出。
 *
 * 三个功能，各走各的 try/catch，失败只 Toast：
 *   1. {@link #shareBackup} 把 Backup 生成的 zip 用系统分享面板发出去（微信 / QQ / 文件 …）。
 *   2. {@link #shareNameTable} 把名字对照表（中文↔上传用 ASCII）导出成 txt 分享。
 *   3. 导入走 Backup.pick / Backup.confirmRestore（已有流程，先自动备份当前存档再恢复）。
 *
 * 真机「点导出 / 点导出名字对照表都提示没有能接收文件的应用」的根因与对策：
 *  - 只给 ACTION_SEND + EXTRA_STREAM 在某些 ROM 上没人认（尤其 zip：文件管理器不接、QQ 走自己的私有接口），
 *    所以这里按「谁能接」分档：进程内专门的「存到文件」目标 → 系统分享面板 → 文件管理器打开。
 *  - ClipData 必须设：7.0+ 的 URI 授权是跟着 ClipData 走的，只加 FLAG_GRANT_READ_URI_PERMISSION
 *    不给 ClipData，接收方拿到 uri 读不了，于是自己也判断「打不开」。
 *  - content:// 由游戏自带 androidx.core.content.FileProvider 出（authorities=jp.co.althi.elnea.en.zhfiles，
 *    路径配置复用 @7f100002 image_share_filepaths 的 files-path image_provider/）。
 *  - 全部失败时把文件拷到 内部存储根目录/艾尔尼亚存档备份/（拿不到全文件权限就到
 *    Documents/艾尔尼亚存档备份/），Toast 里写清完整路径 —— 玩家用「导入存档」就能选到它。
 */
final class Share {
    private static final String TAG = "ElneaZH-SHARE";
    static final String AUTHORITY = "jp.co.althi.elnea.en.zhfiles";
    private static final String OUT = "image_provider";

    /** 「存到文件」目标：本进程里的 {@link SaveAsActivity}，不弹系统面板，直接写选定目录。 */
    static final String ACTION_SAVE_AS = "com.shuilize.elneazh.inj.SAVE_AS";

    private Share() {}

    /** 分享用的暂存目录：filesDir/image_provider/（= @7f100002 里声明的 files-path）。 */
    static File outDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), OUT);
        if (!d.isDirectory() && !d.mkdirs()) Log.w(TAG, "outDir mkdir failed: " + d);
        return d;
    }

    /** 把任意来源（raw 路径 / content uri）拷进 files/image_provider/，返回副本。失败返回 null。 */
    static File stage(Context ctx, Uri src, String name) {
        if (ctx == null || src == null) return null;
        File dst = new File(outDir(ctx), safeName(name));
        try (java.io.InputStream in = open(ctx, src);
             OutputStream o = new FileOutputStream(dst)) {
            if (in == null) return null;
            byte[] b = new byte[8192];
            int k;
            while ((k = in.read(b)) > 0) o.write(b, 0, k);
            return dst;
        } catch (Throwable t) {
            Log.w(TAG, "stage failed: " + src, t);
            try { dst.delete(); } catch (Throwable ignored) {}
            return null;
        }
    }

    private static java.io.InputStream open(Context ctx, Uri src) throws Exception {
        String s = src.toString();
        if ("file".equals(src.getScheme()) || src.getScheme() == null) {
            String p = "file".equals(src.getScheme()) ? src.getPath() : s;
            return new java.io.FileInputStream(new File(p));
        }
        return ctx.getContentResolver().openInputStream(src);
    }

    /**
     * 把一个文件送出去：依次试「存到文件」目标 → 系统分享面板 → 文件管理器打开。
     *
     * @return true 表示有一个界面真的起来了（不代表对方真收到）；全都失败返回 false，调用方再兜底拷盘。
     */
    static boolean send(Context ctx, File f, String mime, String subject, String chooserTitle) {
        if (ctx == null || f == null || !f.isFile()) return false;
        Uri u = contentUri(ctx, f);
        if (u == null) return false;
        String type = mime == null ? "application/octet-stream" : mime;

        if (startSaveAs(ctx, u, type, f.getName())) return true;

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(type);
        send.putExtra(Intent.EXTRA_STREAM, u);
        send.putExtra(Intent.EXTRA_TITLE, f.getName());
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newUri(ctx.getContentResolver(), f.getName(), u));
        // 显式给每个能接的 App 授权：部分 ROM 不看 ClipData，只认逐个 grant。
        grantTo(ctx, send, u);

        Intent chooser = Intent.createChooser(send, chooserTitle == null ? "导出到" : chooserTitle);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        chooser.setClipData(ClipData.newUri(ctx.getContentResolver(), f.getName(), u));
        if (!(ctx instanceof android.app.Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(chooser);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "chooser failed", t);
        }

        // 退路：用文件管理器打开（有些 ROM 没有分享目标，但有查看器）。
        try {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(u, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setClipData(ClipData.newUri(ctx.getContentResolver(), f.getName(), u));
            if (!(ctx instanceof android.app.Activity)) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(view);
            return true;
        } catch (Throwable t2) {
            Log.w(TAG, "view fallback failed", t2);
        }
        return false;
    }

    /** 「存到文件」目标在不在（本 APK 自己声明的 Activity，正常情况下一定在）。 */
    private static boolean startSaveAs(Context ctx, Uri u, String type, String name) {
        try {
            Intent i = new Intent(ACTION_SAVE_AS);
            i.setClass(ctx, SaveAsActivity.class);
            i.setDataAndType(u, type);
            i.putExtra(Intent.EXTRA_TITLE, name);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newUri(ctx.getContentResolver(), name, u));
            if (!(ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            Log.i(TAG, "save-as opened for " + name);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "save-as unavailable: " + t);
            return false;
        }
    }

    private static void grantTo(Context ctx, Intent i, Uri u) {
        try {
            List<ResolveInfo> all = ctx.getPackageManager().queryIntentActivities(i, 0);
            for (ResolveInfo ri : all) {
                if (ri.activityInfo == null) continue;
                try {
                    ctx.grantUriPermission(ri.activityInfo.packageName, u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Log.i(TAG, "grant read to " + ri.activityInfo.packageName);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "grantTo", t);
        }
    }

    /**
     * 兜底：把文件拷到玩家找得到的地方，返回**完整路径**；连这个都失败返回 null。
     *
     * 位置顺序：内部存储根目录/艾尔尼亚存档备份/（有「所有文件」权限时）→ Documents/艾尔尼亚存档备份/。
     */
    static String saveToFolder(Context ctx, File f, String name) {
        if (ctx == null || f == null || !f.isFile()) return null;
        String safe = safeName(name);
        File dst = null;
        try {
            if (Backup.rootOk(ctx)) {
                File d = Backup.rootDir();
                if (d.isDirectory() || d.mkdirs()) dst = new File(d, safe);
            }
        } catch (Throwable ignored) {
        }
        if (dst == null) {
            try {
                File d = new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOCUMENTS), Backup.DIR);
                if (d.isDirectory() || d.mkdirs()) dst = new File(d, safe);
            } catch (Throwable ignored) {
            }
        }
        if (dst == null) {
            // 最后的退路：留在 App 自己的目录（文件管理器里也能找到）
            File d = outDir(ctx);
            dst = new File(d, safe);
        }
        try {
            try (java.io.InputStream in = new java.io.FileInputStream(f);
                 OutputStream o = new FileOutputStream(dst)) {
                byte[] b = new byte[8192];
                int k;
                while ((k = in.read(b)) > 0) o.write(b, 0, k);
            }
            // MediaStore 里补一条，安卓 11+ 的文件管理器和相册类 App 才立刻看得见
            try {
                android.media.MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, null, null);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "saved to " + dst);
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "saveToFolder failed: " + dst, t);
            return null;
        }
    }

    // ---------------- 1. 导出存档 ----------------

    /**
     * 导出存档：先把当前存档打一份新备份（Backup.make），再把它分享出去。
     * 打完就分享而不是分享「上一次」的旧包 —— 玩家点「导出」时想给的总是当下这份。
     *
     * 打 zip 要读几十 MB，放后台线程；分享面板必须回主线程开。
     */
    static void shareBackup(final android.app.Activity a) {
        if (a == null) return;
        try {
            Backup.toast(a, "正在打包存档…");
            new Thread(new Runnable() { @Override public void run() {
                File staged = null;
                String err = null;
                try {
                    String name = Backup.make(a, "导出");
                    if (name == null) {
                        err = "导出失败：存档备份没生成，游戏存档未受影响";
                    } else {
                        Uri u = Backup.uriOf(a, name);
                        staged = u == null ? null : stage(a, u, name);
                        if (staged == null) err = "导出失败：备份没能放到临时目录";
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "shareBackup worker", t);
                    err = "导出出错：" + t;
                }
                final File f = staged;
                final String e = err;
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    try {
                        if (e != null) { Backup.toast(a, e); return; }
                        if (send(a, f, "application/zip", "艾尔尼亚存档 " + f.getName(), "导出存档到")) return;
                        String p = saveToFolder(a, f, f.getName());
                        Backup.toast(a, p == null ? "导出失败：手机不让写文件，可用「立即备份」"
                                : "已存到 " + p);
                    } catch (Throwable t) {
                        Log.w(TAG, "shareBackup ui", t);
                        Backup.toast(a, "导出出错：" + t);
                    }
                }});
            }}).start();
        } catch (Throwable t) {
            Log.w(TAG, "shareBackup", t);
            Backup.toast(a, "导出出错：" + t);
        }
    }

    // ---------------- 2. 导出名字对照表 ----------------

    /** 名字对照表正文（纯函数，离线可测）：中文<TAB>上传用 ASCII，按中文排序。 */
    static String nameTableText(Map<String, String> aliases) {
        StringBuilder b = new StringBuilder();
        b.append("# 艾尔尼亚汉化 · 名字对照表（中文 -> 上传用名字）\n");
        b.append("# NPC 名换回原版英文原名；玩家自己取的中文名换成拼音。服务端存的是右边这串 ASCII。\n");
        b.append("# 本机显示始终是中文；这张表只说明「同一个名字发出去长什么样」。\n");
        b.append("# 中文\t上传用\n");
        if (aliases == null || aliases.isEmpty()) {
            b.append("# （还没有记过名字：进游戏取一个中文名，或改一次名，这里就会出现）\n");
            return b.toString();
        }
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<String, String>(aliases);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            b.append(clean(e.getKey())).append('\t').append(clean(e.getValue())).append('\n');
        }
        b.append("# 共 ").append(sorted.size()).append(" 条\n");
        return b.toString();
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** 导出名字对照表 txt 并分享。 */
    static void shareNameTable(final android.app.Activity a) {
        File f = null;
        try {
            Map<String, String> m = new LinkedHashMap<String, String>();
            try { m.putAll(NameFix.aliasSnapshot()); } catch (Throwable ignored) {}
            try { m.putAll(NameFix.npcSnapshot()); } catch (Throwable ignored) {}
            String text = nameTableText(m);
            f = new File(outDir(a), "艾尔尼亚名字对照表.txt");
            try (OutputStream o = new FileOutputStream(f)) {
                o.write(text.getBytes("UTF-8"));
            }
            if (send(a, f, "text/plain", "艾尔尼亚名字对照表", "导出名字对照表到")) return;
            String p = saveToFolder(a, f, f.getName());
            Backup.toast(a, p == null ? "导出失败：手机不让写文件" : "已存到 " + p);
        } catch (Throwable t) {
            Log.w(TAG, "shareNameTable", t);
            Backup.toast(a, "导出名字对照表出错：" + t);
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 把一个文件变成能给别的 App 读的 content:// uri。
     *
     * 用**游戏自己**的 FileProvider（androidx.core.content.FileProvider，APK 里本来就有这个类，
     * provider 声明由 tools/manifest_zh.py 写进游戏 manifest），authorities=jp.co.althi.elnea.en.zhfiles。
     * 走 ContentResolver.call("getUriForFile") 的公开协议而不是直接引用 FileProvider 类：
     * classes3.dex 里没有 androidx 依赖，直接 import 会逼着把整个 androidx.core 塞进注解 dex。
     * 拿不到就返回 null —— 绝不回退 file://（7.0+ 必炸 FileUriExposedException）。
     */
    static Uri contentUri(Context ctx, File f) {
        if (ctx == null || f == null) return null;
        File real;
        try {
            real = f.getCanonicalFile();
        } catch (Throwable t) {
            real = f.getAbsoluteFile();
        }
        // 先确认文件真在 files/image_provider/ 下：FileProvider 只肯给声明过的目录出 uri，
        // 不在那儿会抛 IllegalArgumentException（以前这个异常被吞掉，最后退成 file:// 直接失败）。
        File dir = outDir(ctx);
        String base, path;
        try {
            base = dir.getCanonicalPath() + File.separator;
            path = real.getCanonicalPath();
        } catch (Throwable t) {
            base = dir.getAbsolutePath() + File.separator;
            path = real.getAbsolutePath();
        }
        if (!path.startsWith(base)) {
            Log.w(TAG, "not under provider dir: " + path);
            return null;
        }
        try {
            android.os.Bundle r = ctx.getContentResolver().call(
                    Uri.parse("content://" + AUTHORITY), "getUriForFile", path, null);
            if (r != null) {
                String s = r.getString("uri");
                if (s != null && !s.isEmpty()) {
                    Uri u = Uri.parse(s);
                    Log.i(TAG, "content uri " + u);
                    return u;
                }
            }
            Log.w(TAG, "provider returned no uri for " + path);
        } catch (Throwable t) {
            Log.w(TAG, "contentUri via provider failed: " + path, t);
        }
        return null;
    }

    static String safeName(String n) {
        if (n == null || n.trim().isEmpty()) return "elneazh.bin";
        String s = n.replace('/', '_').replace('\\', '_').replace(':', '_').replace('\0', '_').trim();
        return s.isEmpty() ? "elneazh.bin" : s;
    }

    /** 供 SelfCheck 打印的一行摘要。 */
    static String describe() {
        List<String> l = Offline.writeEndpoints();
        return "share authority=" + AUTHORITY + ", staged dir=files/" + OUT + ", blockable endpoints=" + l.size();
    }

    /** 对照表空表也至少给个说明，不返回 null（SelfCheck 断言用）。 */
    static Map<String, String> emptyAliases() {
        return new LinkedHashMap<String, String>();
    }
}
