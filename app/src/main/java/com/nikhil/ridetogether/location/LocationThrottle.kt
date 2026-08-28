package com.nikhil.ridetogether.location

import com.nikhil.ridetogether.util.Geo

/**
 * Decides which GPS fixes are worth writing to Firebase.
 *
 * This one class is why the app is cheap to run. The fused provider will happily
 * hand us a fix every second; publishing all of them would burn battery, mobile
 * data and Firebase quota for no visible benefit, since nobody can perceive a
 * friend's marker moving 3 metres. The rules:
 *
 *  - never publish more often than [minIntervalMs]
 *  - between that and [maxIntervalMs], publish only after real movement
 *  - always publish at least every [maxIntervalMs] so the other rider's
 *    "last seen" stays fresh even when you are parked at a chai stop
 *
 * On a highway run this settles at roughly one write every 4-5 seconds; stopped,
 * it drops to one every 25 seconds.
 */
class LocationThrottle(
    private val minIntervalMs: Long = 4_000,
    private val minDistanceMeters: Double = 15.0,
    private val maxIntervalMs: Long = 25_000
) {

    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastPublishedAt: Long = 0L

    fun shouldPublish(lat: Double, lng: Double, now: Long): Boolean {
        val prevLat = lastLat
        val prevLng = lastLng

        // First fix always goes out -- the other rider is staring at an empty map.
        if (prevLat == null || prevLng == null) return true

        val sinceLast = now - lastPublishedAt

        // Clock went backwards (NTP correction, timezone change). Treat as due.
        if (sinceLast < 0) return true

        if (sinceLast >= maxIntervalMs) return true
        if (sinceLast < minIntervalMs) return false

        return Geo.distanceMeters(prevLat, prevLng, lat, lng) >= minDistanceMeters
    }

    /** Call only after the write actually succeeded. */
    fun recordPublished(lat: Double, lng: Double, now: Long) {
        lastLat = lat
        lastLng = lng
        lastPublishedAt = now
    }

    fun reset() {
        lastLat = null
        lastLng = null
        lastPublishedAt = 0L
    }
}
