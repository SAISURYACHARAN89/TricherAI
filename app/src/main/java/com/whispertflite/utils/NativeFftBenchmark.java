package com.whispertflite.utils;

import android.util.Log;

public final class NativeFftBenchmark {
    private static final String TAG = "NativeFftBenchmark";

    private NativeFftBenchmark() {
    }

    public static void runOnce(float[] samples, int nSamples, int nThreads, float[] filters,
                               int nMel, int nFft, int fftStep, int melLen, int fftSize) {
        if (!NativeFft.isAvailable()) {
            Log.w(TAG, "Native FFT not available; benchmark skipped");
            return;
        }
        long start = System.nanoTime();
        float[] out = NativeFft.computeMelSpectrogram(
                samples,
                nSamples,
                nThreads,
                filters,
                nMel,
                nFft,
                fftStep,
                melLen,
                WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE,
                fftSize
        );
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        Log.d(TAG, "Native mel computed: " + (out == null ? 0 : out.length) + " floats in " + elapsedMs + "ms");
    }
}

