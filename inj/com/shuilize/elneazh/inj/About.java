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
 * Shown a few seconds after the first activity resumes, once per KEY (bump KEY to show again).
 * Everything is wrapped so a failure can never affect the game.
 */
final class About {
    static final String QQ = Support.QQ;
    private static final String PREFS = "elneazh_about";
    private static final String KEY = "shown_v2";
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
        if (markShown) a.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, true).apply();
        Support.build(a).show();
    }
}
