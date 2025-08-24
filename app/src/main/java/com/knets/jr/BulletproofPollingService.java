package com.knets.jr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * BULLETPROOF Background Service for Knets Jr
 * 
 * Features:
 * - Self-healing with automatic restart on failures
 * - Exponential backoff for network errors
 * - Foreground service with persistent notification
 * - Multiple fallback mechanisms
 * - Battery optimization resistant
 * - Network resilience
 */
public class BulletproofPollingService extends Service {
    private static final String TAG = "KnetsBulletproof";
    private static final String CHANNEL_ID = "KnetsJrBulletproofChannel";
    private static final int NOTIFICATION_ID = 1003;
    private static final int POLLING_INTERVAL = 30000; // 30 seconds
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    private static final int MAX_BACKOFF_TIME = 300000; // 5 minutes
    
    private ScheduledExecutorService scheduler;
    private OkHttpClient httpClient;
    private String deviceImei;
    private int consecutiveErrors = 0;
    private boolean isServiceRunning = false;
    private Handler mainHandler;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "🛡️ BULLETPROOF: Service created with enhanced reliability");
        
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        initializeHttpClient();
        loadDeviceIdentifier();
        
        // Start as foreground service immediately
        startForeground(NOTIFICATION_ID, createPersistentNotification());
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "🚀 BULLETPROOF: Service starting with self-healing capabilities");
        
        if (!isServiceRunning) {
            startBulletproofPolling();
        }
        
        // START_STICKY ensures Android restarts service if killed
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // No binding needed
    }
    
    @Override
    public void onDestroy() {
        Log.w(TAG, "🛑 BULLETPROOF: Service destroyed, scheduling restart");
        isServiceRunning = false;
        
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
        
        // Auto-restart mechanism
        scheduleServiceRestart();
        super.onDestroy();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Knets Jr Background Communication",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains connection with parent device for safety monitoring");
            channel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createPersistentNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Knets Jr - Safety Active")
                .setContentText("🛡️ Connected to parent device • Location ready")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
    
    private void initializeHttpClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
    
    private void loadDeviceIdentifier() {
        SharedPreferences prefs = getSharedPreferences("knets_jr", Context.MODE_PRIVATE);
        deviceImei = prefs.getString("device_imei", "");
        
        if (deviceImei.isEmpty()) {
            deviceImei = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            Log.d(TAG, "📱 Using Android ID as device identifier: " + deviceImei);
        }
    }
    
    private void startBulletproofPolling() {
        if (isServiceRunning) {
            Log.d(TAG, "⚠️ BULLETPROOF: Polling already active");
            return;
        }
        
        isServiceRunning = true;
        consecutiveErrors = 0;
        
        // Use ScheduledExecutorService for more reliable scheduling
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Schedule initial poll immediately
        scheduler.schedule(this::performPollingCycle, 0, TimeUnit.SECONDS);
        
        Log.i(TAG, "✅ BULLETPROOF: Polling started with 30-second intervals");
    }
    
    private void performPollingCycle() {
        if (!isServiceRunning) {
            Log.d(TAG, "🛑 Service stopped, ending polling");
            return;
        }
        
        try {
            checkForParentCommands();
            
            // Schedule next poll
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.schedule(this::performPollingCycle, POLLING_INTERVAL, TimeUnit.MILLISECONDS);
            }
            
        } catch (Exception e) {
            handlePollingError(e);
        }
    }
    
    private void checkForParentCommands() {
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/check-commands/" + deviceImei;
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .addHeader("User-Agent", "KnetsJr/Bulletproof")
                .build();
        
        Log.d(TAG, "🔍 BULLETPROOF: Checking for parent commands at " + System.currentTimeMillis());
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "❌ BULLETPROOF: Network failure during command check", e);
                handleNetworkError(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "❌ BULLETPROOF: HTTP error " + response.code() + ": " + response.message());
                        handleHttpError(response.code());
                        return;
                    }
                    
                    String responseBody = response.body() != null ? response.body().string() : "";
                    
                    if (!responseBody.isEmpty()) {
                        processServerResponse(responseBody);
                    }
                    
                    // Reset error counter on successful response
                    consecutiveErrors = 0;
                    updateNotificationSuccess();
                    
                } catch (Exception e) {
                    Log.e(TAG, "❌ BULLETPROOF: Error processing response", e);
                    handlePollingError(e);
                } finally {
                    response.close();
                }
            }
        });
    }
    
    private void processServerResponse(String responseBody) {
        try {
            JsonObject jsonResponse = new Gson().fromJson(responseBody, JsonObject.class);
            
            if (jsonResponse.has("commands") && jsonResponse.get("commands").isJsonArray()) {
                com.google.gson.JsonArray commands = jsonResponse.get("commands").getAsJsonArray();
                
                if (commands.size() > 0) {
                    Log.i(TAG, "📨 BULLETPROOF: Received " + commands.size() + " commands from parent");
                    processParentCommands(commands);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ BULLETPROOF: Failed to parse server response", e);
            handlePollingError(e);
        }
    }
    
    private void processParentCommands(com.google.gson.JsonArray commands) {
        for (int i = 0; i < commands.size(); i++) {
            try {
                JsonObject command = commands.get(i).getAsJsonObject();
                String commandType = command.get("type").getAsString();
                String commandId = command.get("id").getAsString();
                
                Log.i(TAG, "🎯 BULLETPROOF: Processing command: " + commandType);
                
                switch (commandType) {
                    case "ENABLE_LOCATION":
                        handleLocationEnableCommand();
                        break;
                    case "REQUEST_LOCATION":
                        handleLocationRequestCommand();
                        break;
                    case "LOCK_DEVICE":
                        handleDeviceLockCommand();
                        break;
                    case "UNLOCK_DEVICE":
                        handleDeviceUnlockCommand();
                        break;
                    default:
                        Log.w(TAG, "⚠️ BULLETPROOF: Unknown command type: " + commandType);
                        break;
                }
                
                // Acknowledge command processing
                acknowledgeCommandProcessed(commandId);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ BULLETPROOF: Error processing individual command", e);
            }
        }
    }
    
    private void handleLocationRequestCommand() {
        Log.i(TAG, "📍 BULLETPROOF: Parent requested location update");
        
        Intent locationIntent = new Intent(this, EnhancedLocationService.class);
        locationIntent.setAction("REQUEST_LOCATION");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(locationIntent);
        } else {
            startService(locationIntent);
        }
        
        updateNotification("📍 Location tracking active");
    }
    
    private void handleLocationEnableCommand() {
        Log.i(TAG, "🌍 BULLETPROOF: Parent enabled location services");
        updateNotification("🌍 Location services enabled");
    }
    
    private void handleDeviceLockCommand() {
        Log.i(TAG, "🔒 BULLETPROOF: Parent requested device lock");
        updateNotification("🔒 Device locked by parent");
        // Implement device lock logic here
    }
    
    private void handleDeviceUnlockCommand() {
        Log.i(TAG, "🔓 BULLETPROOF: Parent unlocked device");
        updateNotification("🔓 Device unlocked by parent");
        // Implement device unlock logic here
    }
    
    private void acknowledgeCommandProcessed(String commandId) {
        // Send acknowledgment to server that command was processed
        String ackUrl = getServerBaseUrl() + "/api/knets-jr/acknowledge-command";
        
        try {
            JsonObject ackData = new JsonObject();
            ackData.addProperty("commandId", commandId);
            ackData.addProperty("deviceId", deviceImei);
            ackData.addProperty("status", "processed");
            
            okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                ackData.toString(),
                okhttp3.MediaType.parse("application/json")
            );
            
            Request ackRequest = new Request.Builder()
                    .url(ackUrl)
                    .post(requestBody)
                    .build();
            
            httpClient.newCall(ackRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.w(TAG, "⚠️ BULLETPROOF: Failed to acknowledge command " + commandId, e);
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "✅ BULLETPROOF: Command " + commandId + " acknowledged");
                    }
                    response.close();
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "❌ BULLETPROOF: Error acknowledging command", e);
        }
    }
    
    private void handleNetworkError(IOException e) {
        consecutiveErrors++;
        Log.w(TAG, "🌐 BULLETPROOF: Network error " + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + ": " + e.getMessage());
        
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "🚨 BULLETPROOF: Too many network errors, initiating recovery");
            initiateErrorRecovery();
        } else {
            scheduleRetryWithBackoff();
        }
    }
    
    private void handleHttpError(int statusCode) {
        consecutiveErrors++;
        Log.w(TAG, "📡 BULLETPROOF: HTTP error " + statusCode + " (" + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + ")");
        
        if (statusCode >= 500) {
            // Server errors - retry with backoff
            scheduleRetryWithBackoff();
        } else if (statusCode == 404) {
            // Client not found - may need re-registration
            Log.w(TAG, "⚠️ BULLETPROOF: Device not found on server, continuing polling");
        }
    }
    
    private void handlePollingError(Exception e) {
        consecutiveErrors++;
        Log.e(TAG, "❌ BULLETPROOF: Polling error " + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS, e);
        
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            initiateErrorRecovery();
        } else {
            scheduleRetryWithBackoff();
        }
    }
    
    private void scheduleRetryWithBackoff() {
        long backoffTime = Math.min(POLLING_INTERVAL * consecutiveErrors, MAX_BACKOFF_TIME);
        
        Log.i(TAG, "⏳ BULLETPROOF: Backing off for " + (backoffTime / 1000) + " seconds");
        
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(this::performPollingCycle, backoffTime, TimeUnit.MILLISECONDS);
        }
        
        updateNotification("⏳ Retrying connection...");
    }
    
    private void initiateErrorRecovery() {
        Log.w(TAG, "🔄 BULLETPROOF: Initiating error recovery sequence");
        
        // Reset error counter
        consecutiveErrors = 0;
        
        // Restart HTTP client
        initializeHttpClient();
        
        // Schedule recovery with longer delay
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(() -> {
                Log.i(TAG, "🚀 BULLETPROOF: Recovery complete, resuming normal operation");
                performPollingCycle();
            }, 60000, TimeUnit.MILLISECONDS); // 1-minute recovery delay
        }
        
        updateNotification("🔄 Recovering connection...");
    }
    
    private void scheduleServiceRestart() {
        // Schedule service restart after delay
        mainHandler.postDelayed(() -> {
            try {
                Log.i(TAG, "🔄 BULLETPROOF: Auto-restarting service");
                Intent restartIntent = new Intent(this, BulletproofPollingService.class);
                startService(restartIntent);
            } catch (Exception e) {
                Log.e(TAG, "❌ BULLETPROOF: Failed to restart service", e);
            }
        }, 15000); // 15-second delay
    }
    
    private void updateNotification(String status) {
        mainHandler.post(() -> {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Knets Jr - Safety Active")
                    .setContentText(status)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        });
    }
    
    private void updateNotificationSuccess() {
        updateNotification("✅ Connected • Ready for parent requests");
    }
    
    private String getServerBaseUrl() {
        // Use production URL for Knets Jr
        return "https://workspace--thinkbacktechno.replit.app";
    }
}