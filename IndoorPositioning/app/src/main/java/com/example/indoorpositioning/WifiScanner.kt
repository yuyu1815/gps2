package com.example.indoorpositioning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log

class WifiScanner(private val context: Context, private val onScanResults: (List<ScanResult>) -> Unit) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                scanSuccess()
            } else {
                scanFailure()
            }
        }
    }

    fun startScan() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, intentFilter)

        val success = wifiManager.startScan()
        if (!success) {
            // scan failure handling
            scanFailure()
        }
    }

    private fun scanSuccess() {
        try {
            val results = wifiManager.scanResults
            context.unregisterReceiver(wifiScanReceiver)
            onScanResults(results)
        } catch (e: SecurityException) {
            Log.e("WifiScanner", "Permission error on getting scan results", e)
            scanFailure()
        }
    }

    private fun scanFailure() {
        // handle failure: unregister receiver and return empty list
        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
        onScanResults(emptyList())
        Log.w("WifiScanner", "Wi-Fi scan failed.")
    }
}
