package com.example.indoorpositioning.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "fingerprints",
    primaryKeys = ["locationId", "bssid"],
    foreignKeys = [
        ForeignKey(
            entity = LocationPoint::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["locationId"])]
)
data class Fingerprint(
    val locationId: Long,
    val bssid: String,
    val rssi: Int
)
