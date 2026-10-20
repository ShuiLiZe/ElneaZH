package com.shuilize.elneazh.inj;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分享 / 导出（第三批）。
 *
 * 三个功能，各走各的 try/catch，失败只 Toast：
 *   1. {@link #shareBackup} 把 Backup 生成的 zip 用系统分享面板发出去（微信 / QQ / 文件 …）。
 *   2. {@link #shareNameTable} 把 elneazh_names 别名表（中文↔拼音）导出成 txt 分享。
 *   3. 导入走 Backup.pick / Backup.confirmRestore（已有流程，先自动备份当前存档再恢复）。
 *
 * 文件给出去用 FileProvider（authorities=jp.co.althi.elnea.en.zhfiles，由 tools/manifest_zh.py 往游戏
 * manifest 里加）。分享用的文件一律先拷一份到 files/image_provider/ 再出 uri：
 *  - FileProvider 只肯给「路径配置里声明过的目录」出 uri，而 /sdcard/艾尔尼亚存档备份 里的原始 zip 不在其中；
 *  - 路径配置直接用 APP 里已有的 @xml/image_share_filepaths（files-path name="image_provider_images"
 *    path="image_provider/"），所以落到 filesDir/image_provider/ 最省事：不用新增 xml 资源，
 *    也不怕原包被系统清缓存或第三方清理工具扫掉。
 */
final class Share {
    private static final String TAG = "ElneaZH-SHARE";
    static final String AUTHORITY = "jp.co.althi.elnea.en.zhfiles";
    private static final String OUT = "image_provider";

    private Share() {}

    /** 分享用的暂存目录：filesDir/image_provider/（= @xml/image_share_filepaths 里声明的 files-path）。 */
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
     * 系统分享面板发一个文件。返回 true 表示系统接了（不代表对方真收到）。
     * 没有任何 App 能接就退到「用文件管理器打开」（部分极简 ROM 没有分享目标）。
     */
    static boolean send(Context ctx, File f, String mime, String subject, String chooserTitle) {
        if (ctx == null || f == null || !f.isFile()) return false;
        Uri u = contentUri(ctx, f);
        if (u == null) return false;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime == null ? "application/octet-stream" : mime);
        send.putExtra(Intent.EXTRA_STREAM, u);
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, chooserTitle == null ? "分享" : chooserTitle);
        if (!(ctx instanceof android.app.Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(chooser);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "chooser failed", t);
            try {
                Intent view = new Intent(Intent.ACTION_VIEW).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!(ctx instanceof android.app.Activity)) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(view);
                return true;
            } catch (Throwable t2) {
                Log.w(TAG, "view fallback failed", t2);
                return false;
            }
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
                        if (!send(a, f, "application/zip", "艾尔尼亚存档 " + f.getName(), "导出存档到"))
                            Backup.toast(a, "导出失败：没有能接收文件的应用");
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

    /** 名字对照表正文（纯函数，离线可测）：中文<TAB>拼音，按中文排序。 */
    static String nameTableText(Map<String, String> aliases) {
        StringBuilder b = new StringBuilder();
        b.append("# 艾尔尼亚汉化 · 名字对照表（中文 -> 上传用拼音）\n");
        b.append("# 上传存档时游戏会把名字里的汉字换成右边这串 ASCII；服务端存的就是它。\n");
        b.append("# 恢复备份/换手机后本表随 shared_prefs/elneazh_names 一起回来。\n");
        b.append("# 中文\t拼音\n");
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
            String text = nameTableText(NameFix.aliasSnapshot());
            f = new File(outDir(a), "艾尔尼亚名字对照表.txt");
            try (OutputStream o = new FileOutputStream(f)) {
                o.write(text.getBytes("UTF-8"));
            }
            boolean ok = send(a, f, "text/plain", "艾尔尼亚名字对照表", "导出名字对照表到");
            if (!ok) Backup.toast(a, "导出失败：没有能接收文件的应用");
        } catch (Throwable t) {
            Log.w(TAG, "shareNameTable", t);
            Backup.toast(a, "导出名字对照表出错：" + t);
            try { if (f != null) f.delete(); } catch (Throwable ignored) {}
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
     */
    static Uri contentUri(Context ctx, File f) {
        if (ctx == null || f == null) return null;
        try {
            android.os.Bundle r = ctx.getContentResolver().call(
                    Uri.parse("content://" + AUTHORITY),
                    "getUriForFile", f.getAbsolutePath(), null);
            if (r != null) {
                String s = r.getString("uri");
                if (s != null && !s.isEmpty()) return Uri.parse(s);
            }
        } catch (Throwable t) {
            Log.w(TAG, "contentUri via provider failed: " + f, t);
        }
        // 退路：file:// ——Android 7 以下能用；7 以上会抛 FileUriExposedException，捕获后返回 null，
        // 调用方只 Toast，不影响游戏。
        try {
            return Uri.fromFile(f);
        } catch (Throwable t) {
            return null;
        }
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
