package com.android.fmradio;

/** Callback target that some firmware JNI layers look up by name. Must exist; may stay idle. */
public final class FmService {
    private FmService() {}

    public interface RdsListener { void onRdsEvent(int eventType, int v1, int v2, int v3); }

    private static volatile RdsListener listener;

    public static void setRdsListener(RdsListener l) { listener = l; }

    public static int Rdscallback(int eventType, int v1, int v2, int v3) {
        RdsListener l = listener;
        if (l != null) l.onRdsEvent(eventType, v1, v2, v3);
        return 0;
    }

    public static int Rdscallback(int eventType, String value) { return 0; }

    public static int Rdscallback(int eventType, byte[] value) { return 0; }

    public static int callback(int v1, int v2) { return 0; }
}
