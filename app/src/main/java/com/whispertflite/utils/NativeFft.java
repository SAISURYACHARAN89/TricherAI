package com.whispertflite.utils;

import android.util.Log;

public final class NativeFft {
    private static final String TAG = "NativeFft";
    private static final boolean AVAILABLE;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("whisperfft");
            loaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "Native FFT unavailable, falling back to Java", t);
        }
        AVAILABLE = loaded;
    }

    private NativeFft() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static native float[] computeMelSpectrogram(
            float[] samples,
            int nSamples,
            int nThreads,
            float[] melFilters,
            int nMel,
            int nFft,
            int fftStep,
            int melLen,
            int expectedSamples,
            int fftSize
    );
}

