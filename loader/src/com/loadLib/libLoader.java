package com.loadLib;

import android.app.Activity;
import android.os.Handler;

/**
 * Injected into the patched game's UnityPlayerActivity. Loads the in-house
 * native hook.
 */
public class libLoader extends Activity {
    public static void loadLib() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                System.loadLibrary("fgohook");
            }
        }, 1);
    }
}
