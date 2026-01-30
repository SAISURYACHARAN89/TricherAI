package com.example.offlineai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class WakeWordService extends Service {

    public static final String ACTION_WAKE = "OFFLINEAI_WAKE";
    public static final String ACTION_COMMAND = "OFFLINEAI_COMMAND";

    private static final String TAG = "WakeWordService";
    private static final String CHANNEL_ID = "wake_channel";
    private static final int NOTIF_ID = 101;
    private static final int SAMPLE_RATE = 16000;

    private enum ListenMode { WAKE, COMMAND }

    private volatile ListenMode mode = ListenMode.WAKE;
    private volatile boolean running = true;
    private volatile boolean paused = false;
    private volatile boolean ttsLocked = false;

    private Thread listenThread;
    private AudioRecord recorder;
    private Model voskModel;
    private Recognizer recognizer;

    private final List<String> wakeWords = new ArrayList<>();
    private final List<String> commandWords = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        loadKeywords();
        initVosk();
    }

    private void loadKeywords() {
        try {
            InputStream is = getAssets().open("wakeup.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();

            JSONArray array = new JSONArray(new String(buf));

            // First 3 = wake words
            for (int i = 0; i < 3; i++) {
                wakeWords.add(array.getString(i).toLowerCase());
            }

            // Remaining = command words
            for (int i = 3; i < array.length(); i++) {
                commandWords.add(array.getString(i).toLowerCase());
            }
        } catch (Exception e) {
            // 🔥 IMPORTANT: include ALL commands here
            wakeWords.add("teacher");
            wakeWords.add("tricher");
            wakeWords.add("hello");

            commandWords.add("pause");
            commandWords.add("resume");
            commandWords.add("repeat");
            commandWords.add("stop");
            commandWords.add("restart");     // ✅ ADD
            commandWords.add("new");         // ✅ ADD
            commandWords.add("question");    // ✅ ADD
        }
    }

    public static void lockForTTS(Context ctx) {
        Intent i = new Intent(ctx, WakeWordService.class);
        i.putExtra("ttsLock", true);
        ctx.startService(i);
    }

    public static void unlockAfterTTS(Context ctx, boolean commandMode) {
        Intent i = new Intent(ctx, WakeWordService.class);
        i.putExtra("ttsUnlock", true);
        i.putExtra("resumeCommand", commandMode);
        ctx.startService(i);
    }

    private void initVosk() {
        new Thread(() -> {
            try {
                File modelDir = new File(getFilesDir(), "vosk-small");
                if (!modelDir.exists()) {
                    copyAssets("vosk-model-small-en-us-0.15", modelDir.getAbsolutePath());
                }
                voskModel = new Model(modelDir.getAbsolutePath());
                updateRecognizer();
                Log.i(TAG, "Vosk initialized for Wake Detection");
            } catch (Exception e) { Log.e(TAG, "Vosk load failed", e); }
        }).start();
    }

    private void updateRecognizer() {
        try {
            StringBuilder grammar = new StringBuilder("[");
            List<String> current = (mode == ListenMode.WAKE) ? wakeWords : commandWords;
            for (int i = 0; i < current.size(); i++) {
                grammar.append("\"").append(current.get(i)).append("\"");
                if (i < current.size() - 1) grammar.append(",");
            }
            grammar.append(", \"[unk]\"]");
            recognizer = new Recognizer(voskModel, SAMPLE_RATE, grammar.toString());
        } catch (Exception ignored) {}
    }

    private void copyAssets(String assetDir, String targetDir) throws IOException {
        AssetManager assetManager = getAssets();
        String[] files = assetManager.list(assetDir);
        new File(targetDir).mkdirs();
        for (String filename : files) {
            String path = assetDir + "/" + filename;
            String[] subFiles = assetManager.list(path);
            if (subFiles.length == 0) {
                try (InputStream in = assetManager.open(path);
                     OutputStream out = new FileOutputStream(targetDir + "/" + filename)) {
                    byte[] buffer = new byte[4096]; int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
            } else { copyAssets(path, targetDir + "/" + filename); }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getBooleanExtra("pause", false)) {
                paused = true; stopRecorder();
            } else if (intent.getBooleanExtra("resumeWake", false)) {
                paused = false; mode = ListenMode.WAKE; updateRecognizer(); startRecorderIfNeeded();
            } else if (intent.getBooleanExtra("resumeCommand", false)) {
                paused = false; mode = ListenMode.COMMAND; updateRecognizer(); startRecorderIfNeeded();
            }
        }
        if (listenThread == null) startListening();
        return START_STICKY;
    }

    private void startListening() {
        listenThread = new Thread(() -> {
            try {
                startRecorderIfNeeded();
                byte[] buffer = new byte[4096];
                while (running) {
                    if (paused || recorder == null || recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING || recognizer == null) {
                        Thread.sleep(150); continue;
                    }
                    int n = recorder.read(buffer, 0, buffer.length);
                    if (n > 0 && recognizer.acceptWaveForm(buffer, n)) {
                        handleVoskResult(recognizer.getResult());
                    }
                }
            } catch (Exception ignored) {}
        });
        listenThread.start();
    }

    private void handleVoskResult(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String text = obj.optString("text", "").toLowerCase();
            if (text.isEmpty()) return;

            if (mode == ListenMode.WAKE) {
                for (String w : wakeWords) if (text.contains(w)) { sendWakeSignal(); return; }
            } else {
                // Command mode: pause, continue, repeat, restart, new question
                for (String c : commandWords) {
                    if (text.contains(c)) {
                        sendCommand(c);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void sendWakeSignal() {
        Intent i = new Intent(ACTION_WAKE); i.setPackage(getPackageName()); sendBroadcast(i);
        paused = true;
    }
    // In your WakeWordService.java, add:
    private void setupAudioForWakeWord() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Check for Bluetooth and set mode accordingly
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && btAdapter.isEnabled()) {
            // Could add Bluetooth headset check here
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } else {
            am.setMode(AudioManager.MODE_NORMAL);
        }
    }
    private void sendCommand(String cmd) {
        Intent i = new Intent(ACTION_COMMAND); i.putExtra("cmd", cmd); i.setPackage(getPackageName()); sendBroadcast(i);
    }

    private void startRecorderIfNeeded() {
        if (recorder != null) return;
        int buf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 4);
        if (recorder.getState() == AudioRecord.STATE_INITIALIZED) recorder.startRecording();
    }

    private void stopRecorder() {
        if (recorder != null) {
            try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); } catch (Exception ignored) {}
            recorder.release(); recorder = null;
        }
    }

    public static void pauseListening(Context ctx) {
        Intent i = new Intent(ctx, WakeWordService.class); i.putExtra("pause", true); ctx.startService(i);
    }
    public static void resumeWakeMode(Context ctx) {
        Intent i = new Intent(ctx, WakeWordService.class); i.putExtra("resumeWake", true); ctx.startService(i);
    }
    public static void resumeCommandMode(Context ctx) {
        Intent i = new Intent(ctx, WakeWordService.class); i.putExtra("resumeCommand", true); ctx.startService(i);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Wake Listener", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Offline AI").setContentText("Voice detection active").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
