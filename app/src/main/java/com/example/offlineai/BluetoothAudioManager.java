package com.example.offlineai;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.util.Log;

public class BluetoothAudioManager {
    private static final String TAG = "BluetoothAudio";

    private final Context context;
    private final AudioManager audioManager;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothHeadset bluetoothHeadset;
    private boolean isBluetoothScoActive = false;
    private boolean isInitialized = false;

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
            // Stop any existing SCO connection
            audioManager.stopBluetoothSco();
            audioManager.setBluetoothScoOn(false);

            // Start new SCO connection
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);

            // Set audio mode for communication
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);

            Log.i(TAG, "Switched to Bluetooth audio");
        } catch (Exception e) {
            Log.e(TAG, "Error switching to Bluetooth audio", e);
        }
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
        if (isBluetoothHeadsetConnected() && isBluetoothScoActive) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
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