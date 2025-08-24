package com.knets.jr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ServerPollingService extends Service {
    private static final String TAG = "KnetsJrPolling";
    private static final String CHANNEL_ID = "KnetsJrPollingChannel";
    private static final int NOTIFICATION_ID = 1002;
    private static final int POLLING_INTERVAL = 30000; // 30 seconds
    
    private OkHttpClient httpClient;
    private String deviceImei;
    private boolean isPolling = false;
    private Thread pollingThread;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ServerPollingService created");
        
        createNotificationChannel();
        
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        
        SharedPreferences prefs = getSharedPreferences("knets_jr", Context.MODE_PRIVATE);
        deviceImei = prefs.getString("device_imei", "");
        if (deviceImei.isEmpty()) {
            deviceImei = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ServerPollingService started");
        
        startForeground(NOTIFICATION_ID, createNotification());
        startPolling();
        
        return START_STICKY;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Knets Jr Server Communication",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors parent requests and device commands");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Knets Jr Active")
                .setContentText("Monitoring parent requests...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    
    private void startPolling() {
        if (isPolling || deviceImei.isEmpty()) {
            Log.d(TAG, "Polling already started or device ID missing");
            return;
        }
        
        isPolling = true;
        
        pollingThread = new Thread(() -> {
            Log.d(TAG, "Polling thread started");
            
            while (isPolling) {
                try {
                    checkForParentCommands();
                    Thread.sleep(POLLING_INTERVAL);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Polling thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in polling loop", e);
                    try {
                        Thread.sleep(POLLING_INTERVAL);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            
            Log.d(TAG, "Polling thread ended");
        });
        
        pollingThread.start();
    }
    
    private void checkForParentCommands() {
        // Use production URL or configurable server address
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/check-commands/" + deviceImei;
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to check parent commands", e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Command check failed: " + response.message());
                    response.close();
                    return;
                }
                
                String responseBody = response.body() != null ? response.body().string() : "";
                response.close();
                
                try {
                    JsonObject jsonResponse = new Gson().fromJson(responseBody, JsonObject.class);
                    
                    if (jsonResponse.has("commands") && jsonResponse.get("commands").isJsonArray()) {
                        processCommands(jsonResponse.get("commands").getAsJsonArray());
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error processing command response", e);
                }
            }
        });
    }
    
    private void processCommands(com.google.gson.JsonArray commands) {
        for (int i = 0; i < commands.size(); i++) {
            JsonObject command = commands.get(i).getAsJsonObject();
            String commandType = command.get("type").getAsString();
            
            Log.d(TAG, "Processing command: " + commandType);
            
            switch (commandType) {
                case "ENABLE_LOCATION":
                    handleEnableLocationCommand();
                    break;
                case "REQUEST_LOCATION":
                    handleLocationRequestCommand();
                    break;
                case "LOCK_DEVICE":
                    handleLockDeviceCommand();
                    break;
                case "UNLOCK_DEVICE":
                    handleUnlockDeviceCommand();
                    break;
                default:
                    Log.w(TAG, "Unknown command type: " + commandType);
                    break;
            }
            
            // Acknowledge command processed
            acknowledgeCommand(command.get("id").getAsString());
        }
    }
    
    private void handleEnableLocationCommand() {
        Log.d(TAG, "Parent requested location service activation");
        
        // Check if location service is already running
        Intent locationServiceIntent = new Intent(this, LocationService.class);
        
        // Auto-enable location service when parent requests it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(locationServiceIntent);
        } else {
            startService(locationServiceIntent);
        }
        
        // Update notification to show location is active
        updateNotification("Location tracking activated by parent request");
        
        Log.d(TAG, "Location service auto-enabled successfully");
    }
    
    private void handleLocationRequestCommand() {
        Log.d(TAG, "Parent requested enhanced multi-layer location update");
        
        // Use enhanced location service with multiple fallback methods
        Intent enhancedLocationIntent = new Intent(this, EnhancedLocationService.class);
        enhancedLocationIntent.setAction("REQUEST_LOCATION");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(enhancedLocationIntent);
        } else {
            startService(enhancedLocationIntent);
        }
        
        updateNotification("Multi-layer location tracking: GPS→Network→Cell→IP");
    }
    
    private void handleLockDeviceCommand() {
        Log.d(TAG, "Parent requested device lock");
        
        // Trigger device lock through DeviceAdminReceiver
        Intent lockIntent = new Intent(this, MainActivity.class);
        lockIntent.putExtra("command", "lock_device");
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(lockIntent);
        
        updateNotification("Device locked by parent");
    }
    
    private void handleUnlockDeviceCommand() {
        Log.d(TAG, "Parent requested device unlock");
        
        // Remove lock restrictions
        Intent unlockIntent = new Intent(this, MainActivity.class);
        unlockIntent.putExtra("command", "unlock_device");
        unlockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(unlockIntent);
        
        updateNotification("Device unlocked by parent");
    }
    
    private void acknowledgeCommand(String commandId) {
        JsonObject ackData = new JsonObject();
        ackData.addProperty("commandId", commandId);
        ackData.addProperty("deviceImei", deviceImei);
        ackData.addProperty("status", "processed");
        ackData.addProperty("timestamp", System.currentTimeMillis());
        
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json"), 
                ackData.toString()
        );
        
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/acknowledge-command";
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to acknowledge command: " + commandId, e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Command acknowledged: " + commandId);
                } else {
                    Log.e(TAG, "Failed to acknowledge command: " + commandId + " - " + response.message());
                }
                response.close();
            }
        });
    }
    
    private void updateNotification(String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Knets Jr Active")
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        isPolling = false;
        
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        
        Log.d(TAG, "ServerPollingService destroyed");
    }
    
    /**
     * Get the server base URL - configurable for different environments
     */
    private String getServerBaseUrl() {
        SharedPreferences prefs = getSharedPreferences("knets_jr", Context.MODE_PRIVATE);
        String customUrl = prefs.getString("server_url", "");
        
        if (!customUrl.isEmpty()) {
            return customUrl;
        }
        
        // Current Replit development URL
        return "https://109f494a-e49e-4a8a-973f-659f67493858-00-23mfa5oss8rxi.janeway.replit.dev";
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}