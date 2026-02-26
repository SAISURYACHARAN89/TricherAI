package com.example.offlineai;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Secure License Enforcement Manager
 * Handles license validation, device binding, and subscription enforcement
 */
public class LicenseManager {
    private static final String TAG = "LicenseManager";
    private static final String CODE_DEVICE_LIMIT = "device_limit";
    private static final String CODE_NO_SUBSCRIPTION = "no_subscription";

    // SharedPreferences keys
    private static final String PREFS_NAME = "license_prefs_encrypted";
    private static final String KEY_LICENSE_VALID = "license_valid";
    private static final String KEY_LICENSE_EXPIRY = "license_expiry_time";
    private static final String KEY_LAST_SERVER_TIME = "last_server_time";
    private static final String KEY_LAST_VALIDATION_TIME = "last_validation_time";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_USER_EMAIL = "user_email";

    // License validation endpoint - Update this with your actual backend URL
    private static final String LICENSE_VALIDATION_URL = "https://api.tricher.app/api/validate-license";

    // 3 days in milliseconds
    public static final long MAX_OFFLINE_DURATION = 3L * 24 * 60 * 60 * 1000;

    // Check license once a day when app is active (24 hours)
    private static final long LICENSE_CHECK_INTERVAL = 24 * 60 * 60 * 1000;

    // License state variables
    private boolean licenseValid = false;
    private long licenseExpiryTime = 0;
    private long lastServerTime = 0;
    private long lastValidationTime = 0;
    private String deviceId = null;
    private String userEmail = null;

    private final Context context;
    private SharedPreferences prefs;
    private LicenseCallback callback;

    // Handler for periodic license checks
    private android.os.Handler licenseCheckHandler;
    private Runnable licenseCheckRunnable;
    private boolean isMonitoringActive = false;
    private boolean networkRequiredNotified = false;

    public interface LicenseCallback {
        void onLicenseValid();
        void onLicenseInvalid(String reason);
        void onLicenseCheckInProgress();
        void onNetworkRequired();
        void onLicenseExpired(); // NEW: Called when license expires while app is running
    }

    public LicenseManager(Context context) {
        this.context = context.getApplicationContext();
        initializePreferences();
        loadStoredLicenseData();
        ensureDeviceId();
        setupPeriodicLicenseCheck();
    }

    /**
     * Setup periodic license check to auto-detect expiry while app is running
     */
    private void setupPeriodicLicenseCheck() {
        licenseCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        licenseCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isMonitoringActive) return;

                Log.d(TAG, "Periodic license check running...");

                // Check if license has expired
                if (isLicenseExpiredNow()) {
                    Log.w(TAG, "LICENSE EXPIRED - auto-blocking access");
                    licenseValid = false;
                    saveLicenseData();
                    deleteModelFile();

                    if (callback != null) {
                        callback.onLicenseExpired();
                    }
                    return; // Stop checking once expired
                }

                // Check if we need online revalidation
                if (needsOnlineRevalidation()) {
                    Log.w(TAG, "Online revalidation needed");
                    licenseValid = false;
                    saveLicenseData();

                    if (callback != null && !networkRequiredNotified) {
                        networkRequiredNotified = true;
                        callback.onNetworkRequired();
                    }
                }

                // Schedule next check - smartly based on expiry time
                long timeUntilExpiry = licenseExpiryTime - System.currentTimeMillis();
                long nextCheckDelay;

                if (licenseExpiryTime == 0) {
                    // Never validated yet; avoid tight loop
                    nextCheckDelay = LICENSE_CHECK_INTERVAL;
                } else if (timeUntilExpiry <= 0) {
                    // Should have been caught above, but just in case
                    nextCheckDelay = 1000;
                } else if (timeUntilExpiry < LICENSE_CHECK_INTERVAL) {
                    // Expires within 24 hours - check at expiry time
                    nextCheckDelay = timeUntilExpiry + 1000;
                } else {
                    // Normal daily check
                    nextCheckDelay = LICENSE_CHECK_INTERVAL;
                }

                Log.d(TAG, "Next license check in " + (nextCheckDelay / 1000 / 60) + " minutes");
                licenseCheckHandler.postDelayed(this, nextCheckDelay);
            }
        };
    }

    /**
     * Start monitoring license expiry (call when app becomes active)
     */
    public void startExpiryMonitoring() {
        if (isMonitoringActive) return;

        isMonitoringActive = true;
        Log.i(TAG, "Starting license expiry monitoring");

        // Calculate time until expiry for more precise first check
        long timeUntilExpiry = licenseExpiryTime - System.currentTimeMillis();
        long firstCheckDelay;

        if (timeUntilExpiry <= 0) {
            // Already expired, check immediately
            firstCheckDelay = 0;
        } else if (timeUntilExpiry < LICENSE_CHECK_INTERVAL) {
            // Expires soon, check at expiry time + small buffer
            firstCheckDelay = timeUntilExpiry + 1000;
        } else {
            // Normal interval
            firstCheckDelay = LICENSE_CHECK_INTERVAL;
        }

        licenseCheckHandler.postDelayed(licenseCheckRunnable, firstCheckDelay);
    }

    /**
     * Stop monitoring license expiry (call when app goes to background)
     */
    public void stopExpiryMonitoring() {
        isMonitoringActive = false;
        if (licenseCheckHandler != null) {
            licenseCheckHandler.removeCallbacks(licenseCheckRunnable);
        }
        Log.i(TAG, "Stopped license expiry monitoring");
    }

    /**
     * Check if license is expired RIGHT NOW
     */
    public boolean isLicenseExpiredNow() {
        if (licenseExpiryTime == 0) return false; // Never validated
        return System.currentTimeMillis() > licenseExpiryTime;
    }

    /**
     * Get time remaining until license expires (in milliseconds)
     * Returns 0 if already expired, -1 if never validated
     */
    public long getTimeUntilExpiry() {
        if (licenseExpiryTime == 0) return -1;
        long remaining = licenseExpiryTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    private void initializePreferences() {
        // Use regular SharedPreferences with MODE_PRIVATE for security
        // Note: For production, consider using EncryptedSharedPreferences from androidx.security:security-crypto
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Log.i(TAG, "Using SharedPreferences for license storage");
    }

    private void loadStoredLicenseData() {
        licenseValid = prefs.getBoolean(KEY_LICENSE_VALID, false);
        licenseExpiryTime = prefs.getLong(KEY_LICENSE_EXPIRY, 0);
        lastServerTime = prefs.getLong(KEY_LAST_SERVER_TIME, 0);
        lastValidationTime = prefs.getLong(KEY_LAST_VALIDATION_TIME, 0);
        deviceId = prefs.getString(KEY_DEVICE_ID, null);
        userEmail = prefs.getString(KEY_USER_EMAIL, null);

        Log.i(TAG, "Loaded license data - Valid: " + licenseValid +
                ", Expiry: " + licenseExpiryTime +
                ", LastValidation: " + lastValidationTime);
    }

    private void ensureDeviceId() {
        if (deviceId == null || deviceId.isEmpty()) {
            // Try ANDROID_ID first
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );

            if (androidId != null && !androidId.isEmpty() && !"9774d56d682e549c".equals(androidId)) {
                // Valid ANDROID_ID
                deviceId = "AID_" + androidId;
            } else {
                // Generate UUID as fallback
                deviceId = "UUID_" + UUID.randomUUID().toString();
            }

            // Store permanently
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
            Log.i(TAG, "Generated device ID: " + deviceId);
        }
    }

    public void setCallback(LicenseCallback callback) {
        this.callback = callback;
    }

    public void setUserEmail(String email) {
        this.userEmail = email;
        prefs.edit().putString(KEY_USER_EMAIL, email).apply();
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public boolean isLicenseValid() {
        return licenseValid;
    }

    public long getLicenseExpiryTime() {
        return licenseExpiryTime;
    }

    /**
     * Main license enforcement check - call this before any LLM operations
     * Returns true if license is valid, false otherwise
     */
    public boolean enforceLicenseCheck() {
        long currentTime = System.currentTimeMillis();

        Log.i(TAG, "Enforcing license check...");
        Log.i(TAG, "Current time: " + currentTime);
        Log.i(TAG, "License expiry: " + licenseExpiryTime);
        Log.i(TAG, "Last server time: " + lastServerTime);
        Log.i(TAG, "Last validation: " + lastValidationTime);

        // Check 1: Has license ever been validated?
        if (lastValidationTime == 0) {
            Log.w(TAG, "License never validated - requiring online validation");
            invalidateLicense("License not validated. Please connect to internet.");
            if (callback != null && !networkRequiredNotified) {
                networkRequiredNotified = true;
                callback.onNetworkRequired();
            }
            return false;
        }

        // Check 2: Time tampering detection - device time before last server time
        if (currentTime < lastServerTime - 60000) { // Allow 1 minute tolerance
            Log.e(TAG, "TIME TAMPERING DETECTED! Device time is before server time");
            invalidateLicense("Time tampering detected. Please set correct time.");
            deleteModelFile();
            return false;
        }

        // Check 3: License expired
        if (currentTime > licenseExpiryTime) {
            Log.w(TAG, "License expired");
            invalidateLicense("Your subscription has expired. Please renew.");
            deleteModelFile();
            return false;
        }

        // Check 4: Offline duration exceeded - requires online revalidation
        long timeSinceLastValidation = currentTime - lastValidationTime;
        if (timeSinceLastValidation > MAX_OFFLINE_DURATION) {
            Log.w(TAG, "Offline duration exceeded: " + timeSinceLastValidation + "ms");
            licenseValid = false;
            saveLicenseData();
            if (callback != null) callback.onNetworkRequired();
            return false;
        }

        // All checks passed
        if (!licenseValid) {
            Log.w(TAG, "License marked invalid in storage");
            if (callback != null) callback.onLicenseInvalid("License invalid. Please revalidate.");
            return false;
        }

        Log.i(TAG, "License check PASSED");
        return true;
    }

    /**
     * Validate license from server - must be called on background thread
     */
    public void validateLicenseFromServer(String email) {
        if (email == null || email.isEmpty()) {
            Log.e(TAG, "Cannot validate - no email provided");
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    callback.onLicenseInvalid("Please provide your email address.")
                );
            }
            return;
        }

        setUserEmail(email);

        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                callback.onLicenseCheckInProgress()
            );
        }

        new Thread(() -> {
            try {
                Log.i(TAG, "Validating license for email: " + email);
                Log.i(TAG, "License validation URL: " + LICENSE_VALIDATION_URL);
                Log.i(TAG, "Device ID: " + deviceId);

                URL url = new URL(LICENSE_VALIDATION_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                // Build request body
                JSONObject requestBody = new JSONObject();
                requestBody.put("email", email);
                requestBody.put("deviceId", deviceId);

                Log.i(TAG, "Request body: " + requestBody.toString());

                // Send request
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.toString().getBytes("UTF-8"));
                }

                int responseCode = conn.getResponseCode();
                Log.i(TAG, "Response code: " + responseCode);
                Log.i(TAG, "License validation response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Read response
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                    }

                    // Parse response
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    boolean access = jsonResponse.optBoolean("access", false);

                    // Parse expiryDate - can be ISO string or timestamp
                    long expiryDate = 0;
                    Object expiryObj = jsonResponse.opt("expiryDate");
                    if (expiryObj instanceof String) {
                        expiryDate = parseIsoDateToMillis((String) expiryObj);
                    } else if (expiryObj instanceof Number) {
                        expiryDate = ((Number) expiryObj).longValue();
                    }

                    // Parse serverTime - can be ISO string or timestamp
                    long serverTime = System.currentTimeMillis();
                    Object serverTimeObj = jsonResponse.opt("serverTime");
                    if (serverTimeObj instanceof String) {
                        serverTime = parseIsoDateToMillis((String) serverTimeObj);
                    } else if (serverTimeObj instanceof Number) {
                        serverTime = ((Number) serverTimeObj).longValue();
                    }

                    // Fallback if serverTime is still 0
                    if (serverTime == 0) {
                        serverTime = System.currentTimeMillis();
                    }

                    Log.i(TAG, "Server response - access: " + access +
                            ", expiryDate: " + expiryDate +
                            ", serverTime: " + serverTime);

                    if (access) {
                        // License valid
                        licenseValid = true;
                        licenseExpiryTime = expiryDate;
                        lastServerTime = serverTime;
                        lastValidationTime = System.currentTimeMillis();
                        networkRequiredNotified = false;
                        saveLicenseData();

                        Log.i(TAG, "License validated successfully!");

                        if (callback != null) {
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                callback.onLicenseValid()
                            );
                        }
                    } else {
                        // Access denied - check for specific error codes
                        String code = jsonResponse.optString("code", "");
                        String message = jsonResponse.optString("message", "Access denied. Please renew.");

                        // Handle device limit error specifically
                        if ("device_limit".equals(code)) {
                            message = "This subscription is already in use on another device. Only one device per account is allowed.";
                            Log.w(TAG, "Device limit reached - subscription bound to different device");
                        }

                        invalidateLicense(message);
                        deleteModelFile();

                        if (callback != null) {
                            final String finalMessage = message;
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                callback.onLicenseInvalid(finalMessage)
                            );
                        }
                    }
                } else {
                    // HTTP error - try to read error body for more info
                    Log.e(TAG, "License validation HTTP error: " + responseCode);

                    // Read error response body
                    StringBuilder errorResponse = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            errorResponse.append(line);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not read error response body");
                    }
                    Log.e(TAG, "Error response body: " + errorResponse);

                    // Try to parse error message from response
                    String errorMessage = "Server error. Please try again later.";
                    try {
                        JSONObject errorJson = new JSONObject(errorResponse.toString());
                        boolean access = errorJson.optBoolean("access", false);
                        String code = errorJson.optString("code", "");
                        String message = errorJson.optString("message", "");

                        if (!access) {
                            if (CODE_DEVICE_LIMIT.equals(code)) {
                                errorMessage = "This subscription is already in use on another device. Only one device per account is allowed.";
                            } else if (CODE_NO_SUBSCRIPTION.equals(code)) {
                                errorMessage = "No active subscription found for this email. Please renew.";
                            } else if (!message.isEmpty()) {
                                errorMessage = message;
                            }
                        }
                    } catch (Exception e) {
                        // Use default error message
                    }

                    invalidateLicense(errorMessage);
                    deleteModelFile();

                    if (callback != null) {
                        final String finalErrorMsg = errorMessage;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            callback.onLicenseInvalid(finalErrorMsg)
                        );
                    }
                }

                conn.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "License validation error", e);

                if (callback != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        callback.onLicenseInvalid("Network error. Please check your connection.")
                    );
                }
            }
        }).start();
    }

    /**
     * Synchronous license validation - blocks until complete
     * Returns true if valid, false otherwise
     */
    public boolean validateLicenseSync(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }

        setUserEmail(email);
        final AtomicBoolean result = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                URL url = new URL(LICENSE_VALIDATION_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                JSONObject requestBody = new JSONObject();
                requestBody.put("email", email);
                requestBody.put("deviceId", deviceId);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.toString().getBytes("UTF-8"));
                }

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                    }

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    boolean access = jsonResponse.optBoolean("access", false);

                    if (access) {
                        licenseValid = true;

                        // Parse expiryDate - can be ISO string or timestamp
                        Object expiryObj = jsonResponse.opt("expiryDate");
                        if (expiryObj instanceof String) {
                            licenseExpiryTime = parseIsoDateToMillis((String) expiryObj);
                        } else if (expiryObj instanceof Number) {
                            licenseExpiryTime = ((Number) expiryObj).longValue();
                        }

                        // Parse serverTime - can be ISO string or timestamp
                        Object serverTimeObj = jsonResponse.opt("serverTime");
                        if (serverTimeObj instanceof String) {
                            lastServerTime = parseIsoDateToMillis((String) serverTimeObj);
                        } else if (serverTimeObj instanceof Number) {
                            lastServerTime = ((Number) serverTimeObj).longValue();
                        }
                        if (lastServerTime == 0) {
                            lastServerTime = System.currentTimeMillis();
                        }

                        lastValidationTime = System.currentTimeMillis();
                        saveLicenseData();
                        result.set(true);
                    } else {
                        invalidateLicense("Access denied");
                        deleteModelFile();
                    }
                }

                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Sync validation error", e);
            } finally {
                latch.countDown();
            }
        }).start();

        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Validation interrupted", e);
        }

        return result.get();
    }

    private void invalidateLicense(String reason) {
        Log.w(TAG, "Invalidating license: " + reason);
        licenseValid = false;
        saveLicenseData();
    }

    private void saveLicenseData() {
        prefs.edit()
                .putBoolean(KEY_LICENSE_VALID, licenseValid)
                .putLong(KEY_LICENSE_EXPIRY, licenseExpiryTime)
                .putLong(KEY_LAST_SERVER_TIME, lastServerTime)
                .putLong(KEY_LAST_VALIDATION_TIME, lastValidationTime)
                .apply();

        Log.i(TAG, "License data saved - Valid: " + licenseValid);
    }

    /**
     * Delete the model file when license becomes invalid
     */
    public void deleteModelFile() {
        try {
            File modelFile = new File(context.getExternalFilesDir(null), "model.task");
            if (modelFile.exists()) {
                boolean deleted = modelFile.delete();
                Log.i(TAG, "Model file deleted: " + deleted);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting model file", e);
        }
    }

    /**
     * Check if online revalidation is required
     */
    public boolean needsOnlineRevalidation() {
        if (lastValidationTime == 0) return true;
        long timeSinceLastValidation = System.currentTimeMillis() - lastValidationTime;
        return timeSinceLastValidation > MAX_OFFLINE_DURATION;
    }

    /**
     * Get remaining offline time in milliseconds
     */
    public long getRemainingOfflineTime() {
        long timeSinceLastValidation = System.currentTimeMillis() - lastValidationTime;
        return Math.max(0, MAX_OFFLINE_DURATION - timeSinceLastValidation);
    }

    /**
     * Get remaining license time in milliseconds
     */
    public long getRemainingLicenseTime() {
        return Math.max(0, licenseExpiryTime - System.currentTimeMillis());
    }

    /**
     * Clear all license data (for logout/reset)
     */
    public void clearLicenseData() {
        licenseValid = false;
        licenseExpiryTime = 0;
        lastServerTime = 0;
        lastValidationTime = 0;
        userEmail = null;

        prefs.edit()
                .remove(KEY_LICENSE_VALID)
                .remove(KEY_LICENSE_EXPIRY)
                .remove(KEY_LAST_SERVER_TIME)
                .remove(KEY_LAST_VALIDATION_TIME)
                .remove(KEY_USER_EMAIL)
                .apply();

        deleteModelFile();
        Log.i(TAG, "License data cleared");
    }

    /**
     * Parse ISO 8601 date string to milliseconds timestamp
     * Supports formats like: 2026-03-15T10:30:00.000Z, 2026-03-15T10:30:00Z, 2026-03-15
     */
    private long parseIsoDateToMillis(String isoDateString) {
        if (isoDateString == null || isoDateString.isEmpty()) {
            return 0;
        }

        // List of ISO date formats to try
        String[] dateFormats = {
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",  // 2026-03-15T10:30:00.000Z
            "yyyy-MM-dd'T'HH:mm:ss'Z'",       // 2026-03-15T10:30:00Z
            "yyyy-MM-dd'T'HH:mm:ssXXX",       // 2026-03-15T10:30:00+00:00
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",   // 2026-03-15T10:30:00.000+00:00
            "yyyy-MM-dd'T'HH:mm:ss",          // 2026-03-15T10:30:00
            "yyyy-MM-dd"                       // 2026-03-15
        };

        for (String format : dateFormats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = sdf.parse(isoDateString);
                if (date != null) {
                    long millis = date.getTime();
                    Log.d(TAG, "Parsed ISO date '" + isoDateString + "' to " + millis + " ms");
                    return millis;
                }
            } catch (Exception e) {
                // Try next format
            }
        }

        // If all formats fail, try to parse as long (in case it's already a timestamp)
        try {
            long millis = Long.parseLong(isoDateString);
            Log.d(TAG, "Parsed numeric timestamp: " + millis);
            return millis;
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse date string: " + isoDateString);
            return 0;
        }
    }
}
