package com.along.capprobe;

import android.util.Log;

public class Slog {
    private static final String TAG = "CapProbe";

    public static void d(String s) {
        Log.d(TAG, s);
    }

    public static void i(String s) {
        Log.i(TAG, s);
    }

    public static void w(String s) {
        Log.w(TAG, s);
    }

    public static void e(String s) {
        Log.e(TAG, s);
    }

    public static void e(String s, Throwable t) {
        Log.e(TAG, s, t);
    }
}
