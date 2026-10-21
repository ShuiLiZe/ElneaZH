package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * One-time 「交流与支持」 dialog (see {@link Support}) inside the localized game: free, QQ group, optional WeChat donation QR.
 *
 * <p>顺带承担一件事：**「进到主界面」的判据**。Unity 游戏进程里，Activity 真正的
 * {@code onActivityResumed} 之后 6 秒（旧逻辑弹一次首启说明的时机）一定已经过了 splash，
 * 主循环活着、UI 线程能 post —— 这正是 {@link NativeGuard} 需要的「这一轮启动活下来了」的证据。
 * 所以这里清掉崩溃保护的标记文件（多清一次没关系，{@code markSurvived} 是幂等的）。
 *
 * <p>注意：即使首启说明已经显示过（{@code KEY} 为 true）也要注册回调 —— 否则只清一次标记，
 * 之后每轮都算「没活下来」，第二次启动就会把 native 停掉。
 */
final class About {
    private static final String PREFS = "elneazh_ui";
    private static final String KEY = "shown_v2";
    private static boolean pending = true;

    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!(app instanceof Application)) {
            // 拿不到 Application 也要保证「活到主界面」这件事有人记账：交给看门狗（60s）。
            return;
        }
        final boolean needDialog = !app.getSharedPreferences(PREFS, 0).getBoolean(KEY, false);
        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(final Activity a) {
                // ① 崩溃保护：Activity  resumed = 进程活过了启动阶段。
                try { NativeGuard.markSurvived("activity resumed: " + a.getClass().getName()); }
                catch (Throwable t) { Log.w("ElneaGate", "mark survived failed", t); }
                // ② 首启说明只弹一次（旧逻辑不变）
                if (!needDialog || !pending) return;
                pending = false;
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
        if (markShown) a.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, true).apply();
        Support.build(a).show();
    }
}
