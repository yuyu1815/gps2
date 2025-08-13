package com.example.indoorpositioning

import android.net.wifi.ScanResult
import com.example.indoorpositioning.data.LocationPoint
import com.example.indoorpositioning.data.LocationPointWithFingerprints
import kotlin.math.pow
import kotlin.math.sqrt

object Positioning {

    private const val MISSING_RSSI = -100

    fun findBestMatch(
        liveScan: List<ScanResult>,
        storedFingerprints: List<LocationPointWithFingerprints>
    ): LocationPoint? {
        if (storedFingerprints.isEmpty()) return null

        var bestMatch: LocationPoint? = null
        var smallestDistance = Double.MAX_VALUE

        val liveScanMap = liveScan.associateBy { it.BSSID }

        for (storedPoint in storedFingerprints) {
            val storedScanMap = storedPoint.fingerprints.associateBy { it.bssid }

            // Find all BSSIDs present in either the live scan or the stored scan
            val allBssids = liveScanMap.keys + storedScanMap.keys

            if (allBssids.isEmpty()) continue

            var sumOfSquares = 0.0
            for (bssid in allBssids) {
                val liveRssi = liveScanMap[bssid]?.level ?: MISSING_RSSI
                val storedRssi = storedScanMap[bssid]?.rssi ?: MISSING_RSSI
                sumOfSquares += (liveRssi - storedRssi).toDouble().pow(2)
            }

            val distance = sqrt(sumOfSquares)

            if (distance < smallestDistance) {
                smallestDistance = distance
                bestMatch = storedPoint.locationPoint
            }
        }

        return bestMatch
    }
}
