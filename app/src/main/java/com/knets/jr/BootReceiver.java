package com.knets.jr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver for automatic startup of Knets Jr services
 * This receiver starts the BulletproofPollingService automatically when:
 * 1. Device boots up
 * 2. App is updated/replaced
 * 3. Package is replaced
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "KnetsBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "🚀 Boot event received: " + action);
        
        // Check if Knets Jr has been set up (parent code stored)
        SharedPreferences prefs = context.getSharedPreferences("knets_jr", Context.MODE_PRIVATE);
        String parentCode = prefs.getString("parent_code", "");
        String deviceImei = prefs.getString("device_imei", "");
        
        if (parentCode.isEmpty()) {
            Log.d(TAG, "⏸️ Parent code not set, skipping auto-start");
            return;
        }
        
        Log.i(TAG, "✅ AUTO-START: Knets Jr configured, starting services...");
        
        // Start the bulletproof polling service
        Intent serviceIntent = new Intent(context, BulletproofPollingService.class);
        serviceIntent.putExtra("auto_start", true);
        serviceIntent.putExtra("boot_reason", action);
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
                Log.i(TAG, "🎯 BulletproofPollingService started via foreground service");
            } else {
                context.startService(serviceIntent);
                Log.i(TAG, "🎯 BulletproofPollingService started via regular service");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to auto-start BulletproofPollingService", e);
        }
        
        // Also launch MainActivity silently to ensure everything is initialized
        try {
            Intent mainActivityIntent = new Intent(context, MainActivity.class);
            mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mainActivityIntent.putExtra("silent_launch", true);
            mainActivityIntent.putExtra("boot_start", true);
            
            context.startActivity(mainActivityIntent);
            Log.i(TAG, "📱 MainActivity launched silently for initialization");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Could not launch MainActivity silently", e);
        }
        
        Log.i(TAG, "🎉 AUTO-START COMPLETE: Knets Jr is now running in background");
    }
}