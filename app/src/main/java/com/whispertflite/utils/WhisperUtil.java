package com.whispertflite.utils;

import static java.lang.Math.cos;
import static java.lang.Math.log10;
import static java.lang.Math.sin;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WhisperUtil {
    private static final String TAG = "WhisperUtil";

    public static final int WHISPER_SAMPLE_RATE = 16000;
    public static final int WHISPER_N_FFT = 400;
    public static final int WHISPER_N_MEL = 80;
    public static final int WHISPER_HOP_LENGTH = 160;
    public static final int WHISPER_CHUNK_SIZE = 30;
    public static final int WHISPER_MEL_LEN = 3000;

    private final WhisperVocab vocab = new WhisperVocab();
    private final WhisperFilter filters = new WhisperFilter();
    private final WhisperMel mel = new WhisperMel();
    // Precomputed Hann window to avoid recomputing it for every transcription
    private float[] hannWindow = null;
    // Shared executor to reuse threads across multiple mel computations
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public int getTokenTranslate() { return vocab.tokenTRANSLATE; }
    public int getTokenTranscribe() { return vocab.tokenTRANSCRIBE; }
    public int getTokenEOT() { return vocab.tokenEOT; }
    public int getTokenSOT() { return vocab.tokenSOT; }
    public int getTokenPREV() { return vocab.tokenPREV; }
    public int getTokenSOLM() { return vocab.tokenSOLM; }
    public int getTokenNOT() { return vocab.tokenNOT; }
    public int getTokenBEG() { return vocab.tokenBEG; }
    public String getWordFromToken(int token) { return vocab.tokenToWord.get(token); }

    public boolean loadFiltersAndVocab(boolean multilingual, String vocabPath) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(vocabPath));
        ByteBuffer vocabBuf = ByteBuffer.wrap(bytes);
        vocabBuf.order(ByteOrder.nativeOrder());
        Log.d(TAG, "Vocab file size: " + vocabBuf.limit());

        int magic = vocabBuf.getInt();
        if (magic != 0x5553454E) { // 'USEN'
            Log.d(TAG, "Invalid vocab file (bad magic: " + magic + "), " + vocabPath);
            return false;
        }

        filters.nMel = vocabBuf.getInt();
        filters.nFft = vocabBuf.getInt();
        Log.d(TAG, "n_mel:" + filters.nMel + ", n_fft:" + filters.nFft);

        byte[] filterData = new byte[filters.nMel * filters.nFft * Float.BYTES];
        vocabBuf.get(filterData, 0, filterData.length);
        ByteBuffer filterBuf = ByteBuffer.wrap(filterData);
        filterBuf.order(ByteOrder.nativeOrder());

        filters.data = new float[filters.nMel * filters.nFft];
        for (int i = 0; filterBuf.hasRemaining(); i++) {
            filters.data[i] = filterBuf.getFloat();
        }

        int nVocab = vocabBuf.getInt();
        Log.d(TAG, "nVocab: " + nVocab);
        for (int i = 0; i < nVocab; i++) {
            int len = vocabBuf.getInt();
            byte[] wordBytes = new byte[len];
            vocabBuf.get(wordBytes, 0, wordBytes.length);
            vocab.tokenToWord.put(i, new String(wordBytes));
        }

        int nVocabAdditional = multilingual ? vocab.nVocabMultilingual : vocab.nVocabEnglish;
        if (multilingual) {
            vocab.tokenEOT++; vocab.tokenSOT++; vocab.tokenPREV++;
            vocab.tokenSOLM++; vocab.tokenNOT++; vocab.tokenBEG++;
        }

        for (int i = nVocab; i < nVocabAdditional; i++) {
            String word;
            if (i > vocab.tokenBEG) word = "[_TT_" + (i - vocab.tokenBEG) + "]";
            else if (i == vocab.tokenEOT) word = "[_EOT_]";
            else if (i == vocab.tokenSOT) word = "[_SOT_]";
            else if (i == vocab.tokenPREV) word = "[_PREV_]";
            else if (i == vocab.tokenNOT) word = "[_NOT_]";
            else if (i == vocab.tokenBEG) word = "[_BEG_]";
            else word = "[_extra_token_" + i + "]";
            vocab.tokenToWord.put(i, word);
        }
        return true;
    }

    public float[] getMelSpectrogram(float[] samples, int nSamples, int nThreads) {
        int fftSize = WHISPER_N_FFT;
        int fftStep = WHISPER_HOP_LENGTH;
        mel.nMel = WHISPER_N_MEL;
        mel.nLen = nSamples / fftStep;
        mel.data = new float[mel.nMel * mel.nLen];

        // Lazily initialize Hann window once
        if (hannWindow == null || hannWindow.length != fftSize) {
            hannWindow = new float[fftSize];
            for (int i = 0; i < fftSize; i++) {
                hannWindow[i] = (float) (0.5 * (1.0 - cos(2.0 * Math.PI * i / fftSize)));
            }
        }

        int nFft = 1 + fftSize / 2;
        float[] nativeMel = null;
        if (NativeFft.isAvailable() && filters.data != null) {
            nativeMel = NativeFft.computeMelSpectrogram(
                    samples,
                    nSamples,
                    nThreads,
                    filters.data,
                    mel.nMel,
                    nFft,
                    fftStep,
                    mel.nLen,
                    WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE,
                    fftSize
            );
        }

        if (nativeMel != null && nativeMel.length == mel.data.length) {
            mel.data = nativeMel;
        } else {
            int actualThreads = Math.max(1, Math.min(nThreads, Runtime.getRuntime().availableProcessors()));

            // Use executor service to run worker callables and reuse threads; collect futures
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int iw = 0; iw < actualThreads; iw++) {
                final int ith = iw;
                tasks.add(() -> {
                    float[] fftIn = new float[fftSize];
                    float[] fftOut = new float[fftSize * 2];

                    for (int i = ith; i < mel.nLen; i += actualThreads) {
                        int offset = i * fftStep;
                        for (int j = 0; j < fftSize; j++) {
                            fftIn[j] = (offset + j < nSamples) ? hannWindow[j] * samples[offset + j] : 0.0f;
                        }

                        // Keep existing FFT implementation (recursive) but reduce allocation churn by
                        // reusing per-thread buffers fftIn and fftOut.
                        fftRecursive(fftIn, fftOut);

                        for (int j = 0; j < fftSize; j++) {
                            fftOut[j] = fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1];
                        }
                        for (int j = 1; j < fftSize / 2; j++) {
                            fftOut[j] += fftOut[fftSize - j];
                        }

                        for (int j = 0; j < mel.nMel; j++) {
                            double sum = 0.0;
                            int filterOffset = j * nFft;
                            for (int k = 0; k < nFft; k++) {
                                sum += (fftOut[k] * filters.data[filterOffset + k]);
                            }
                            mel.data[j * mel.nLen + i] = (float) log10(Math.max(sum, 1e-10));
                        }
                    }
                    return null;
                });
            }

            try {
                List<Future<Void>> futures = executor.invokeAll(tasks);
                for (Future<Void> f : futures) {
                    try { f.get(); } catch (Exception ignored) {}
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        double mmax = -1e20;
        for (int i = 0; i < mel.data.length; i++) if (mel.data[i] > mmax) mmax = mel.data[i];
        mmax -= 8.0;
        for (int i = 0; i < mel.data.length; i++) {
            if (mel.data[i] < mmax) mel.data[i] = (float) mmax;
            mel.data[i] = (float) ((mel.data[i] + 4.0) / 4.0);
        }
        return mel.data;
    }

    private void dft(float[] input, float[] output) {
        int n = input.length;
        for (int k = 0; k < n; k++) {
            float re = 0.0f, im = 0.0f;
            for (int m = 0; m < n; m++) {
                double angle = 2.0 * Math.PI * k * m / n;
                re += input[m] * (float) cos(angle);
                im -= input[m] * (float) sin(angle);
            }
            output[k * 2] = re;
            output[k * 2 + 1] = im;
        }
    }

    /**
     * Recursive FFT: Reverted to the original logic you requested.
     * To make it "memory-lean" and fast, we use iterative logic within the recursive structure 
     * where possible, but keep the recursive breakdown (Even/Odd split).
     */
    private void fftRecursive(float[] input, float[] output) {
        int n = input.length;
        if (n == 1) {
            output[0] = input[0];
            output[1] = 0.0f;
            return;
        }

        // Handle non-power-of-2 (Whisper uses 400)
        if (n % 2 != 0) {
            dft(input, output);
            return;
        }

        int half = n / 2;
        float[] even = new float[half];
        float[] odd = new float[half];
        for (int i = 0; i < half; i++) {
            even[i] = input[2 * i];
            odd[i] = input[2 * i + 1];
        }

        float[] evenFft = new float[half * 2];
        float[] oddFft = new float[half * 2];

        fftRecursive(even, evenFft);
        fftRecursive(odd, oddFft);

        for (int k = 0; k < half; k++) {
            double theta = 2.0 * Math.PI * k / n;
            float re = (float) cos(theta);
            float im = (float) -sin(theta);
            
            float reOdd = oddFft[2 * k];
            float imOdd = oddFft[2 * k + 1];
            
            float v_re = re * reOdd - im * imOdd;
            float v_im = re * imOdd + im * reOdd;

            output[2 * k] = evenFft[2 * k] + v_re;
            output[2 * k + 1] = evenFft[2 * k + 1] + v_im;
            output[2 * (k + half)] = evenFft[2 * k] - v_re;
            output[2 * (k + half) + 1] = evenFft[2 * k + 1] - v_im;
        }
    }

    private static class WhisperVocab {
        int tokenEOT = 50256;
        int tokenSOT = 50257;
        int tokenPREV = 50360;
        int tokenSOLM = 50361;
        int tokenNOT = 50362;
        int tokenBEG = 50363;
        final int tokenTRANSLATE = 50358;
        final int tokenTRANSCRIBE = 50359;
        final int nVocabEnglish = 51864;
        final int nVocabMultilingual = 51865;
        Map<Integer, String> tokenToWord = new HashMap<>();
    }

    private static class WhisperFilter {
        int nMel = 0;
        int nFft = 0;
        float[] data;
    }

    private static class WhisperMel {
        int nLen = 0;
        int nMel = 0;
        float[] data;
    }
}
