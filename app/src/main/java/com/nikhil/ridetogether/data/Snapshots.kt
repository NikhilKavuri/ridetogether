package com.nikhil.ridetogether.data

import com.google.firebase.database.DataSnapshot
import com.nikhil.ridetogether.data.model.Destination
import com.nikhil.ridetogether.data.model.Ride
import com.nikhil.ridetogether.data.model.RideEvent
import com.nikhil.ridetogether.data.model.RideEventType
import com.nikhil.ridetogether.data.model.Rider
import com.nikhil.ridetogether.data.model.RiderLocation

/**
 * Explicit, total snapshot readers.
 *
 * Firebase can map a snapshot onto a data class for you via reflection. This
 * app does not use that, for two reasons that both bite in production:
 *
 *  1. R8 renames fields in release builds, so reflective mapping silently
 *     yields nulls in the APK you ship while working perfectly in debug.
 *  2. A partially-written node (a client killed mid-write) throws inside the
 *     mapper and takes down the whole listener.
 *
 * Reading field by field with typed defaults costs about forty lines and makes
 * both failure modes impossible. A malformed node degrades to "not there"
 * rather than to a crash.
 */
internal object Snapshots {

    private fun DataSnapshot.str(key: String): String? =
        child(key).getValue(String::class.java)

    private fun DataSnapshot.dbl(key: String): Double? =
        when (val v = child(key).value) {
            is Double -> v
            is Long -> v.toDouble()
            is Int -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }

    private fun DataSnapshot.lng(key: String): Long? =
        when (val v = child(key).value) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }

    private fun DataSnapshot.bool(key: String): Boolean? =
        when (val v = child(key).value) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
            is Long -> v != 0L
            else -> null
        }

    fun ride(code: String, meta: DataSnapshot): Ride? {
        if (!meta.exists()) return null
        val hostUid = meta.str("hostUid") ?: return null

        val destLat = meta.dbl("destLat")
        val destLng = meta.dbl("destLng")
        val destination = if (destLat != null && destLng != null) {
            Destination(
                name = meta.str("destName").orEmpty(),
                address = meta.str("destAddress").orEmpty(),
                lat = destLat,
                lng = destLng
            )
        } else {
            null
        }

        return Ride(
            code = code,
            hostUid = hostUid,
            destination = destination,
            createdAt = meta.lng("createdAt") ?: 0L,
            active = meta.bool("active") ?: true
        )
    }

    fun rider(node: DataSnapshot): Rider? {
        val uid = node.key ?: return null
        val name = node.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return Rider(
            uid = uid,
            name = name,
            characterId = (node.lng("characterId") ?: 0L).toInt(),
            joinedAt = node.lng("joinedAt") ?: 0L
        )
    }

    fun location(node: DataSnapshot): RiderLocation? {
        val uid = node.key ?: return null
        // A fix without coordinates is not a fix.
        val lat = node.dbl("lat") ?: return null
        val lng = node.dbl("lng") ?: return null
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return null

        return RiderLocation(
            uid = uid,
            lat = lat,
            lng = lng,
            bearing = (node.dbl("bearing") ?: 0.0).toFloat(),
            speedKmh = (node.dbl("speedKmh") ?: 0.0).toFloat(),
            accuracyM = (node.dbl("accuracyM") ?: 0.0).toFloat(),
            batteryPct = (node.lng("batteryPct") ?: -1L).toInt(),
            updatedAt = node.lng("updatedAt") ?: 0L
        )
    }

    fun event(node: DataSnapshot): RideEvent? {
        val id = node.key ?: return null
        return RideEvent(
            id = id,
            type = RideEventType.from(node.str("type")),
            uid = node.str("uid").orEmpty(),
            riderName = node.str("riderName").orEmpty().ifBlank { "A rider" },
            at = node.lng("at") ?: 0L,
            note = node.str("note")
        )
    }
}
