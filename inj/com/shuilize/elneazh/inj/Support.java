package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * 「交流与支持」dialog drawn in the game's own popup style (cream panel, brown frame, orange buttons).
 * Opened from the title screen Support → 「交流群与支持」 button (libil2cpp patch makes that button call
 * Application.OpenURL("elneazh://support"), which lands in {@link SupportActivity}) and once on first launch
 * ({@link About}). Every action is wrapped; nothing here may crash the game.
 */
final class Support {
    static final String TAG = "ElneaZH";
    static final String QQ = "790184852";
    static final String QQ_LINK = "https://qun.qq.com/universal-share/share?ac=1&authKey=2oXkeJiLXvUlnYoZp%2FCWwlD%2Fkvb3f69RpT6%2BePF8jahMWgvsuOZr5BW53OFi%2FX9j&busi_data=eyJncm91cENvZGUiOiI3OTAxODQ4NTIiLCJ0b2tlbiI6IkcvckZJRVFKWnZGanZ6YlhZRkNXbHJRM3kwb21kc1hWZzlOd21yYlA1dlI1UnRxWGthdzVqRnBRUFZhRVRlTDgiLCJ1aW4iOiIyNzcwMjEzOTE2In0%3D&data=sWsjEEx2_zevFLauPt59ifUwPK3xkIuZdmdJBr4RNbJzma9BghIQ407l-FwVZUGo53DJDh98NYQO90mOdeJjoA&svctype=4&tempid=h5_group_info";
    /** Original developer support address (the game's own 「Contact us」 web form sends mail here). */
    static final String DEV_MAIL = "elnea_support_en@althi.co.jp";

    // game popup palette
    static final int CREAM = Color.rgb(0xFB, 0xF1, 0xDA);
    static final int CREAM_DARK = Color.rgb(0xF0, 0xDF, 0xBC);
    static final int BROWN = Color.rgb(0x7A, 0x4B, 0x24);
    static final int BROWN_TEXT = Color.rgb(0x55, 0x36, 0x1C);
    static final int ORANGE = Color.rgb(0xF3, 0x93, 0x2A);
    static final int ORANGE_DARK = Color.rgb(0xC8, 0x6A, 0x12);

    static Dialog build(final Activity a) {
        final float d = a.getResources().getDisplayMetrics().density;
        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCanceledOnTouchOutside(true);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(round(CREAM, BROWN, 3 * d, 14 * d));
        int pad = (int) (18 * d);
        card.setPadding(pad, (int) (14 * d), pad, (int) (16 * d));

        TextView title = new TextView(a);
        title.setText("交流与支持");
        title.setTextColor(BROWN);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, (int) (8 * d));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(a);
        line.setBackgroundColor(Color.argb(0x66, 0x7A, 0x4B, 0x24));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, Math.max(1, (int) (1.5f * d)));
        llp.bottomMargin = (int) (10 * d);
        card.addView(line, llp);

        TextView tv = new TextView(a);
        tv.setTextColor(BROWN_TEXT);
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.25f);
        tv.setText("本汉化完全免费，禁止倒卖。\n"
                + "反馈没汉化的地方、获取新版，欢迎进汉化交流 QQ 群：" + QQ + "。");
        card.addView(tv, new LinearLayout.LayoutParams(-1, -2));

        card.addView(button(a, d, "加入 QQ 群", ORANGE, ORANGE_DARK, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) { joinQQ(a); }
        }));

        Bitmap qr = null;
        try (InputStream in = a.getAssets().open("elneazh/about/qr.png")) { qr = BitmapFactory.decodeStream(in); } catch (Throwable ignored) {}
        if (qr != null) {
            TextView cap = new TextView(a);
            cap.setTextColor(BROWN_TEXT);
            cap.setTextSize(13);
            cap.setGravity(Gravity.CENTER);
            cap.setLineSpacing(0, 1.2f);
            cap.setText("觉得好玩，可以微信扫码请汉化作者喝杯奶茶。\n全凭自愿，不捐也一样能玩。");
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.topMargin = (int) (14 * d);
            card.addView(cap, cp);
            FrameLayout qrBox = new FrameLayout(a);
            qrBox.setBackground(round(Color.WHITE, BROWN, 2 * d, 8 * d));
            int qp = (int) (6 * d);
            qrBox.setPadding(qp, qp, qp, qp);
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(qr);
            iv.setAdjustViewBounds(true);
            int s = (int) (170 * d);
            qrBox.addView(iv, new FrameLayout.LayoutParams(s, s));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
            qlp.topMargin = (int) (8 * d);
            card.addView(qrBox, qlp);
        }

        card.addView(button(a, d, "存档备份", ORANGE, ORANGE_DARK, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) { Backup.manage(a); }
        }));
        TextView bk = new TextView(a);
        bk.setTextColor(BROWN_TEXT);
        bk.setTextSize(12);
        bk.setGravity(Gravity.CENTER);
        bk.setText("每次启动自动备份，卸载游戏也不会丢。");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = (int) (6 * d);
        card.addView(bk, bp);

        card.addView(button(a, d, "联系原作者（英文邮件）", CREAM_DARK, BROWN, BROWN, new View.OnClickListener() {
            @Override public void onClick(View v) { mailDev(a); }
        }));
        card.addView(button(a, d, "关闭", BROWN, Color.rgb(0x4E, 0x2F, 0x14), Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) { try { dlg.dismiss(); } catch (Throwable ignored) {} }
        }));

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(card, new FrameLayout.LayoutParams(-1, -2));
        dlg.setContentView(sv);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int sw = a.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min((int) (sw * 0.9f), (int) (380 * d));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dlg;
    }

    static TextView button(Context c, float d, String text, int fill, int stroke, int textColor, View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, round(stroke, stroke, 2.5f * d, 10 * d));
        st.addState(new int[0], round(fill, stroke, 2.5f * d, 10 * d));
        b.setBackground(st);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (46 * d));
        lp.topMargin = (int) (10 * d);
        b.setLayoutParams(lp);
        return b;
    }

    static GradientDrawable round(int fill, int stroke, float strokeW, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, (int) strokeW), stroke);
        g.setCornerRadius(radius);
        return g;
    }

    static void restore(Activity a) {
        try {
            if (a instanceof SupportActivity) { Backup.pick(a); return; }
            Intent i = new Intent(a, SupportActivity.class);
            i.putExtra("restore", true);
            a.startActivity(i);
        } catch (Throwable t) { Log.w(TAG, "restore", t); }
    }

    static void joinQQ(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(QQ_LINK));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            copy(a, "QQ群", QQ, null);
        } catch (Throwable t) {
            Log.w(TAG, "join qq", t);
            copy(a, "QQ群", QQ, "没能打开加群页面，已复制群号 " + QQ + "，请在 QQ 里搜索加群");
        }
    }

    static void mailDev(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + DEV_MAIL));
            i.putExtra(Intent.EXTRA_SUBJECT, "Elnea Kingdom inquiry");
            i.putExtra(Intent.EXTRA_TEXT, "------- Please describe the content of your inquiry (in English) -------\n\n\n");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            copy(a, "邮箱", DEV_MAIL, "没有邮件 App，已复制原作者邮箱 " + DEV_MAIL);
        } catch (Throwable t) {
            Log.w(TAG, "mail dev", t);
            copy(a, "邮箱", DEV_MAIL, "已复制原作者邮箱 " + DEV_MAIL);
        }
    }

    static void copy(Context c, String label, String text, String toast) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (Throwable ignored) {}
        if (toast != null) try { Toast.makeText(c, toast, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }
}
