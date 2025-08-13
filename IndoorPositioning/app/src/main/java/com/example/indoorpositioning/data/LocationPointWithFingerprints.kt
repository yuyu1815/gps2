package com.example.indoorpositioning.data

import androidx.room.Embedded
import androidx.room.Relation

data class LocationPointWithFingerprints(
    @Embedded val locationPoint: LocationPoint,
    @Relation(
        parentColumn = "id",
        entityColumn = "locationId"
    )
    val fingerprints: List<Fingerprint>
)
