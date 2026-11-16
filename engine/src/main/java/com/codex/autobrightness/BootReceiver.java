package com.codex.autobrightness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Start only the opted-in foreground controller after a real boot or application update. */
public final class BootReceiver extends BroadcastReceiver {
    /** Respect both saved switches and reset only the session-scoped manual pause. */
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Settings settings = new Settings(context);
        if (settings.pauseUntil() == Long.MAX_VALUE) settings.clearPause();
        if (settings.initialized() && settings.enabled() && settings.autostart()) BrightnessService.start(context, null);
    }
}
