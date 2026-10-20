package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

/**
 * 「更换游戏身份」. The game logs in with PlayerPrefs["uuid"] (UuidPlugin.GetUUID). If the server has put that identity
 * under 维护 / forces the account panel, the player can drop it here: we back up the save first (so the old identity can
 * be brought back from 存档备份), delete PlayerPrefs["uuid"], and kill the process. On the next start GetUUID finds no
 * cached id and (libil2cpp patch, so_patch_zh.py) makes a fresh random one instead of the device-derived id.
 * Nothing else in the save is touched, so the player never has to clear the game's data. Never throws.
 */
final class Identity {
    static final String KEY = "uuid";

    /** Confirmation drawn in the same game-popup style as {@link Support} (cream panel, brown frame, orange buttons). */
    static void confirm(final Activity a, final Runnable onCancel) {
        try {
            final float d = a.getResources().getDisplayMetrics().density;
            final Dialog dlg = new Dialog(a);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dlg.setCanceledOnTouchOutside(true);
            final boolean[] ok = {false};

            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setBackground(Support.round(Support.CREAM, Support.BROWN, 3 * d, 14 * d));
            int pad = (int) (18 * d);
            card.setPadding(pad, (int) (14 * d), pad, (int) (16 * d));

            TextView title = new TextView(a);
            title.setText("更换游戏身份");
            title.setTextColor(Support.BROWN);
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
            tv.setTextColor(Support.BROWN_TEXT);
            tv.setTextSize(14);
            tv.setLineSpacing(0, 1.25f);
            tv.setText("只在游戏一直卡「维护中」或强制要求输入账号时使用。\n\n"
                    + "会换成一个新的游戏身份（服务器上相当于新号）。更换前自动备份当前存档，"
                    + "想换回原来的身份，在「存档备份」里恢复「换身份前」那份即可。\n\n"
                    + "更换后游戏会自动关闭，请重新打开。");
            card.addView(tv, new LinearLayout.LayoutParams(-1, -2));

            card.addView(Support.button(a, d, "确定更换", Support.ORANGE, Support.ORANGE_DARK, Color.WHITE, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ok[0] = true;
                    try { dlg.dismiss(); } catch (Throwable ignored) {}
                    reset(a);
                }
            }));
            card.addView(Support.button(a, d, "取消", Support.BROWN, Color.rgb(0x4E, 0x2F, 0x14), Color.WHITE, new View.OnClickListener() {
                @Override public void onClick(View v) { try { dlg.dismiss(); } catch (Throwable ignored) {} }
            }));

            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface di) { if (!ok[0] && onCancel != null) onCancel.run(); }
            });
            ScrollView sv = new ScrollView(a);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(card, new FrameLayout.LayoutParams(-1, -2));
            dlg.setContentView(sv);
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int sw = a.getResources().getDisplayMetrics().widthPixels;
                w.setLayout(Math.min((int) (sw * 0.86f), (int) (360 * d)), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dlg.show();
        } catch (Throwable t) {
            Log.e(Support.TAG, "identity confirm", t);
            if (onCancel != null) onCancel.run();
        }
    }

    static void reset(final Activity a) {
        try { Backup.make(a, "换身份前"); } catch (Throwable t) { Log.w(Support.TAG, "identity backup", t); }
        try {
            SharedPreferences sp = a.getSharedPreferences(a.getPackageName() + ".v2.playerprefs", Context.MODE_PRIVATE);
            Object old = sp.getAll().get(KEY);
            SharedPreferences.Editor e = sp.edit();
            for (String k : sp.getAll().keySet()) if (KEY.equals(k) || KEY.equals(Uri.decode(k))) e.remove(k);
            if (!e.commit()) throw new Exception("commit failed");
            if (old != null) {
                a.getSharedPreferences("elneazh_identity", Context.MODE_PRIVATE).edit()
                        .putString("prev_" + System.currentTimeMillis(), String.valueOf(old)).commit();
            }
        } catch (Throwable t) {
            Log.e(Support.TAG, "identity reset", t);
            Backup.toast(a, "更换失败：" + t.getMessage());
            return;
        }
        Backup.toast(a, "已更换游戏身份，请重新打开游戏");
        try {
            a.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() { kill(a); }
            }, 1500);
        } catch (Throwable t) { kill(a); }
    }

    private static void kill(Activity a) {
        try { a.finishAffinity(); } catch (Throwable ignored) {}
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }
}
