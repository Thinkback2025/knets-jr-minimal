package com.knets.jr;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

// Android 13+ specific imports
import android.window.OnBackInvokedDispatcher;
import android.window.OnBackInvokedCallback;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.core.os.BuildCompat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
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

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "KnetsJr";
    private static final int DEVICE_ADMIN_REQUEST = 1001;
    private static final int LOCATION_PERMISSION_REQUEST = 1002;
    private static final int PHONE_STATE_PERMISSION_REQUEST = 1003;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1004;
    
    private EditText etParentCode, etSecretCode, etDeviceImei;
    private TextView tvSecretCodeLabel, tvImeiLabel, tvImeiInstructions;
    private Button btnConnect, btnEnableDeviceAdmin, btnEnableLocation;
    private TextView tvStatus, tvStep, tvDeviceInfo;
    private ProgressBar progressBar;
    
    private DevicePolicyManager devicePolicyManager;
    private ComponentName deviceAdminReceiver;
    private String deviceImei = "";
    private String storedParentCode = "";
    private String storedSecretCode = "";
    private int currentStep = 1;
    
    // 3-Step Workflow States
    private boolean parentCodeVerified = false;
    private boolean imeiSaved = false;
    private boolean secretCodeVerified = false;
    private boolean deviceAdminEnabled = false;
    private boolean workflowCompleted = false;
    
    private OkHttpClient httpClient;
    private SharedPreferences preferences;
    
    // Android 13+ specific callbacks
    private OnBackInvokedCallback backInvokedCallback;
    private OnBackPressedCallback backPressedCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Android 13+ security initialization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setupAndroid13Compatibility();
        }
        
        setContentView(R.layout.activity_main);
        
        initializeViews();
        initializeServices();
        loadStoredData();
        updateUI();
        
        Log.d(TAG, "MainActivity created - Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    }
    
    private void initializeViews() {
        etParentCode = findViewById(R.id.etParentCode);
        etSecretCode = findViewById(R.id.etSecretCode);
        etDeviceImei = findViewById(R.id.etDeviceImei);
        tvSecretCodeLabel = findViewById(R.id.tvSecretCodeLabel);
        tvImeiLabel = findViewById(R.id.tvImeiLabel);
        tvImeiInstructions = findViewById(R.id.tvImeiInstructions);
        btnConnect = findViewById(R.id.btnConnect);
        btnEnableDeviceAdmin = findViewById(R.id.btnEnableDeviceAdmin);
        btnEnableLocation = findViewById(R.id.btnEnableLocation);
        tvStatus = findViewById(R.id.tvStatus);
        tvStep = findViewById(R.id.tvStep);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        progressBar = findViewById(R.id.progressBar);
        
        // Set up click listeners
        btnConnect.setOnClickListener(v -> handleConnectStep());
        btnEnableDeviceAdmin.setOnClickListener(v -> enableDeviceAdmin());
        btnEnableLocation.setOnClickListener(v -> requestLocationPermissions());
        
        // Auto-hide input field after code is stored (smart UI behavior)
        etParentCode.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                // Convert input to uppercase for consistency
                String input = s.toString().toUpperCase();
                if (!input.equals(s.toString())) {
                    etParentCode.setText(input);
                    etParentCode.setSelection(input.length());
                }
                
                if (input.length() == 10) {
                    // Auto-advance when 10-character code is complete
                    btnConnect.setEnabled(true);
                    btnConnect.setText("Verify Code");
                } else {
                    btnConnect.setEnabled(false);
                    btnConnect.setText("Save Code");
                }
            }
        });
        
        // IMEI input validation (smart UI behavior)
        etDeviceImei.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 15) {
                    // Auto-advance when 15-digit IMEI is complete
                    btnConnect.setEnabled(true);
                    btnConnect.setText("Save IMEI");
                } else if (s.length() > 0) {
                    btnConnect.setEnabled(false);
                    btnConnect.setText("Enter 15-digit IMEI");
                }
            }
        });

        // Secret code input validation (smart UI behavior)
        etSecretCode.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 4) {
                    // Auto-advance when 4-digit secret code is complete
                    btnConnect.setEnabled(true);
                    btnConnect.setText("Save Secret Code");
                }
            }
        });
    }
    
    private void initializeServices() {
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        deviceAdminReceiver = new ComponentName(this, KnetsDeviceAdminReceiver.class);
        preferences = getSharedPreferences("knets_jr", Context.MODE_PRIVATE);
        
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    private void loadStoredData() {
        storedParentCode = preferences.getString("parent_code", "");
        storedSecretCode = preferences.getString("secret_code", "");
        parentCodeVerified = preferences.getBoolean("parent_code_verified", false);
        imeiSaved = preferences.getBoolean("imei_saved", false);
        secretCodeVerified = preferences.getBoolean("secret_code_verified", false);
        deviceAdminEnabled = devicePolicyManager.isAdminActive(deviceAdminReceiver);
        workflowCompleted = preferences.getBoolean("workflow_completed", false);
        
        // Determine current step based on state
        updateCurrentStep();
        
        // Get device IMEI - request permission if needed (but don't require it for Step 1)
        // TEMPORARILY DISABLED: requestImeiPermissionAndGet(); // Disabled to test polling service
        
        // Set Android ID as fallback for testing
        deviceImei = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.d(TAG, "Using Android ID for testing: " + (deviceImei != null ? deviceImei.substring(0, Math.min(8, deviceImei.length())) + "..." : "null"));
        
        Log.d(TAG, "Loaded state - Parent Code Verified: " + (parentCodeVerified ? "✓" : "✗") + 
               " Secret Code Verified: " + (secretCodeVerified ? "✓" : "✗") + 
               " Admin: " + (deviceAdminEnabled ? "✓" : "✗"));
    }
    
    private void updateCurrentStep() {
        if (!parentCodeVerified || !imeiSaved) currentStep = 1; // Step 1: Parent code + IMEI
        else if (!secretCodeVerified) currentStep = 2;
        else if (!deviceAdminEnabled) currentStep = 3;
        else currentStep = 4; // Completed
    }
    
    private void updateUI() {
        // Smart UI behavior: Hide input fields after successful verification
        if (parentCodeVerified) {
            etParentCode.setVisibility(View.GONE);
            findViewById(R.id.tvCodeLabel).setVisibility(View.GONE);
        } else {
            if (!storedParentCode.isEmpty()) {
                etParentCode.setText(storedParentCode);
            }
        }
        
        // Show IMEI input after parent code verification but before completion
        if (parentCodeVerified && !imeiSaved) {
            tvImeiLabel.setVisibility(View.VISIBLE);
            tvImeiInstructions.setVisibility(View.VISIBLE);
            etDeviceImei.setVisibility(View.VISIBLE);
        } else if (imeiSaved) {
            tvImeiLabel.setVisibility(View.GONE);
            tvImeiInstructions.setVisibility(View.GONE);
            etDeviceImei.setVisibility(View.GONE);
        }
        
        // Show secret code input after parent code + IMEI completion
        if (parentCodeVerified && imeiSaved && !secretCodeVerified) {
            tvSecretCodeLabel.setVisibility(View.VISIBLE);
            etSecretCode.setVisibility(View.VISIBLE);
        } else if (secretCodeVerified) {
            tvSecretCodeLabel.setVisibility(View.GONE);
            etSecretCode.setVisibility(View.GONE);
        }
        
        String stepText = "Step " + currentStep + " of 3: ";
        String statusText = "";
        
        // Reset button visibility
        btnConnect.setVisibility(View.GONE);
        btnEnableDeviceAdmin.setVisibility(View.GONE);
        btnEnableLocation.setVisibility(View.GONE);
        
        switch (currentStep) {
            case 1:
                if (!parentCodeVerified) {
                    stepText += "Parent Code Verification";
                    statusText = "Enter your 10-digit parent code to connect with Knets dashboard";
                    btnConnect.setVisibility(View.VISIBLE);
                    btnConnect.setText("Verify Parent Code");
                    btnConnect.setEnabled(etParentCode.getText().length() == 10);
                } else if (!imeiSaved) {
                    stepText += "Device IMEI Registration";
                    statusText = "Enter your device IMEI number for security identification";
                    btnConnect.setVisibility(View.VISIBLE);
                    btnConnect.setText("Save IMEI");
                    btnConnect.setEnabled(etDeviceImei.getText().length() == 15);
                }
                break;
                
            case 2:
                stepText += "Secret Code Verification";
                statusText = "Enter your 4-digit security code for device administration";
                btnConnect.setVisibility(View.VISIBLE);
                btnConnect.setText("Verify Secret Code");
                btnConnect.setEnabled(etSecretCode.getText().length() == 4);
                break;
                
            case 3:
                stepText += "Enable Device Admin";
                statusText = "Device administrator permission is essential for:\n\n" +
                           "• Remote lock/unlock of device\n" +
                           "• Screen time monitoring and control\n" +
                           "• App usage restrictions\n" +
                           "• Network access management\n" +
                           "• Schedule enforcement\n\n" +
                           "This ensures complete parental control from your dashboard.";
                btnEnableDeviceAdmin.setVisibility(View.VISIBLE);
                btnEnableDeviceAdmin.setText("Enable Device Admin");
                break;
                
            case 5:
                stepText += "Enable Location Services";
                statusText = "Allow location access for GPS tracking";
                btnEnableLocation.setVisibility(View.VISIBLE);
                break;
                
            case 6:
                stepText += "Register Device";
                statusText = "Registering device with Knets...";
                registerDevice();
                break;
                
            case 7:
                stepText += "Complete Setup";
                statusText = "Finalizing Knets Jr setup...";
                completeSetup();
                break;
                
            case 4:
                tvStep.setText("Device Registration Complete");
                tvStatus.setText("✅ Knets Jr setup completed successfully!");
                showCompletedState();
                return; // Don't update progress bar or step text below
        }
        
        tvStep.setText(stepText);
        tvStatus.setText(statusText);
        
        // Update device info
        updateDeviceInfo();
        
        // Update progress bar
        int progress = Math.min(currentStep * 100 / 3, 100);
        progressBar.setProgress(progress);
    }
    
    private void handleConnectStep() {
        if (currentStep == 1) {
            if (!parentCodeVerified) {
                // Step 1a: Verify parent code
                String code = etParentCode.getText().toString().trim();
                if (code.length() != 10) {
                    showToast("Please enter a 10-digit parent code");
                    return;
                }
                
                storedParentCode = code;
                preferences.edit()
                        .putString("parent_code", code)
                        .apply();
                
                verifyCodeWithServer();
                
            } else if (!imeiSaved) {
                // Step 1b: Save IMEI after parent code verification
                String imei = etDeviceImei.getText().toString().trim();
                if (imei.length() != 15) {
                    showToast("Please enter a valid 15-digit IMEI number");
                    return;
                }
                
                saveImeiWithServer(imei);
            }
            
        } else if (currentStep == 2) {
            // Step 2: Verify secret code
            String secretCode = etSecretCode.getText().toString().trim();
            if (secretCode.length() != 4) {
                showToast("Please enter a 4-digit secret code");
                return;
            }
            
            storedSecretCode = secretCode;
            preferences.edit()
                    .putString("secret_code", secretCode)
                    .apply();
                    
            verifySecretCodeWithServer(secretCode);
        }
    }
    
    private void verifyCodeWithServer() {
        if (storedParentCode.isEmpty()) {
            showToast("No parent code found");
            return;
        }
        
        showProgress("Verifying parent code...");
        
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("parentCode", storedParentCode);
        // Only send parent code for verification, IMEI comes later
        
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), 
                jsonBody.toString()
        );
        
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/verify-code";
        Log.d(TAG, "Attempting verification with URL: " + serverUrl);
        Log.d(TAG, "Request body: " + jsonBody.toString());
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    hideProgress();
                    String errorMsg = "Network error: " + e.getMessage();
                    showToast(errorMsg);
                    Log.e(TAG, "Code verification network failure", e);
                    Log.e(TAG, "Failed URL: " + serverUrl);
                    
                    // Try fallback URLs
                    tryFallbackServers();
                });
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                
                runOnUiThread(() -> {
                    hideProgress();
                    
                    if (response.isSuccessful()) {
                        try {
                            JsonObject jsonResponse = new Gson().fromJson(responseBody, JsonObject.class);
                            boolean valid = jsonResponse.get("valid").getAsBoolean();
                            String message = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "";
                            
                            if (valid) {
                                boolean requiresImei = jsonResponse.has("requiresImei") && 
                                                     jsonResponse.get("requiresImei").getAsBoolean();
                                
                                if (requiresImei) {
                                    // Parent code verified, now need IMEI
                                    showToast("✅ Parent code verified! Please enter device IMEI.");
                                    
                                    parentCodeVerified = true;
                                    preferences.edit()
                                            .putBoolean("parent_code_verified", true)
                                            .apply();
                                    
                                    Log.d(TAG, "✅ Parent code verified, requesting IMEI");
                                    updateUI(); // This will show IMEI input fields
                                } else {
                                    // Both parent code and IMEI saved successfully
                                    showToast("✅ " + message);
                                    
                                    parentCodeVerified = true;
                                    imeiSaved = true;
                                    preferences.edit()
                                            .putBoolean("parent_code_verified", true)
                                            .putBoolean("imei_saved", true)
                                            .apply();
                                    
                                    Log.d(TAG, "✅ Parent code and IMEI saved successfully");
                                    updateCurrentStep();
                                    updateUI();
                                }
                            } else {
                                showToast("❌ " + message);
                                Log.e(TAG, "❌ Parent code verification failed: " + message);
                            }
                        } catch (Exception e) {
                            showToast("Error processing server response");
                            Log.e(TAG, "Error parsing verification response", e);
                        }
                    } else {
                        showToast("Code verification failed: " + response.message());
                    }
                });
            }
        });
    }
    
    private void saveImeiWithServer(String imei) {
        if (storedParentCode.isEmpty()) {
            showToast("No parent code found");
            return;
        }
        
        showProgress("Saving device IMEI...");
        
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("parentCode", storedParentCode);
        jsonBody.addProperty("deviceImei", imei);
        
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), 
                jsonBody.toString()
        );
        
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/verify-code";
        Log.d(TAG, "Saving IMEI with URL: " + serverUrl);
        Log.d(TAG, "Request body: " + jsonBody.toString());
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    hideProgress();
                    String errorMsg = "Network error: " + e.getMessage();
                    showToast(errorMsg);
                    Log.e(TAG, "IMEI save network failure", e);
                });
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                
                runOnUiThread(() -> {
                    hideProgress();
                    
                    if (response.isSuccessful()) {
                        try {
                            JsonObject jsonResponse = new Gson().fromJson(responseBody, JsonObject.class);
                            boolean valid = jsonResponse.get("valid").getAsBoolean();
                            String message = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "";
                            
                            if (valid) {
                                showToast("✅ " + message);
                                
                                // Mark IMEI as saved and advance to next step
                                imeiSaved = true;
                                preferences.edit()
                                        .putBoolean("imei_saved", true)
                                        .apply();
                                
                                Log.d(TAG, "✅ Device IMEI saved successfully");
                                updateCurrentStep();
                                updateUI();
                            } else {
                                showToast("❌ " + message);
                                Log.e(TAG, "❌ IMEI save failed: " + message);
                            }
                        } catch (Exception e) {
                            showToast("Error processing server response");
                            Log.e(TAG, "Error parsing IMEI save response", e);
                        }
                    } else {
                        showToast("IMEI save failed: " + response.message());
                    }
                });
            }
        });
    }
    
    private void verifySecretCodeWithServer(String secretCode) {
        showProgress("Verifying secret code...");
        
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("parentCode", storedParentCode);
        jsonBody.addProperty("secretCode", secretCode);
        
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), 
                jsonBody.toString()
        );
        
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/verify-codes";
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    hideProgress();
                    showToast("Secret code verification failed: " + e.getMessage());
                    Log.e(TAG, "Secret code verification failed", e);
                });
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                
                runOnUiThread(() -> {
                    hideProgress();
                    
                    if (response.isSuccessful()) {
                        try {
                            JsonObject jsonResponse = new Gson().fromJson(responseBody, JsonObject.class);
                            boolean success = jsonResponse.get("success").getAsBoolean();
                            
                            if (success) {
                                secretCodeVerified = true;
                                preferences.edit()
                                        .putBoolean("secret_code_verified", true)
                                        .apply();
                                
                                showToast("Secret code verification successful");
                                updateCurrentStep();
                                updateUI();
                            } else {
                                showToast("Invalid secret code. Please check and try again.");
                            }
                        } catch (Exception e) {
                            showToast("Error processing server response");
                            Log.e(TAG, "Error parsing secret verification response", e);
                        }
                    } else {
                        showToast("Secret code verification failed: " + response.message());
                    }
                });
            }
        });
    }
    
    private void enableDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminReceiver);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                "Enable device admin to allow Knets parental controls");
        startActivityForResult(intent, DEVICE_ADMIN_REQUEST);
    }
    
    private void requestLocationPermissions() {
        // Android 13+ compatible permission request
        String[] permissions;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ needs notification permission too
            permissions = new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions = new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
            };
        } else {
            permissions = new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }
        
        ActivityCompat.requestPermissions(this, permissions, LOCATION_PERMISSION_REQUEST);
    }
    
    private void registerDevice() {
        if (!parentCodeVerified || deviceImei.isEmpty()) {
            showToast("Cannot register: Code not verified or IMEI not available");
            return;
        }
        
        // Final attempt to get real IMEI before registration
        ensureDeviceIdentifier();
        
        showProgress("Registering device...");
        
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("parentCode", storedParentCode);
        jsonBody.addProperty("deviceImei", deviceImei);
        jsonBody.addProperty("deviceInfo", getDeviceInfoJson());
        
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), 
                jsonBody.toString()
        );
        
        Request request = new Request.Builder()
                .url(getServerBaseUrl() + "/api/knets-jr/register-device")
                .post(body)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    hideProgress();
                    showToast("Device registration failed: " + e.getMessage());
                    Log.e(TAG, "Device registration failed", e);
                });
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                
                runOnUiThread(() -> {
                    hideProgress();
                    
                    if (response.isSuccessful()) {
                        try {
                            JsonObject jsonResponse = new Gson().fromJson(responseBody, JsonObject.class);
                            boolean success = jsonResponse.get("success").getAsBoolean();
                            
                            if (success) {
                                showToast("Device registered successfully!");
                                completeSetup();
                            } else {
                                showToast("Device registration failed: " + jsonResponse.get("message").getAsString());
                            }
                        } catch (Exception e) {
                            showToast("Error processing registration response");
                            Log.e(TAG, "Error parsing registration response", e);
                        }
                    } else {
                        showToast("Device registration failed: " + response.message());
                    }
                });
            }
        });
    }
    
    private void completeSetup() {
        // Start server polling service for auto-enable location functionality
        Intent pollingIntent = new Intent(this, ServerPollingService.class);
        startForegroundService(pollingIntent);
        
        // Store device IMEI for services
        if (!deviceImei.isEmpty()) {
            preferences.edit()
                    .putString("device_imei", deviceImei)
                    .apply();
        }
        
        workflowCompleted = true;
        preferences.edit()
                .putBoolean("workflow_completed", true)
                .apply();
        
        currentStep = 4;
        showToast("Knets Jr setup completed!");
        updateUI();
        
        Log.d(TAG, "3-step workflow completed - Location auto-enable activated");
    }
    
    private void showCompletedState() {
        // Hide all buttons and show completion message
        btnConnect.setVisibility(View.GONE);
        btnEnableDeviceAdmin.setVisibility(View.GONE);
        btnEnableLocation.setVisibility(View.GONE);
        
        tvStatus.setText("✅ Knets Jr setup completed successfully!\n\n" +
                "Your device is now connected to your parent's dashboard with:\n\n" +
                "• Remote device control enabled\n" +
                "• Automatic location services (activated when requested)\n" +
                "• Screen time monitoring\n" +
                "• App usage tracking\n\n" +
                "The app will run in the background and automatically respond to parent requests.");
        
        // Update device information display
        updateDeviceInfo();
    }
    
    private void requestImeiPermissionAndGet() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) 
                == PackageManager.PERMISSION_GRANTED) {
            // Permission already granted, get IMEI immediately
            collectDeviceImei();
        } else {
            // Permission not granted, use Android ID temporarily and request permission
            deviceImei = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            Log.d(TAG, "Using temporary Android ID, requesting READ_PHONE_STATE permission immediately");
            
            // Show rationale to user
            showToast("Requesting device permission for security identification");
            
            // Request permission immediately for better user experience
            ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.READ_PHONE_STATE}, 
                    PHONE_STATE_PERMISSION_REQUEST);
        }
        
        Log.d(TAG, "Initial Device ID: " + (deviceImei != null ? deviceImei.substring(0, Math.min(4, deviceImei.length())) + "****" : "null"));
    }
    
    private void collectDeviceImei() {
        Log.d(TAG, "🔍 Starting IMEI collection process...");
        Log.d(TAG, "Android Version: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Log.d(TAG, "Device Model: " + Build.MODEL + " by " + Build.MANUFACTURER);
        
        try {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                Log.d(TAG, "TelephonyManager available, attempting IMEI collection...");
                
                // Try multiple methods for different Android versions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ (API 29+) - More restrictive
                    Log.d(TAG, "Using Android 10+ IMEI method");
                    try {
                        deviceImei = telephonyManager.getImei();
                        Log.d(TAG, "getImei() result: " + (deviceImei != null ? "SUCCESS (" + deviceImei.length() + " chars)" : "NULL"));
                    } catch (SecurityException e) {
                        Log.e(TAG, "SecurityException on getImei(): " + e.getMessage());
                    }
                    
                    if (deviceImei == null || deviceImei.isEmpty()) {
                        try {
                            deviceImei = telephonyManager.getImei(0);
                            Log.d(TAG, "getImei(0) result: " + (deviceImei != null ? "SUCCESS (" + deviceImei.length() + " chars)" : "NULL"));
                        } catch (Exception e) {
                            Log.e(TAG, "Exception on getImei(0): " + e.getMessage());
                        }
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8-9 (API 26-28)
                    Log.d(TAG, "Using Android 8-9 IMEI method");
                    deviceImei = telephonyManager.getImei();
                    if (deviceImei == null || deviceImei.isEmpty()) {
                        deviceImei = telephonyManager.getImei(0);
                    }
                } else {
                    // Pre-Android 8 (API < 26)
                    Log.d(TAG, "Using legacy getDeviceId() method");
                    deviceImei = telephonyManager.getDeviceId();
                }
                
                // Validate IMEI
                if (deviceImei != null && !deviceImei.isEmpty()) {
                    Log.d(TAG, "Raw device identifier: " + deviceImei.substring(0, Math.min(8, deviceImei.length())) + "...");
                    Log.d(TAG, "Identifier length: " + deviceImei.length() + " characters");
                    
                    if (deviceImei.length() >= 14 && deviceImei.matches("\\d{14,15}")) {
                        Log.d(TAG, "✅ Valid IMEI collected: " + deviceImei.substring(0, 4) + "****");
                        return;
                    } else if (deviceImei.length() >= 14) {
                        Log.d(TAG, "✅ Device identifier collected (may be IMEI): " + deviceImei.substring(0, 4) + "****");
                        return;
                    } else {
                        Log.d(TAG, "❌ Identifier too short to be IMEI: " + deviceImei.length() + " chars");
                    }
                }
            } else {
                Log.e(TAG, "TelephonyManager is null - device may not support telephony");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException collecting device identifier", e);
        } catch (Exception e) {
            Log.e(TAG, "Exception collecting device identifier", e);
        }
        
        // Fallback to Android ID
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceImei = androidId;
        Log.d(TAG, "❌ IMEI unavailable, using Android ID: " + androidId.substring(0, Math.min(8, androidId.length())) + "****");
        Log.d(TAG, "Note: Android 10+ restricts IMEI access even with permissions");
    }
    
    private void ensureDeviceIdentifier() {
        // Try to get real IMEI if we only have Android ID
        if (deviceImei != null && deviceImei.length() < 14) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) 
                    == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permission available, attempting to upgrade from Android ID to IMEI");
                collectDeviceImei();
                // If we successfully got real IMEI, update server
                if (deviceImei != null && deviceImei.length() >= 14 && !storedParentCode.isEmpty()) {
                    Log.d(TAG, "Successfully upgraded to real IMEI, updating server");
                    updateDeviceImeiOnServer();
                }
            }
        }
    }
    
    private void updateDeviceImeiOnServer() {
        if (deviceImei == null || deviceImei.isEmpty() || storedParentCode.isEmpty()) {
            return;
        }
        
        // Only send real IMEI to server (skip Android ID updates)
        if (deviceImei.length() < 14) {
            Log.d(TAG, "Skipping Android ID update, waiting for real IMEI");
            return;
        }
        
        // Update server with real IMEI if we have it
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("imei", deviceImei);
        jsonBody.addProperty("parentCode", storedParentCode);
        
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), 
                jsonBody.toString()
        );
        
        String serverUrl = getServerBaseUrl() + "/api/knets-jr/update-imei";
        
        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to update IMEI on server", e);
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "✅ Real IMEI updated on server successfully");
                    runOnUiThread(() -> {
                        updateDeviceInfo(); // Refresh UI to show real IMEI
                    });
                }
            }
        });
    }
    
    private boolean hasLocationPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED;
    }
    
    private String getDeviceInfoJson() {
        JsonObject deviceInfo = new JsonObject();
        deviceInfo.addProperty("model", Build.MODEL);
        deviceInfo.addProperty("manufacturer", Build.MANUFACTURER);
        deviceInfo.addProperty("androidVersion", Build.VERSION.RELEASE);
        deviceInfo.addProperty("apiLevel", Build.VERSION.SDK_INT);
        deviceInfo.addProperty("deviceImei", deviceImei);
        return deviceInfo.toString();
    }
    
    private void updateDeviceInfo() {
        String info = "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                     "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                     "ID: " + (deviceImei != null ? deviceImei.substring(0, Math.min(8, deviceImei.length())) + "..." : "Unknown");
        tvDeviceInfo.setText(info);
    }
    
    private void showProgress(String message) {
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText(message);
    }
    
    private void hideProgress() {
        progressBar.setVisibility(View.GONE);
    }
    
    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Log.d(TAG, "Toast: " + message);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == DEVICE_ADMIN_REQUEST) {
            deviceAdminEnabled = devicePolicyManager.isAdminActive(deviceAdminReceiver);
            
            if (deviceAdminEnabled) {
                showToast("Device admin enabled successfully!");
                completeSetup();
            } else {
                showToast("Device admin is required for parental controls");
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                         @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            boolean fineLocationGranted = grantResults.length > 0 && 
                    grantResults[0] == PackageManager.PERMISSION_GRANTED;
            boolean coarseLocationGranted = grantResults.length > 1 && 
                    grantResults[1] == PackageManager.PERMISSION_GRANTED;
            
            if (fineLocationGranted && coarseLocationGranted) {
                showToast("Location permissions granted!");
                
                // For Android 10+ request background location separately
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(this, 
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 
                            LOCATION_PERMISSION_REQUEST + 1);
                }
            } else {
                showToast("Location permissions are required for GPS tracking");
            }
        } else if (requestCode == PHONE_STATE_PERMISSION_REQUEST) {
            // TEMPORARILY DISABLED IMEI COLLECTION FOR TESTING
            Log.d(TAG, "📱 IMEI permission callback - DISABLED for polling service testing");
            showToast("Using Android ID for testing");
            
            /* DISABLED FOR TESTING - IMEI COLLECTION
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "📱 READ_PHONE_STATE permission granted! Collecting real IMEI...");
                collectDeviceImei();
                
                // Update UI to show real IMEI
                updateDeviceInfo();
                
                // Update server with real IMEI if we have parent code and it's a real IMEI
                if (!storedParentCode.isEmpty() && deviceImei != null && deviceImei.length() >= 14) {
                    Log.d(TAG, "Updating server with real IMEI after permission grant");
                    updateDeviceImeiOnServer();
                    showToast("Device IMEI updated for security!");
                } else {
                    Log.d(TAG, "No parent code or still using Android ID, skipping server update");
                }
            } else {
                Log.d(TAG, "📱 READ_PHONE_STATE permission denied, keeping Android ID");
                showToast("Using device ID for identification");
            }
            */
        }
    }
    
    /**
     * Get the server base URL - configurable for different environments
     */
    private String getServerBaseUrl() {
        // Check for custom server URL in preferences
        SharedPreferences prefs = getSharedPreferences("knets_jr", Context.MODE_PRIVATE);
        String customUrl = prefs.getString("server_url", "");
        
        if (!customUrl.isEmpty()) {
            return customUrl;
        }
        
        // Try multiple server URLs - WORKING EXTERNAL URL PRIORITY
        String[] serverUrls = {
            "https://109f494a-e49e-4a8a-973f-659f67493858-00-23mfa5oss8rxi.janeway.replit.dev", // CORRECT REPLIT URL
            "https://knets.replit.app",                       // Production URL
            "http://10.0.2.2:5000",                          // Android emulator localhost
            "http://192.168.1.100:5000",                     // Local network IP fallback
            "http://192.168.0.100:5000"                      // Router IP range fallback
        };
        
        // Return the appropriate URL based on current attempt
        int currentAttempt = prefs.getInt("current_server_attempt", 0);
        if (currentAttempt < serverUrls.length) {
            return serverUrls[currentAttempt];
        }
        
        return serverUrls[0];  // Default to first URL
    }
    
    /**
     * Android 13+ compatibility setup for critical security and runtime requirements
     */
    private void setupAndroid13Compatibility() {
        try {
            // Setup OnBackInvokedCallback for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                backInvokedCallback = new OnBackInvokedCallback() {
                    @Override
                    public void onBackInvoked() {
                        // Handle back gesture for Android 13+
                        handleBackAction();
                    }
                };
                
                // Register the callback
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, 
                    backInvokedCallback
                );
            }
            
            // Setup traditional back pressed for older versions
            backPressedCallback = new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    handleBackAction();
                }
            };
            getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
            
            Log.d(TAG, "Android 13+ compatibility setup completed successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error setting up Android 13+ compatibility", e);
            // Continue without crashing - fallback to basic functionality
        }
    }
    
    /**
     * Handle back action for both Android 13+ and older versions
     */
    private void handleBackAction() {
        if (workflowCompleted) {
            // Move app to background instead of closing during active monitoring
            moveTaskToBack(true);
        } else {
            // Prevent accidental exit during setup
            showToast("Complete the setup to use Knets Jr");
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Clean up Android 13+ callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering back callback", e);
            }
        }
        
        if (backPressedCallback != null) {
            backPressedCallback.remove();
        }
    }
    
    private void tryFallbackServers() {
        SharedPreferences prefs = getSharedPreferences("knets_jr", Context.MODE_PRIVATE);
        int currentAttempt = prefs.getInt("current_server_attempt", 0);
        
        String[] serverUrls = {
            "https://109f494a-e49e-4a8a-973f-659f67493858-00-23mfa5oss8rxi.janeway.replit.dev", // WORKING REPLIT URL
            "https://knets.replit.app",                       // Production URL
            "http://10.0.2.2:5000",                          // Emulator localhost
            "http://192.168.1.100:5000",                     // Local network IP
            "http://192.168.0.100:5000"                      // Router IP range
        };
        
        if (currentAttempt < serverUrls.length - 1) {
            currentAttempt++;
            prefs.edit().putInt("current_server_attempt", currentAttempt).apply();
            
            Log.d(TAG, "Trying fallback server #" + currentAttempt + ": " + serverUrls[currentAttempt]);
            showToast("Trying alternate server...");
            
            // Retry verification with new server
            new Handler().postDelayed(() -> verifyCodeWithServer(), 1000);
        } else {
            // All servers failed
            prefs.edit().putInt("current_server_attempt", 0).apply();
            showToast("All servers unreachable. Check internet connection.");
            Log.e(TAG, "All server URLs failed");
        }
    }
}