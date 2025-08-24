package com.knets.jr;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LocationService extends Service implements LocationListener {
    private static final String TAG = "KnetsJrLocation";
    private static final String CHANNEL_ID = "KnetsJrLocationChannel";
    private static final int NOTIFICATION_ID = 1001;
    
    private LocationManager locationManager;
    private OkHttpClient httpClient;
    private String deviceImei;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "LocationService created");
        
        createNotificationChannel();
        
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        
        deviceImei = getSharedPreferences("knets_jr", Context.MODE_PRIVATE)
                .getString("device_imei", "");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "LocationService started");
        
        startForeground(NOTIFICATION_ID, createNotification());
        startLocationUpdates();
        
        // Check if this is an immediate location request from parent
        if (intent != null && intent.getBooleanExtra("immediate_update", false)) {
            Log.d(TAG, "Immediate location update requested by parent");
            requestImmediateLocation();
        }
        
        return START_STICKY;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Knets Jr Location Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Provides location tracking for parental monitoring");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Knets Jr Active")
                .setContentText("Location tracking enabled for parental monitoring")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    
    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted");
            return;
        }
        
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        
        if (locationManager != null) {
            try {
                // Request updates every 5 minutes or 100 meters
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 
                        300000, // 5 minutes
                        100,    // 100 meters
                        this
                );
                
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        300000, // 5 minutes
                        100,    // 100 meters
                        this
                );
                
                Log.d(TAG, "Location updates requested");
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception requesting location updates", e);
            }
        }
    }
    
    @Override
    public void onLocationChanged(Location location) {
        Log.d(TAG, "Location changed: " + location.getLatitude() + ", " + location.getLongitude());
        sendLocationToServer(location);
    }
    
    private void sendLocationToServer(Location location) {
        if (deviceImei.isEmpty()) {
            Log.e(TAG, "Device IMEI not available for location update");
            return;
        }
        
        JsonObject locationData = new JsonObject();
        locationData.addProperty("deviceImei", deviceImei);
        locationData.addProperty("latitude", location.getLatitude());
        locationData.addProperty("longitude", location.getLongitude());
        locationData.addProperty("accuracy", location.getAccuracy());
        locationData.addProperty("timestamp", System.currentTimeMillis());
        locationData.addProperty("provider", location.getProvider());
        
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), 
                locationData.toString()
        );
        
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/location-update";
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send location update", e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Location update sent successfully");
                } else {
                    Log.e(TAG, "Location update failed: " + response.message());
                }
                response.close();
            }
        });
    }
    
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d(TAG, "Location provider status changed: " + provider + " = " + status);
    }
    
    @Override
    public void onProviderEnabled(String provider) {
        Log.d(TAG, "Location provider enabled: " + provider);
    }
    
    @Override
    public void onProviderDisabled(String provider) {
        Log.d(TAG, "Location provider disabled: " + provider);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
        Log.d(TAG, "LocationService destroyed");
    }
    
    private void requestImmediateLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted for immediate update");
            return;
        }
        
        Log.d(TAG, "ONE-TIME location request initiated by parent");
        
        if (locationManager != null) {
            try {
                // Get last known location first for immediate response
                Location lastKnownGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                
                Location bestLocation = null;
                if (lastKnownGPS != null && lastKnownNetwork != null) {
                    bestLocation = lastKnownGPS.getAccuracy() < lastKnownNetwork.getAccuracy() 
                                 ? lastKnownGPS : lastKnownNetwork;
                } else if (lastKnownGPS != null) {
                    bestLocation = lastKnownGPS;
                } else if (lastKnownNetwork != null) {
                    bestLocation = lastKnownNetwork;
                }
                
                if (bestLocation != null) {
                    Log.d(TAG, "Sending cached location immediately to parent (ONE-TIME)");
                    sendLocationToServer(bestLocation);
                }
                
                // Request fresh location update (SINGLE UPDATE, not continuous)
                Log.d(TAG, "Requesting fresh GPS location (ONE-TIME only)");
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, null);
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, null);
                
                // Note: After onLocationChanged is called, no more continuous tracking occurs
                // This is a ONE-TIME location request, not continuous monitoring
                
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception requesting immediate location", e);
            }
        }
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