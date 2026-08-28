package com.nikhil.ridetogether.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry helpers. No Android types in here on purpose -- this is the
 * part of the app most worth unit testing, and it runs on the JVM in
 * milliseconds.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres between two WGS84 points. */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** Initial bearing in degrees (0..360) from point 1 to point 2. */
    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)

        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Human-readable distance. Deliberately coarse above a kilometre -- on a
     * moving bike, "12.4 km" is noise; "12 km" is the information.
     */
    fun formatDistance(meters: Double): String = when {
        meters.isNaN() -> "--"
        meters < 0 -> "--"
        meters < 950 -> "${(meters / 10).roundToInt() * 10} m"
        meters < 10_000 -> String.format("%.1f km", meters / 1000.0)
        else -> "${(meters / 1000.0).roundToInt()} km"
    }

    /** "just now" / "2 min ago" / "1 h ago" for a last-seen timestamp. */
    fun formatAge(ageMillis: Long): String = when {
        ageMillis < 0 -> "just now"
        ageMillis < 20_000 -> "just now"
        ageMillis < 60_000 -> "${ageMillis / 1000} s ago"
        ageMillis < 3_600_000 -> "${ageMillis / 60_000} min ago"
        else -> "${ageMillis / 3_600_000} h ago"
    }

    /** Compass point for a bearing, used in the "N-E of you" readout. */
    fun compassPoint(bearing: Double): String {
        val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalised = ((bearing % 360.0) + 360.0) % 360.0
        val index = ((normalised + 22.5) / 45.0).toInt() % 8
        return points[index]
    }

    /** True when a fix is too old or too imprecise to draw with confidence. */
    fun isStale(updatedAt: Long, now: Long, staleAfterMs: Long = 90_000): Boolean =
        abs(now - updatedAt) > staleAfterMs
}
