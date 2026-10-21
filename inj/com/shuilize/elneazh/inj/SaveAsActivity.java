package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 「导出到文件」：系统分享面板里没有能接 zip 的 App 时，由这里的保存页接管。
 *
 * 为什么必须有这一页：真机上点「导出存档」只弹出「没有能接收文件的应用」—— 分享面板是系统按
 * ACTION_SEND + mime 找目标，纯 zip 在不少 ROM 上一个目标都没有。这一页走
 * ACTION_CREATE_DOCUMENT（系统文件保存器，所有 ROM 标配），让玩家自己选位置。
 *
 * 界面只有两行字 + 一个按钮，不遮游戏太久；中途按返回 = 取消（不动任何东西）。
 */
public final class SaveAsActivity extends Activity {
    static final String TAG = "ElneaZH-SHARE";
    private Uri src;
    private String name = "存档.zip";
    private String mime = "application/octet-stream";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent i = getIntent();
            if (i != null) {
                src = i.getData();
                if (i.getType() != null) mime = i.getType();
                CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TITLE);
                if (t != null && t.length() > 0) name = t.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "saveas intent", t);
        }
        if (src == null) {
            Toast.makeText(this, "导出失败：拿不到文件", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // 先给自己读权限（我们是目标 App，uri 是通过 ACTION_SAVE_AS 带过来的）
        try { grantUriPermission(getPackageName(), src, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) {}
        start();
    }

    private void start() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, 0x4E5C);
        } catch (Throwable t) {
            Log.w(TAG, "create document failed", t);
            fallback();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Uri dst = (data == null) ? null : data.getData();
        if (res != RESULT_OK || dst == null) {
            // 玩家取消：不折腾，直接回游戏
            finish();
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(dst)) {
            if (in == null || out == null) throw new Exception("打开文件失败");
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            out.flush();
            Toast.makeText(this, "已导出：" + name, Toast.LENGTH_LONG).show();
            Log.i(TAG, "exported to " + dst);
        } catch (Throwable t) {
            Log.w(TAG, "copy to document failed", t);
            fallback();
        }
        finish();
    }

    /** 保存器都失败时，退到「写进备份目录」并演示路径。 */
    private void fallback() {
        try {
            File tmp = new File(Share.outDir(this), Share.safeName(name));
            try (InputStream in = getContentResolver().openInputStream(src);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new Exception("打不开源文件");
                byte[] buf = new byte[8192];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            }
            String p = Share.saveToFolder(this, tmp, name);
            Toast.makeText(this, p == null ? "导出失败：手机不让写文件" : "已存到 " + p,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "saveas fallback", t);
            Toast.makeText(this, "导出失败，可在「立即备份」里找回存档", Toast.LENGTH_LONG).show();
        }
    }
}
