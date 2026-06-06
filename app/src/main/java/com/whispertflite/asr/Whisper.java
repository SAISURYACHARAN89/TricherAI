package com.whispertflite.asr;

import android.content.Context;
import android.util.Log;

import com.whispertflite.engine.WhisperEngine;
import com.whispertflite.engine.WhisperEngineJava;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(String result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";
    public static final String MSG_FILE_NOT_FOUND = "Input file doesn't exist..!";

    public static final Action ACTION_TRANSCRIBE = Action.TRANSCRIBE;
    public static final Action ACTION_TRANSLATE = Action.TRANSLATE;

    private enum Action {
        TRANSLATE, TRANSCRIBE
    }

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private final WhisperEngine mWhisperEngine;
    private Action mAction;
    private String mWavFilePath;
    private WhisperListener mUpdateListener;

    // Snapshot of the request captured at start() time. The worker delivers results
    // to THESE — so a transcription always reports back to the listener that asked
    // for it, even if setListener()/setFilePath() are changed mid-flight by a newer
    // session. Without this, a still-running transcription's result leaks to the
    // next listener (the "last question" off-by-one bug).
    private WhisperListener mActiveListener;
    private String mActiveWavFilePath;
    private Action mActiveAction;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;

    public Whisper(Context context) {
        this.mWhisperEngine = new WhisperEngineJava(context);
//        this.mWhisperEngine = new WhisperEngineNative(context);

        // Start thread for file transcription
        Thread threadTranscbFile = new Thread(this::transcribeFileLoop);
        threadTranscbFile.start();
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel(File modelPath, File vocabPath, boolean isMultilingual) {
        loadModel(modelPath.getAbsolutePath(), vocabPath.getAbsolutePath(), isMultilingual);
    }

    public void loadModel(String modelPath, String vocabPath, boolean isMultilingual) {
        try {
            mWhisperEngine.initialize(modelPath, vocabPath, isMultilingual);
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model...", e);
            sendUpdate("Model initialization failed");
        }
    }

    public void unloadModel() {
        mWhisperEngine.deinitialize();
    }

    public void setAction(Action action) {
        this.mAction = action;
    }

    public void setFilePath(String wavFile) {
        this.mWavFilePath = wavFile;
    }

    public void start() {
        // Force state to in progress to ensure loop picks it up
        mInProgress.set(true);
        taskLock.lock();
        try {
            // Capture the request now so the result is delivered to the listener that
            // requested THIS transcription, not whatever listener is set when it finishes.
            mActiveListener = mUpdateListener;
            mActiveWavFilePath = mWavFilePath;
            mActiveAction = mAction;
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void transcribeFileLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                transcribeFile();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void transcribeFile() {
        // Use the snapshot captured at start() — never the live mUpdateListener/mWavFilePath,
        // which a newer session may already have overwritten.
        final WhisperListener listener = mActiveListener;
        final String wavFilePath = mActiveWavFilePath;
        final Action action = mActiveAction;
        try {
            if (mWhisperEngine.isInitialized() && wavFilePath != null) {
                File waveFile = new File(wavFilePath);
                if (waveFile.exists()) {
                    long startTime = System.currentTimeMillis();
                    sendUpdate(listener, MSG_PROCESSING);

                    String result = null;
                    synchronized (mWhisperEngine) {
                        if (action == Action.TRANSCRIBE) {
                            result = mWhisperEngine.transcribeFile(wavFilePath);
                        } else {
//                            result = mWhisperEngine.getTranslation(wavFilePath);
                            Log.d(TAG, "TRANSLATE feature is not implemented");
                        }
                    }
                    sendResult(listener, result);

                    long timeTaken = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                    sendUpdate(listener, MSG_PROCESSING_DONE);
                } else {
                    sendUpdate(listener, MSG_FILE_NOT_FOUND);
                }
            } else {
                sendUpdate(listener, "Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate(listener, "Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        sendUpdate(mUpdateListener, message);
    }

    private void sendUpdate(WhisperListener listener, String message) {
        if (listener != null) {
            listener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperListener listener, String message) {
        if (listener != null) {
            listener.onResultReceived(message);
        }
    }

    /////////////////////// Unused live mic feed methods removed /////////////////////////////////
}
