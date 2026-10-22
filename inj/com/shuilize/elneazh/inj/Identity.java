package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 「更换游戏身份」.
 *
 * 抓包事实：旧存档 + 新 uuid 登录时 login.php 回 “already login!”，所以只抹掉 uuid 没用 ——
 * 服务器是拿旧存档里的账号信息去认人的。换成全新身份必须是「整套本地数据重来」：
 *   1) 先 Backup.make(a, "换身份前")，返回非 null 才继续（备份里必须含 uuid / login_key，
 *      见 Backup.backupForIdentity：它还会校验 zip 里真有 name="uuid" 或 name="login_key"）；
 *   2) 清空游戏本地数据 —— 只删「这份备份登记过、且不在保留白名单里」的文件：
 *      files/、shared_prefs/、ext/ 下的存档与账号文件全删；
 *      汉化主表（zh/Contents 里我们投放的表）、我们自己的 elneazh_* 设置、UnityCache/il2cpp 缓存留下；
 *   3) 结束进程，重开由 UuidPlugin 生成新 uuid、走新号流程。
 * 旧身份随时能从「存档备份」里恢复那份「换身份前」—— restore 会把备份里的 uuid / login_key 一起写回。
 * 全程 try/catch，失败只提示，不动数据。
 */
final class Identity {
    static final String KEY = "uuid";

    /** Confirmation drawn in the same game-popup style as {@link Support} (cream panel, brown frame, orange buttons). */
    static void confirm(final Activity a, final Runnable onCancel) { confirm(a, onCancel, true); }

    /** full=true 更换身份（清数据）；false 只更换身份（保留存档，测试功能）。 */
    static void confirm(final Activity a, final Runnable onCancel, final boolean full) {
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
            title.setText(full ? "更换身份（清数据）" : "只更换身份（保留存档）");
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
            tv.setText(full
                    ? "只在游戏一直卡「维护中」或强制要求输入账号时使用。\n\n"
                    + "点「确定更换」后会先把当前存档（含游戏身份）备份成一份「换身份前」，"
                    + "成功后游戏关闭；重新打开时会像系统「清除数据」一样把本地数据清干净"
                    + "（汉化和已下载的资源包保留，不用重下），相当于一个全新账号。\n\n"
                    + "旧存档不会丢：在「存档管理」里恢复那份「换身份前」备份，就会连原来的身份一起回去。\n\n"
                    + "更换后游戏会自动关闭，请重新打开（第一次打开会稍慢）。"
                    : "测试用：只换掉设备码 / uuid / login_key / 账号块等身份，存档原样保留。\n\n"
                    + "先自动备份一份「换身份前」（含身份），然后游戏关闭；重新打开时在游戏启动前清掉身份。\n\n"
                    + "注意：存档和旧设备码是绑在一起的，保留存档换身份很可能仍然卡登录 —— 这正是要测的。"
                    + "想回去就在「存档管理」完整恢复那份「换身份前」。");
            card.addView(tv, new LinearLayout.LayoutParams(-1, -2));

            card.addView(Support.button(a, d, "确定更换", Support.ORANGE, Support.ORANGE_DARK, Color.WHITE, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ok[0] = true;
                    try { dlg.dismiss(); } catch (Throwable ignored) {}
                    reset(a, full);
                }
            }));
            card.addView(Support.button(a, d, "取消", Support.BROWN, Color.rgb(0x4E, 0x2F, 0x14), Color.WHITE, new View.OnClickListener() {
                @Override public void onClick(View v) { try { dlg.dismiss(); } catch (Throwable ignored) {} }
            }));

            dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override public void onDismiss(android.content.DialogInterface di) { if (!ok[0] && onCancel != null) onCancel.run(); }
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

    /**
     * 备份 → 校验身份 → 清空本地数据 → 结束进程。备份失败就不动任何数据（只提示）。
     * 整个过程在后台线程跑，UI 线程只弹提示。
     */
    static void reset(final Activity a) { reset(a, true); }

    static void reset(final Activity a, final boolean full) {
        Backup.toast(a, "正在备份当前存档…");
        final Activity act = a;
        new Thread(new Runnable() { @Override public void run() {
            Uri bk;
            try {
                bk = Backup.backupForIdentity(act);
            } catch (Throwable t) {
                Log.e(Support.TAG, "identity backup", t);
                bk = null;
            }
            if (bk == null) {
                // 备份没成（或备份里没有 uuid/login_key）—— 中止，一个字节都不动
                final String msg = "备份失败，未更换（当前存档原样保留）";
                Log.e(Support.TAG, "identity: aborted, data untouched");
                try {
                    act.getWindow().getDecorView().post(new Runnable() { @Override public void run() {
                        Backup.toast(act, msg);
                    }});
                } catch (Throwable t) { Backup.toast(act, msg); }
                return;
            }
            try {
                Backup.scheduleIdentityWipe(act, full);
                Log.i(Support.TAG, "identity: wipe scheduled for next start, killing now");
            } catch (Throwable t) {
                Log.e(Support.TAG, "identity schedule", t);
                Backup.toast(act, "更换失败，已保留备份：" + t.getMessage());
                return;
            }
            Backup.toast(act, full ? "已备份。游戏会关闭，重新打开时自动清干净并换成新身份"
                    : "已备份。游戏会关闭，重新打开时只换身份、存档保留");
            // 不走 finishAffinity：那会触发 Unity onPause，把内存里的旧身份/存档写回磁盘。
            try { Thread.sleep(1500); } catch (Throwable ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }}).start();
    }

    private static void kill(Activity a) {
        try { a.finishAffinity(); } catch (Throwable ignored) {}
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }
}
