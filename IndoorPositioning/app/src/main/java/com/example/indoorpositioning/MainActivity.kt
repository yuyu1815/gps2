package com.example.indoorpositioning

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.ScanResult
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.indoorpositioning.data.AppDatabase
import com.example.indoorpositioning.data.Fingerprint
import com.example.indoorpositioning.data.FingerprintDao
import com.example.indoorpositioning.data.LocationPoint
import com.example.indoorpositioning.view.PathView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var pdrTracker: PDRTracker
    private lateinit var wifiScanner: WifiScanner
    private lateinit var statusTextView: TextView
    private lateinit var modeSwitch: SwitchCompat
    private lateinit var mapImageView: ImageView
    private lateinit var markerImageView: ImageView
    private lateinit var pathView: PathView

    private lateinit var db: AppDatabase
    private lateinit var fingerprintDao: FingerprintDao

    private var isLearnMode = false
    private var currentAbsolutePosition: LocationPoint? = null

    private val positioningHandler = Handler(Looper.getMainLooper())
    private lateinit var positioningRunnable: Runnable
    private val POSITIONING_INTERVAL_MS = 5000L

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "Location permission is required for Wi-Fi scanning.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        modeSwitch = findViewById(R.id.modeSwitch)
        mapImageView = findViewById(R.id.mapImageView)
        markerImageView = findViewById(R.id.markerImageView)
        pathView = findViewById(R.id.pathView)

        db = AppDatabase.getDatabase(this)
        fingerprintDao = db.fingerprintDao()

        setupPDRTracker()
        setupWifiScanner()
        setupUI()
        checkPermission()
    }

    override fun onPause() {
        super.onPause()
        stopPositioning()
        pdrTracker.stop()
    }

    override fun onResume() {
        super.onResume()
        pdrTracker.start()
        if (!isLearnMode) {
            startPositioning()
        }
    }

    private fun setupPDRTracker() {
        pdrTracker = PDRTracker(this) { x, y ->
            if (isLearnMode) return@PDRTracker

            val newPosition = LocationPoint(id = currentAbsolutePosition?.id ?: 0, x = x, y = y)
            currentAbsolutePosition = newPosition

            statusTextView.text = "PDR: (x=${"%.2f".format(x)}, y=${"%.2f".format(y)})"
            drawMarker(newPosition)
            pathView.addPoint(newPosition.x, newPosition.y)
        }
    }

    private fun setupWifiScanner() {
        wifiScanner = WifiScanner(this) { results ->
            if (results.isEmpty()) {
                statusTextView.text = "Status: Scan failed"
                return@WifiScanner
            }
            if (!isLearnMode) {
                statusTextView.text = "Status: Locating..."
                locatePosition(results)
            }
        }
    }

    private fun locatePosition(liveScan: List<ScanResult>) {
        lifecycleScope.launch {
            val allFingerprints = fingerprintDao.getAll()
            if (allFingerprints.isEmpty()) {
                statusTextView.text = "Status: No learned data."
                return@launch
            }

            val bestMatch = withContext(Dispatchers.Default) {
                Positioning.findBestMatch(liveScan, allFingerprints)
            }

            if (bestMatch != null) {
                statusTextView.text = "Status: Wi-Fi Position Found!"
                currentAbsolutePosition = bestMatch

                // Re-anchor the PDR system
                pdrTracker.setOrigin(bestMatch.x, bestMatch.y)

                // Redraw path and marker
                pathView.clearPath()
                pathView.addPoint(bestMatch.x, bestMatch.y)
                drawMarker(bestMatch)
            } else {
                statusTextView.text = "Status: Could not determine position."
                markerImageView.setImageDrawable(null)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isLearnMode = isChecked
            modeSwitch.text = if (isLearnMode) "Mode: Learn" else "Mode: Locate"
            pathView.clearPath()
            currentAbsolutePosition = null
            markerImageView.setImageDrawable(null)

            if (isLearnMode) {
                stopPositioning()
                statusTextView.text = "Status: Tap map to learn."
            } else {
                startPositioning()
            }
        }

        mapImageView.setOnTouchListener { _, event ->
            if (isLearnMode && event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                statusTextView.text = "Status: Learning at (${"%.1f".format(x)}, ${"%.1f".format(y)})..."
                WifiScanner(this) { results ->
                    if (results.isNotEmpty()) saveFingerprint(x, y, results)
                    else statusTextView.text = "Status: Learn failed. No Wi-Fi."
                }.startScan()
                true
            } else false
        }

        positioningRunnable = Runnable {
            if (!isLearnMode) {
                statusTextView.text = "Status: Scanning for position..."
                wifiScanner.startScan()
                positioningHandler.postDelayed(positioningRunnable, POSITIONING_INTERVAL_MS)
            }
        }
    }

    private fun startPositioning() {
        pathView.clearPath()
        positioningHandler.removeCallbacks(positioningRunnable)
        positioningHandler.post(positioningRunnable)
    }

    private fun stopPositioning() {
        positioningHandler.removeCallbacks(positioningRunnable)
    }

    private fun saveFingerprint(x: Float, y: Float, results: List<ScanResult>) {
        lifecycleScope.launch {
            val locationPoint = LocationPoint(x = x, y = y)
            val fingerprints = results.map { Fingerprint(locationId = 0, bssid = it.BSSID, rssi = it.level) }
            fingerprintDao.insertLocationPointWithFingerprints(locationPoint, fingerprints)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Saved fingerprint at (${"%.1f".format(x)}, ${"%.1f".format(y)})", Toast.LENGTH_SHORT).show()
                statusTextView.text = "Status: Tap map to learn."
            }
        }
    }

    private fun drawMarker(point: LocationPoint) {
        val markerSize = 20f
        val bmp = Bitmap.createBitmap(markerSize.toInt() * 2, markerSize.toInt() * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(markerSize, markerSize, markerSize, paint)
        markerImageView.setImageBitmap(bmp)
        markerImageView.x = point.x - markerSize
        markerImageView.y = point.y - markerSize
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
