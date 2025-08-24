package com.knets.jr;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Enhanced Location Service with multi-layered location tracking:
 * 1. GPS (high accuracy, requires location enabled)
 * 2. Network (WiFi/cellular towers, works when location off)
 * 3. Cell Tower triangulation (works without location services)
 * 4. IP Geolocation (fallback, works on any internet connection)
 */
public class EnhancedLocationService extends Service implements LocationListener {
    private static final String TAG = "KnetsEnhancedLocation";
    
    private LocationManager locationManager;
    private TelephonyManager telephonyManager;
    private WifiManager wifiManager;
    private OkHttpClient httpClient;
    private String deviceImei;
    
    // Location method priorities
    private enum LocationMethod {
        GPS("gps", 1),
        NETWORK("network", 2), 
        CELL_TOWER("cell_tower", 3),
        IP_GEOLOCATION("ip_location", 4);
        
        final String name;
        final int priority;
        
        LocationMethod(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Enhanced Location Service created");
        
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        
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
        String action = intent != null ? intent.getAction() : "";
        
        if ("REQUEST_LOCATION".equals(action)) {
            Log.d(TAG, "🌍 Multi-layer location request initiated by parent");
            requestLocationWithFallback();
        }
        
        return START_NOT_STICKY;
    }
    
    /**
     * Request location using multiple methods with intelligent fallback
     */
    private void requestLocationWithFallback() {
        Log.d(TAG, "🎯 Starting multi-layer location detection");
        
        // Method 1: Try GPS first (highest accuracy)
        if (tryGPSLocation()) {
            Log.d(TAG, "✅ GPS method initiated");
            return; // GPS request sent, wait for callback
        }
        
        // Method 2: Try Network-based location
        if (tryNetworkLocation()) {
            Log.d(TAG, "✅ Network location method initiated");
            return; // Network request sent, wait for callback
        }
        
        // Method 3: Try Cell Tower triangulation
        if (tryCellTowerLocation()) {
            Log.d(TAG, "✅ Cell tower location detected");
            return; // Cell tower location sent
        }
        
        // Method 4: Fallback to IP Geolocation
        Log.d(TAG, "🌐 Falling back to IP geolocation");
        tryIPGeolocation();
    }
    
    /**
     * Method 1: GPS Location (highest accuracy)
     */
    private boolean tryGPSLocation() {
        if (!hasLocationPermissions()) {
            Log.d(TAG, "❌ GPS: No location permissions");
            return false;
        }
        
        if (!isLocationEnabled()) {
            Log.d(TAG, "❌ GPS: Location services disabled");
            return false;
        }
        
        if (locationManager == null) {
            Log.d(TAG, "❌ GPS: LocationManager not available");
            return false;
        }
        
        try {
            // Get last known GPS location first
            Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnown != null && isLocationFresh(lastKnown)) {
                Log.d(TAG, "📍 GPS: Using fresh cached location");
                sendLocationToServer(lastKnown, LocationMethod.GPS);
                return true;
            }
            
            // Request fresh GPS update
            Log.d(TAG, "📡 GPS: Requesting fresh location");
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, null);
            return true;
            
        } catch (SecurityException e) {
            Log.e(TAG, "❌ GPS: Security exception", e);
            return false;
        }
    }
    
    /**
     * Method 2: Network-based location (WiFi/cellular)
     */
    private boolean tryNetworkLocation() {
        if (!hasLocationPermissions()) {
            Log.d(TAG, "❌ Network: No location permissions");
            return false;
        }
        
        if (locationManager == null) {
            Log.d(TAG, "❌ Network: LocationManager not available");
            return false;
        }
        
        try {
            // Get last known network location
            Location lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (lastKnown != null && isLocationFresh(lastKnown)) {
                Log.d(TAG, "📍 Network: Using fresh cached location");
                sendLocationToServer(lastKnown, LocationMethod.NETWORK);
                return true;
            }
            
            // Request fresh network location
            Log.d(TAG, "📡 Network: Requesting fresh location");
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, null);
            return true;
            
        } catch (SecurityException e) {
            Log.e(TAG, "❌ Network: Security exception", e);
            return false;
        }
    }
    
    /**
     * Method 3: Cell Tower triangulation (works without location services)
     */
    private boolean tryCellTowerLocation() {
        if (!hasPhonePermissions()) {
            Log.d(TAG, "❌ Cell Tower: No phone state permissions");
            return false;
        }
        
        try {
            CellLocation cellLocation = telephonyManager.getCellLocation();
            
            if (cellLocation instanceof GsmCellLocation) {
                GsmCellLocation gsmLocation = (GsmCellLocation) cellLocation;
                int cellId = gsmLocation.getCid();
                int lac = gsmLocation.getLac();
                
                if (cellId != -1 && lac != -1) {
                    Log.d(TAG, "📡 Cell Tower: CID=" + cellId + ", LAC=" + lac);
                    // Use Google's Cell Tower API or OpenCellID for triangulation
                    requestCellTowerLocation(cellId, lac);
                    return true;
                }
            }
            
            // Alternative: Use CellInfo API (Android 17+)
            List<CellInfo> cellInfos = telephonyManager.getAllCellInfo();
            if (cellInfos != null && !cellInfos.isEmpty()) {
                for (CellInfo cellInfo : cellInfos) {
                    if (cellInfo.isRegistered()) {
                        // Extract cell data for triangulation
                        if (processCellInfo(cellInfo)) {
                            return true;
                        }
                    }
                }
            }
            
        } catch (SecurityException e) {
            Log.e(TAG, "❌ Cell Tower: Security exception", e);
        } catch (Exception e) {
            Log.e(TAG, "❌ Cell Tower: General exception", e);
        }
        
        return false;
    }
    
    /**
     * Method 4: IP Geolocation (always works with internet)
     */
    private void tryIPGeolocation() {
        new Thread(() -> {
            try {
                // Use multiple IP geolocation services for reliability
                String[] ipServices = {
                    "http://ip-api.com/json/?fields=lat,lon,city,country,status",
                    "https://ipapi.co/json/",
                    "https://freegeoip.app/json/"
                };
                
                for (String serviceUrl : ipServices) {
                    try {
                        JsonObject locationData = getIPLocation(serviceUrl);
                        if (locationData != null) {
                            double lat = locationData.get("lat").getAsDouble();
                            double lon = locationData.get("lon").getAsDouble();
                            
                            Log.d(TAG, "🌐 IP Geolocation: " + lat + ", " + lon);
                            sendIPLocationToServer(lat, lon, serviceUrl);
                            return; // Success, exit
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "IP service failed: " + serviceUrl, e);
                        continue; // Try next service
                    }
                }
                
                Log.e(TAG, "❌ All IP geolocation services failed");
                
            } catch (Exception e) {
                Log.e(TAG, "❌ IP Geolocation: General exception", e);
            }
        }).start();
    }
    
    /**
     * Process cell tower information for location
     */
    private boolean processCellInfo(CellInfo cellInfo) {
        try {
            if (cellInfo instanceof CellInfoGsm) {
                CellInfoGsm gsmInfo = (CellInfoGsm) cellInfo;
                int cellId = gsmInfo.getCellIdentity().getCid();
                int lac = gsmInfo.getCellIdentity().getLac();
                requestCellTowerLocation(cellId, lac);
                return true;
            } else if (cellInfo instanceof CellInfoLte) {
                CellInfoLte lteInfo = (CellInfoLte) cellInfo;
                int cellId = lteInfo.getCellIdentity().getCi();
                int tac = lteInfo.getCellIdentity().getTac();
                requestCellTowerLocation(cellId, tac);
                return true;
            } else if (cellInfo instanceof CellInfoWcdma) {
                CellInfoWcdma wcdmaInfo = (CellInfoWcdma) cellInfo;
                int cellId = wcdmaInfo.getCellIdentity().getCid();
                int lac = wcdmaInfo.getCellIdentity().getLac();
                requestCellTowerLocation(cellId, lac);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing cell info", e);
        }
        return false;
    }
    
    /**
     * Request location from cell tower data
     */
    private void requestCellTowerLocation(int cellId, int lac) {
        // For now, send cell tower data to server for processing
        // Server can use Google's Geolocation API or OpenCellID
        JsonObject cellData = new JsonObject();
        cellData.addProperty("cellId", cellId);
        cellData.addProperty("lac", lac);
        cellData.addProperty("method", "cell_tower");
        
        Log.d(TAG, "📡 Sending cell tower data: CID=" + cellId + ", LAC=" + lac);
        sendCellDataToServer(cellData);
    }
    
    /**
     * Get location from IP geolocation service
     */
    private JsonObject getIPLocation(String serviceUrl) throws IOException {
        URL url = new URL(serviceUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        
        if (connection.getResponseCode() == 200) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            return new com.google.gson.Gson().fromJson(response.toString(), JsonObject.class);
        }
        
        return null;
    }
    
    /**
     * Send IP-based location to server
     */
    private void sendIPLocationToServer(double latitude, double longitude, String source) {
        JsonObject locationData = new JsonObject();
        locationData.addProperty("deviceImei", deviceImei);
        locationData.addProperty("latitude", latitude);
        locationData.addProperty("longitude", longitude);
        locationData.addProperty("accuracy", 5000); // IP location is less accurate
        locationData.addProperty("timestamp", System.currentTimeMillis());
        locationData.addProperty("provider", "ip_geolocation");
        locationData.addProperty("source", source);
        
        sendDataToServer(locationData, "location-update");
    }
    
    /**
     * Send cell tower data to server
     */
    private void sendCellDataToServer(JsonObject cellData) {
        cellData.addProperty("deviceImei", deviceImei);
        cellData.addProperty("timestamp", System.currentTimeMillis());
        
        sendDataToServer(cellData, "cell-location");
    }
    
    /**
     * Send standard location to server
     */
    private void sendLocationToServer(Location location, LocationMethod method) {
        JsonObject locationData = new JsonObject();
        locationData.addProperty("deviceImei", deviceImei);
        locationData.addProperty("latitude", location.getLatitude());
        locationData.addProperty("longitude", location.getLongitude());
        locationData.addProperty("accuracy", location.getAccuracy());
        locationData.addProperty("timestamp", System.currentTimeMillis());
        locationData.addProperty("provider", method.name);
        locationData.addProperty("altitude", location.getAltitude());
        locationData.addProperty("speed", location.getSpeed());
        
        sendDataToServer(locationData, "location-update");
    }
    
    /**
     * Generic method to send data to server
     */
    private void sendDataToServer(JsonObject data, String endpoint) {
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), 
                data.toString()
        );
        
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/" + endpoint;
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send " + endpoint + " data", e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "✅ " + endpoint + " data sent successfully");
                } else {
                    Log.e(TAG, "❌ " + endpoint + " failed: " + response.message());
                }
                response.close();
            }
        });
    }
    
    // LocationListener implementation
    @Override
    public void onLocationChanged(Location location) {
        String provider = location.getProvider();
        LocationMethod method = LocationManager.GPS_PROVIDER.equals(provider) ? 
                                LocationMethod.GPS : LocationMethod.NETWORK;
        
        Log.d(TAG, "📍 Location received via " + provider + ": " + 
              location.getLatitude() + ", " + location.getLongitude() + 
              " (±" + location.getAccuracy() + "m)");
        
        sendLocationToServer(location, method);
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
    
    // Utility methods
    private boolean hasLocationPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED;
    }
    
    private boolean hasPhonePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) 
                == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED;
    }
    
    private boolean isLocationEnabled() {
        try {
            int locationMode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE);
            return locationMode != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }
    
    private boolean isLocationFresh(Location location) {
        long locationAge = System.currentTimeMillis() - location.getTime();
        return locationAge < 300000; // 5 minutes
    }
    
    private String getServerBaseUrl() {
        SharedPreferences prefs = getSharedPreferences("knets_jr", Context.MODE_PRIVATE);
        String customUrl = prefs.getString("server_url", "");
        
        if (!customUrl.isEmpty()) {
            return customUrl;
        }
        
        return "https://109f494a-e49e-4a8a-973f-659f67493858-00-23mfa5oss8rxi.janeway.replit.dev";
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
        Log.d(TAG, "Enhanced Location Service destroyed");
    }
}