package com.example.offlineai;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.util.Log;

import java.util.List;

public class BluetoothAudioManager {
    private static final String TAG = "BluetoothAudio";

    private final Context context;
    private final AudioManager audioManager;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothHeadset bluetoothHeadset;
    private boolean isBluetoothScoActive = false;
    private boolean isInitialized = false;

    private ToneGenerator scoToneGen;
    private ToneGenerator musicToneGen;

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                Log.i(TAG, "Bluetooth headset connection state: " + state);

                if (state == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Bluetooth headset connected, switching audio");
                    switchToBluetooth();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Bluetooth headset disconnected, switching to phone");
                    switchToPhone();
                }
            }

            // Handle SCO audio state changes
            if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(action)) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                Log.i(TAG, "SCO audio state: " + state);

                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    isBluetoothScoActive = true;
                    Log.i(TAG, "Bluetooth SCO audio connected successfully");
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    isBluetoothScoActive = false;
                    Log.i(TAG, "Bluetooth SCO audio disconnected");
                }
            }
        }
    };

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = (BluetoothHeadset) proxy;
                Log.i(TAG, "Bluetooth headset profile connected");

                // Check if already connected
                if (bluetoothHeadset.getConnectedDevices().size() > 0) {
                    Log.i(TAG, "Bluetooth headset already connected, switching audio");
                    switchToBluetooth();
                }
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null;
                Log.i(TAG, "Bluetooth headset profile disconnected");
            }
        }
    };

    public BluetoothAudioManager(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        
        try {
            scoToneGen = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80);
            musicToneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        } catch (Exception e) {
            Log.e(TAG, "Error creating ToneGenerators", e);
        }
    }

    public void playBeep(int toneType, int durationMs) {
        if (isBluetoothHeadsetConnected() && (isBluetoothScoActive || audioManager.isBluetoothScoOn())) {
            if (scoToneGen != null) {
                scoToneGen.startTone(toneType, durationMs);
                return;
            }
        }
        
        if (musicToneGen != null) {
            musicToneGen.startTone(toneType, durationMs);
        }
    }

    public void stopBeep() {
        if (scoToneGen != null) scoToneGen.stopTone();
        if (musicToneGen != null) musicToneGen.stopTone();
    }

    public void initialize() {
        if (isInitialized) return;

        Log.i(TAG, "Initializing Bluetooth audio manager");

        // Register broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(bluetoothReceiver, filter);

        // Get Bluetooth headset proxy
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HEADSET);
        }

        isInitialized = true;
    }

    public void switchToBluetooth() {
        Log.i(TAG, "Attempting to switch to Bluetooth audio");

        if (!isBluetoothHeadsetConnected()) {
            Log.w(TAG, "No Bluetooth headset connected");
            return;
        }

        try {
            // Android 12+ (API 31+) uses setCommunicationDevice
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo bluetoothHeadsetDevice = null;
                List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
                for (AudioDeviceInfo device : devices) {
                    int type = device.getType();
                    // 26 is AudioDeviceInfo.TYPE_BLUETOOTH_HEADSET
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == 26) {
                        bluetoothHeadsetDevice = device;
                        break;
                    }
                }

                if (bluetoothHeadsetDevice != null) {
                    boolean result = audioManager.setCommunicationDevice(bluetoothHeadsetDevice);
                    Log.i(TAG, "setCommunicationDevice result: " + result);
                }
            }

            // Also keep SCO-based routing for older devices and fallback
            if (!audioManager.isBluetoothScoOn()) {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
                Log.i(TAG, "Bluetooth SCO started");
            } else {
                Log.i(TAG, "Bluetooth SCO already on");
            }

            // Set audio mode for communication
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);

            Log.i(TAG, "Switched to Bluetooth audio (Mode: " + audioManager.getMode() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Error switching to Bluetooth audio", e);
        }
    }

    public boolean prepareBluetoothMicForRecording(long timeoutMs) {
        if (!isBluetoothHeadsetConnected()) {
            return false;
        }

        // If SCO is already active, we're good to go
        if (isBluetoothScoActive) {
            Log.i(TAG, "Bluetooth SCO already active, no wait needed");
            return true;
        }

        switchToBluetooth();
        
        // Increased timeout from 1.2s to 2.5s for slower headsets
        long actualTimeout = Math.max(2500, timeoutMs);
        long deadline = System.currentTimeMillis() + actualTimeout;
        
        Log.i(TAG, "Waiting for Bluetooth SCO to connect (timeout: " + actualTimeout + "ms)...");
        
        while (System.currentTimeMillis() < deadline) {
            if (isBluetoothScoActive || audioManager.isBluetoothScoOn()) {
                // Double check if SCO is actually active
                if (isBluetoothScoActive) {
                    Log.i(TAG, "Bluetooth SCO active after " + (actualTimeout - (deadline - System.currentTimeMillis())) + "ms");
                    return true;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (isBluetoothScoActive) {
            return true;
        }

        Log.w(TAG, "Bluetooth SCO not ready before timeout, fallback to phone mic");
        // switchToPhone(); // Don't force phone mode yet, the recorder might still try SCO sources
        return false;
    }

    public void switchToPhone() {
        Log.i(TAG, "Switching to phone audio");

        try {
            // Stop Bluetooth SCO
            audioManager.stopBluetoothSco();
            audioManager.setBluetoothScoOn(false);

            // Reset to normal mode
            audioManager.setMode(AudioManager.MODE_NORMAL);

            isBluetoothScoActive = false;
            Log.i(TAG, "Switched to phone audio");
        } catch (Exception e) {
            Log.e(TAG, "Error switching to phone audio", e);
        }
    }

    public boolean isBluetoothHeadsetConnected() {
        if (bluetoothHeadset != null) {
            return bluetoothHeadset.getConnectedDevices().size() > 0;
        }
        return false;
    }

    public boolean isBluetoothScoActive() {
        return isBluetoothScoActive;
    }

    public void setAudioModeForTTS() {
        if (isBluetoothHeadsetConnected()) {
            switchToBluetooth();
        } else {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
    }

    public void cleanup() {
        try {
            context.unregisterReceiver(bluetoothReceiver);

            if (bluetoothAdapter != null && bluetoothHeadset != null) {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
            }

            switchToPhone();
            isInitialized = false;
            Log.i(TAG, "Bluetooth audio manager cleaned up");
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up Bluetooth audio manager", e);
        }
    }
}