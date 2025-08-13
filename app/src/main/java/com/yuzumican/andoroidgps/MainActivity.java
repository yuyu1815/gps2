package com.yuzumican.andoroidgps;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.github.chrisbanes.photoview.OnPhotoTapListener;
import com.yuzumican.andoroidgps.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String FINGERPRINTS_FILE = "fingerprints.json";
    private static final int WIFI_SCAN_INTERVAL_MS = 5000;
    private static final int AR_UPDATE_INTERVAL_MS = 100;
    private static final int K_NEAREST_NEIGHBORS = 3;
    private static final float AR_TO_MAP_SCALE = 100.0f; // 1 meter in AR space = 100 pixels on map

    private ActivityMainBinding binding;
    private boolean isLearningMode = false;

    // --- Wi-Fi Fingerprinting ---
    private WifiManager wifiManager;
    private BroadcastReceiver wifiScanReceiver;
    private List<FingerprintRecord> fingerprints = new ArrayList<>();
    private DatabaseHelper dbHelper;
    private float lastTappedX, lastTappedY;
    private final Handler wifiPositioningHandler = new Handler(Looper.getMainLooper());
    private ImageView userPositionView;
    private ImageView learningMarkerView;

    // --- ARCore ---
    private Session arSession;
    private boolean arCoreInstallRequested;
    private final Handler arCoreUpdateHandler = new Handler(Looper.getMainLooper());
    private Pose referenceArPose;
    private float lastKnownMapX, lastKnownMapY;

    // --- Permission Handling ---
    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                permissions.forEach((permission, isGranted) -> {
                    if (!isGranted) {
                        Toast.makeText(this, "Permission " + permission + " is required.", Toast.LENGTH_LONG).show();
                    }
                });
            });

    // --- Photo Picker ---
    private final ActivityResultLauncher<String> pickMedia =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    Log.d(TAG, "Photo selected: " + uri);
                    binding.mapImageView.setImageURI(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        dbHelper = new DatabaseHelper(this);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        setupWifiScanReceiver();
        initUserPositionView();
        initLearningMarkerView();

        loadFingerprintsFromDb();
        setupUIListeners();

        binding.mapImageView.setImageResource(android.R.drawable.ic_dialog_map);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndRequestPermissions();
        try {
            if (arSession == null) {
                if (!ensureArCoreInstalled()) return;
                arSession = new Session(this);
                Config config = new Config(arSession);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                arSession.configure(config);
            }
            arSession.resume();
        } catch (Exception e) {
            Log.e(TAG, "ARCore session creation/resume failed", e);
            arSession = null;
            return;
        }
        updateUIMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wifiPositioningHandler.removeCallbacks(wifiPositioningRunnable);
        arCoreUpdateHandler.removeCallbacks(arCoreUpdateRunnable);
        if (arSession != null) {
            arSession.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(wifiScanReceiver);
        if (arSession != null) {
            arSession.close();
            arSession = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_load_map) {
            pickMedia.launch("image/*");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupUIListeners() {
        binding.modeToggleButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isLearningMode = !isChecked;
            updateUIMode();
        });

        binding.learnButton.setOnClickListener(v -> {
            if (isLearningMode) {
                startWifiScan();
            }
        });

        binding.mapImageView.setOnPhotoTapListener((view, x, y) -> {
            if (isLearningMode) {
                // The x and y are normalized (0.0f to 1.0f). We need to convert them to view coordinates.
                lastTappedX = x * view.getWidth();
                lastTappedY = y * view.getHeight();

                learningMarkerView.setX(lastTappedX - learningMarkerView.getWidth() / 2f);
                learningMarkerView.setY(lastTappedY - learningMarkerView.getHeight() / 2f);
                learningMarkerView.setVisibility(View.VISIBLE);

                Toast.makeText(this, String.format("Selected (%.0f, %.0f). Press 'Record' to scan.", lastTappedX, lastTappedY), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateUIMode() {
        if (isLearningMode) {
            binding.learnButton.setVisibility(View.VISIBLE);
            userPositionView.setVisibility(View.GONE);
            wifiPositioningHandler.removeCallbacks(wifiPositioningRunnable);
            arCoreUpdateHandler.removeCallbacks(arCoreUpdateRunnable);
            Toast.makeText(this, "Switched to Learning Mode", Toast.LENGTH_SHORT).show();
        } else {
            binding.learnButton.setVisibility(View.GONE);
            learningMarkerView.setVisibility(View.GONE);
            userPositionView.setVisibility(View.VISIBLE);
            referenceArPose = null; // Invalidate AR pose until we get a new Wi-Fi anchor
            wifiPositioningHandler.post(wifiPositioningRunnable);
            arCoreUpdateHandler.post(arCoreUpdateRunnable);
            Toast.makeText(this, "Switched to Positioning Mode", Toast.LENGTH_SHORT).show();
        }
    }

    private void startWifiScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Cannot scan without location permission.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean success = wifiManager.startScan();
        if (!success) {
            Log.w(TAG, "Wi-Fi scan failed to start.");
        }
    }

    private void setupWifiScanReceiver() {
        wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                    boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                    if (success) {
                        handleScanSuccess(wifiManager.getScanResults());
                    } else {
                        handleScanFailure();
                    }
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, intentFilter);
    }

    private void handleScanSuccess(List<ScanResult> results) {
        updateStatusText("Wi-Fi APs Found: " + results.size());
        Map<String, Integer> currentWifiData = new HashMap<>();
        for (ScanResult scanResult : results) {
            currentWifiData.put(scanResult.BSSID, scanResult.level);
        }

        if (isLearningMode) {
            learningMarkerView.setVisibility(View.GONE);
            FingerprintRecord newRecord = new FingerprintRecord(lastTappedX, lastTappedY, currentWifiData);
            saveFingerprintToDb(newRecord);
            fingerprints.add(newRecord); // Also add to in-memory list for immediate use
            String message = String.format("Fingerprint saved for (%.0f, %.0f) with %d APs.", lastTappedX, lastTappedY, results.size());
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            updateStatusText("Fingerprints: " + fingerprints.size());
        } else {
            if (fingerprints.isEmpty()) {
                return;
            }
            findBestMatchPosition(currentWifiData);
        }
    }

    private void handleScanFailure() {
        Log.w(TAG, "Wi-Fi scan failed.");
    }

    private void findBestMatchPosition(Map<String, Integer> currentWifiData) {
        if (fingerprints.size() < K_NEAREST_NEIGHBORS) {
            return;
        }

        List<DistanceRecord> distances = new ArrayList<>();
        for (FingerprintRecord record : fingerprints) {
            double distance = 0;
            int commonAPs = 0;
            for (Map.Entry<String, Integer> entry : currentWifiData.entrySet()) {
                if (record.wifiData.containsKey(entry.getKey())) {
                    distance += Math.pow(entry.getValue() - record.wifiData.get(entry.getKey()), 2);
                    commonAPs++;
                }
            }
            if (commonAPs > 0) {
                distances.add(new DistanceRecord(record, distance / commonAPs));
            }
        }

        if (distances.size() < K_NEAREST_NEIGHBORS) {
            return;
        }

        Collections.sort(distances);

        float totalWeight = 0;
        float weightedX = 0;
        float weightedY = 0;
        for (int i = 0; i < K_NEAREST_NEIGHBORS; i++) {
            DistanceRecord neighbor = distances.get(i);
            double weight = 1.0 / (neighbor.distance + 1e-6);
            totalWeight += weight;
            weightedX += neighbor.record.x * weight;
            weightedY += neighbor.record.y * weight;
        }

        float estimatedX = weightedX / totalWeight;
        float estimatedY = weightedY / totalWeight;

        // This is our new "anchor". We update the dot and reset the AR reference pose.
        updateUserPosition(estimatedX, estimatedY);
        lastKnownMapX = estimatedX;
        lastKnownMapY = estimatedY;
        try {
            if (arSession != null) {
                Frame frame = arSession.update();
                referenceArPose = frame.getCamera().getPose();
                Log.d(TAG, "Wi-Fi anchor set. AR reference pose updated.");
            }
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available for AR pose reset", e);
        }
    }

    private final Runnable arCoreUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (arSession == null || isLearningMode) {
                arCoreUpdateHandler.postDelayed(this, AR_UPDATE_INTERVAL_MS);
                return;
            }
            try {
                Frame frame = arSession.update();

                String arStatus = frame.getCamera().getTrackingState().toString();
                updateStatusText("AR Status: " + arStatus);

                if (referenceArPose == null) {
                    arCoreUpdateHandler.postDelayed(this, AR_UPDATE_INTERVAL_MS);
                    return;
                }

                Pose currentArPose = frame.getCamera().getPose();

                Pose deltaPose = referenceArPose.inverse().compose(currentArPose);
                float[] translation = deltaPose.getTranslation();

                float mapDx = translation[0] * AR_TO_MAP_SCALE;
                float mapDz = translation[2] * AR_TO_MAP_SCALE;

                updateUserPosition(lastKnownMapX + mapDx, lastKnownMapY - mapDz); // Note: map Y is often inverted from AR Z

            } catch (CameraNotAvailableException e) {
                Log.e(TAG, "ARCore camera not available", e);
            } finally {
                arCoreUpdateHandler.postDelayed(this, AR_UPDATE_INTERVAL_MS);
            }
        }
    };

    private void initUserPositionView() {
        userPositionView = new ImageView(this);
        ((ImageView) userPositionView).setImageResource(R.drawable.ic_user_position);
        int size = 32; // dp
        final float scale = getResources().getDisplayMetrics().density;
        int sizeInPixels = (int) (size * scale + 0.5f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(sizeInPixels, sizeInPixels);
        userPositionView.setLayoutParams(params);
        binding.mapContainer.addView(userPositionView);
        userPositionView.setVisibility(View.GONE);
    }

    private void initLearningMarkerView() {
        learningMarkerView = new ImageView(this);
        learningMarkerView.setImageResource(R.drawable.ic_learning_marker);
        int size = 32; // dp
        final float scale = getResources().getDisplayMetrics().density;
        int sizeInPixels = (int) (size * scale + 0.5f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(sizeInPixels, sizeInPixels);
        learningMarkerView.setLayoutParams(params);
        binding.mapContainer.addView(learningMarkerView);
        learningMarkerView.setVisibility(View.GONE);
    }

    private void updateUserPosition(float x, float y) {
        userPositionView.setX(x - userPositionView.getWidth() / 2f);
        userPositionView.setY(y - userPositionView.getHeight() / 2f);
    }

    private void updateStatusText(String text) {
        runOnUiThread(() -> binding.statusTextView.setText(text));
    }

    private void saveFingerprintToDb(FingerprintRecord record) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues locationValues = new ContentValues();
            locationValues.put(DatabaseHelper.LocationEntry.COLUMN_NAME_POS_X, record.x);
            locationValues.put(DatabaseHelper.LocationEntry.COLUMN_NAME_POS_Y, record.y);
            long locationId = db.insert(DatabaseHelper.LocationEntry.TABLE_NAME, null, locationValues);

            for (Map.Entry<String, Integer> wifiEntry : record.wifiData.entrySet()) {
                ContentValues wifiValues = new ContentValues();
                wifiValues.put(DatabaseHelper.WifiReadingEntry.COLUMN_NAME_LOCATION_ID, locationId);
                wifiValues.put(DatabaseHelper.WifiReadingEntry.COLUMN_NAME_BSSID, wifiEntry.getKey());
                wifiValues.put(DatabaseHelper.WifiReadingEntry.COLUMN_NAME_RSSI, wifiEntry.getValue());
                db.insert(DatabaseHelper.WifiReadingEntry.TABLE_NAME, null, wifiValues);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void loadFingerprintsFromDb() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<FingerprintRecord> loadedFingerprints = new ArrayList<>();

        // Define a projection that specifies which columns from the database
        // you will actually use after this query.
        String[] locationProjection = {
                DatabaseHelper.LocationEntry._ID,
                DatabaseHelper.LocationEntry.COLUMN_NAME_POS_X,
                DatabaseHelper.LocationEntry.COLUMN_NAME_POS_Y
        };

        Cursor locationCursor = db.query(
                DatabaseHelper.LocationEntry.TABLE_NAME,
                locationProjection,
                null, null, null, null, null
        );

        while(locationCursor.moveToNext()) {
            long locationId = locationCursor.getLong(locationCursor.getColumnIndexOrThrow(DatabaseHelper.LocationEntry._ID));
            float posX = locationCursor.getFloat(locationCursor.getColumnIndexOrThrow(DatabaseHelper.LocationEntry.COLUMN_NAME_POS_X));
            float posY = locationCursor.getFloat(locationCursor.getColumnIndexOrThrow(DatabaseHelper.LocationEntry.COLUMN_NAME_POS_Y));

            Map<String, Integer> wifiData = new HashMap<>();

            String[] readingProjection = {
                DatabaseHelper.WifiReadingEntry.COLUMN_NAME_BSSID,
                DatabaseHelper.WifiReadingEntry.COLUMN_NAME_RSSI
            };
            String selection = DatabaseHelper.WifiReadingEntry.COLUMN_NAME_LOCATION_ID + " = ?";
            String[] selectionArgs = { String.valueOf(locationId) };

            Cursor readingCursor = db.query(
                DatabaseHelper.WifiReadingEntry.TABLE_NAME,
                readingProjection,
                selection,
                selectionArgs,
                null, null, null
            );

            while(readingCursor.moveToNext()) {
                String bssid = readingCursor.getString(readingCursor.getColumnIndexOrThrow(DatabaseHelper.WifiReadingEntry.COLUMN_NAME_BSSID));
                int rssi = readingCursor.getInt(readingCursor.getColumnIndexOrThrow(DatabaseHelper.WifiReadingEntry.COLUMN_NAME_RSSI));
                wifiData.put(bssid, rssi);
            }
            readingCursor.close();

            loadedFingerprints.add(new FingerprintRecord(posX, posY, wifiData));
        }
        locationCursor.close();

        this.fingerprints = loadedFingerprints;
        updateStatusText("Fingerprints loaded from DB: " + fingerprints.size());
    }

    private void checkAndRequestPermissions() {
        requestPermissionsLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA});
    }

    private boolean ensureArCoreInstalled() {
        try {
            ArCoreApk.InstallStatus installStatus = ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested);
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                arCoreInstallRequested = true;
                return false;
            }
        } catch (UnavailableUserDeclinedInstallationException | UnavailableDeviceNotCompatibleException e) {
            Log.e(TAG, "ARCore installation error", e);
            Toast.makeText(this, "This device does not support AR", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private final Runnable wifiPositioningRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isLearningMode) {
                startWifiScan();
                wifiPositioningHandler.postDelayed(this, WIFI_SCAN_INTERVAL_MS);
            }
        }
    };

    private static class DistanceRecord implements Comparable<DistanceRecord> {
        final FingerprintRecord record;
        final double distance;

        DistanceRecord(FingerprintRecord record, double distance) {
            this.record = record;
            this.distance = distance;
        }

        @Override
        public int compareTo(DistanceRecord other) {
            return Double.compare(this.distance, other.distance);
        }
    }
}
