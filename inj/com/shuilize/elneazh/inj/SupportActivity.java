package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
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
        if (getIntent() != null && getIntent().getBooleanExtra("restore", false)) {
            try { Backup.pick(this); } catch (Throwable t) { done(); }
            return;
        }
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

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        try {
            if (req == Backup.REQ_RESTORE && res == RESULT_OK && data != null && data.getData() != null) {
                Backup.confirmRestore(this, data.getData());
            } else if (getIntent() != null && getIntent().getBooleanExtra("restore", false)) {
                done();
            }
        } catch (Throwable t) { Log.e(Support.TAG, "restore result", t); }
    }

    private void done() {
        try { if (!isFinishing()) finish(); overridePendingTransition(0, 0); } catch (Throwable ignored) {}
    }
}
