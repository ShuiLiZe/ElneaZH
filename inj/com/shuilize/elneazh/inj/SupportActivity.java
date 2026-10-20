package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;

/**
 * Transparent activity behind elneazh://support. The title screen's 「交流群与支持」 button (Support popup)
 * is patched in libil2cpp to Application.OpenURL("elneazh://support"); Unity turns that into an ACTION_VIEW
 * intent which resolves here. Shows {@link Support} and finishes when it is closed. Never throws.
 */
public final class SupportActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Dialog d = Support.build(this);
            d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface di) { done(); }
            });
            d.show();
        } catch (Throwable t) {
            Log.e(Support.TAG, "support dialog", t);
            done();
        }
    }

    private void done() {
        try { if (!isFinishing()) finish(); overridePendingTransition(0, 0); } catch (Throwable ignored) {}
    }
}
