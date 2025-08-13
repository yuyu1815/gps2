package com.example.indoorpositioning.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface FingerprintDao {

    @Transaction
    @Query("SELECT * FROM location_points")
    suspend fun getAll(): List<LocationPointWithFingerprints>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationPoint(locationPoint: LocationPoint): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFingerprints(fingerprints: List<Fingerprint>)

    @Transaction
    suspend fun insertLocationPointWithFingerprints(locationPoint: LocationPoint, fingerprints: List<Fingerprint>) {
        val locationId = insertLocationPoint(locationPoint)
        val taggedFingerprints = fingerprints.map { it.copy(locationId = locationId) }
        insertFingerprints(taggedFingerprints)
    }
}
