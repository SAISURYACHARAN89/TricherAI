package com.example.offlineai;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class PermissionManager {
    private static final String TAG = "PermissionManager";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private final Activity activity;

    public PermissionManager(Activity activity) {
        this.activity = activity;
    }

    public boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Always need microphone permission
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ needs these
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else {
            // Older Android versions
            if (!hasPermission(Manifest.permission.BLUETOOTH)) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH);
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADMIN)) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            Log.i(TAG, "Requesting permissions: " + permissionsNeeded);
            ActivityCompat.requestPermissions(
                    activity,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE
            );
            return false;
        }

        Log.i(TAG, "All permissions granted");
        return true;
    }

    public boolean handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                }
            }

            // Check if essential permissions are granted
            boolean hasMic = hasPermission(Manifest.permission.RECORD_AUDIO);
            boolean hasBluetooth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT) :
                    hasPermission(Manifest.permission.BLUETOOTH);

            return hasMic; // Return true if at least mic permission is granted
        }
        return false;
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(activity, permission) ==
                PackageManager.PERMISSION_GRANTED;
    }
}