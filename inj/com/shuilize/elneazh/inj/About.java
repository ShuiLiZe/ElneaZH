package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * One-time "汉化说明" dialog inside the localized game: free, QQ group, optional WeChat donation QR.
 * Shown a few seconds after the first activity resumes, once per KEY (bump KEY to show again).
 * Everything is wrapped so a failure can never affect the game.
 */
final class About {
    static final String QQ = "790184852";
    private static final String PREFS = "elneazh_about";
    private static final String KEY = "shown_v1";
    private static boolean pending = true;

    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!(app instanceof Application)) return;
        if (app.getSharedPreferences(PREFS, 0).getBoolean(KEY, false)) return;
        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(final Activity a) {
                if (!pending) return;
                pending = false;
                ((Application) app).unregisterActivityLifecycleCallbacks(this);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try { if (!a.isFinishing()) show(a, true); } catch (Throwable t) { Log.e("ElneaZH", "about", t); }
                    }
                }, 6000);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    static void show(final Activity a, boolean markShown) {
        final SharedPreferences sp = a.getSharedPreferences(PREFS, 0);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        TextView tv = new TextView(a);
        tv.setTextColor(Color.rgb(40, 40, 40));
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.25f);
        tv.setText("本汉化完全免费，禁止倒卖。\n\n"
                + "汉化交流 QQ 群：" + QQ + "\n反馈没汉化的地方、获取新版，都来群里。\n\n"
                + "觉得好玩，可以微信扫码请汉化作者喝杯奶茶，全凭自愿，不捐也一样能玩。");
        box.addView(tv);
        Bitmap qr = null;
        try (InputStream in = a.getAssets().open("elneazh/about/qr.png")) { qr = BitmapFactory.decodeStream(in); } catch (Throwable ignored) {}
        if (qr != null) {
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(qr);
            iv.setAdjustViewBounds(true);
            int s = (int) (200 * d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.topMargin = (int) (12 * d);
            box.addView(iv, lp);
        }
        AlertDialog dlg = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("艾尔尼亚王国 · 中文汉化")
                .setView(box)
                .setPositiveButton("开始游戏", null)
                .setNeutralButton("复制群号", (di, w) -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("QQ群", QQ));
                        Toast.makeText(a, "已复制 QQ 群号 " + QQ, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                })
                .create();
        dlg.show();
        if (markShown) sp.edit().putBoolean(KEY, true).apply();
    }
}
