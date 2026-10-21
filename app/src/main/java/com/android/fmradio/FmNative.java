package com.android.fmradio;

/**
 * JNI contract for the firmware's libfmjni.so (FYT / Syu SoC-FM units such as UIS7870, and
 * MediaTek/Unisoc-derived FM stacks). Package, class and method names are fixed by the native
 * library and must not change.
 *
 * The library is NOT loaded in a static initializer: {@link #load()} is called explicitly so a
 * failure is reported instead of crashing class loading.
 */
public final class FmNative {
    private static volatile String loadedFrom = null;
    private static volatile String lastError = null;

    private static final String[] PATHS = {
            "/system/lib/libfmjni.so", "/system/lib64/libfmjni.so",
            "/vendor/lib/libfmjni.so", "/vendor/lib64/libfmjni.so",
    };

    private FmNative() {}

    public static final FmNative INSTANCE = new FmNative();

    /** @return null on success, otherwise a description of every attempt. */
    public static synchronized String load() {
        if (loadedFrom != null) return null;
        StringBuilder sb = new StringBuilder();
        try {
            System.loadLibrary("fmjni");
            loadedFrom = "System.loadLibrary(fmjni)";
            return null;
        } catch (Throwable t) {
            sb.append("loadLibrary: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        }
        for (String p : PATHS) {
            if (!new java.io.File(p).exists()) continue;
            try {
                System.load(p);
                loadedFrom = p;
                return null;
            } catch (Throwable t) {
                sb.append(p).append(": ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            }
        }
        lastError = sb.toString().trim();
        return lastError.isEmpty() ? "libfmjni.so not found" : lastError;
    }

    public static String loadedFrom() { return loadedFrom; }
    public static boolean isLoaded() { return loadedFrom != null; }

    public native boolean openDev();
    public native boolean closeDev();
    public native boolean powerUp(float frequency);
    public native boolean powerDown(int type);
    public native boolean tune(float frequency);
    public native float[] seek(float frequency, boolean isUp);
    public native int setMute(boolean mute);
    public static native short[] autoScan(int band);
    public native boolean stopScan();
    public native byte[] getPs();
    public native byte[] getLrText();
    public native int setRds(boolean enable);
    public native short readRds();
    public native int isRdsSupport();
    public native int readRssi();
    public native boolean stereoMono();
    public native int switchAntenna(int antenna);
}
