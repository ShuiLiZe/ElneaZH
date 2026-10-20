package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.AlertDialog;
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

    static void confirm(final Activity a, final Runnable onCancel) {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(a);
            b.setTitle("更换游戏身份");
            b.setMessage("只在游戏一直卡「维护中」或强制要求输入账号时使用。\n\n"
                    + "会生成一个新的游戏身份（服务器上相当于新号）。更换前会自动备份当前存档，"
                    + "想换回原来的身份，在「存档备份」里恢复这份备份即可。\n\n"
                    + "更换后游戏会自动关闭，请重新打开。");
            b.setPositiveButton("确定更换", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { reset(a); }
            });
            b.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { if (onCancel != null) onCancel.run(); }
            });
            b.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override public void onCancel(DialogInterface d) { if (onCancel != null) onCancel.run(); }
            });
            b.show();
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
