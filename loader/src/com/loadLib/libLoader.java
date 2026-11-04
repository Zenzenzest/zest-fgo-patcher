package com.loadLib;

import android.app.Activity;
import android.os.Handler;

/**
 * Injected into the patched game's UnityPlayerActivity.
 *
 * libavalon is optional: release builds still ship the donor library, while
 * test builds omit it and load only the in-house hook. The try/catch keeps the
 * loader working in both configurations.
 */
public class libLoader extends Activity {
    public static void loadLib() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    System.loadLibrary("avalon");
                } catch (Throwable ignored) {
                    // libavalon is optional: our in-house hook replaces it.
                }
                System.loadLibrary("fgohook");
            }
        }, 1);
    }
}
