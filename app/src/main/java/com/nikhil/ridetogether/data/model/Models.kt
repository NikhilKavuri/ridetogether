package com.nikhil.ridetogether.data.model

/**
 * The whole domain model. Every one of these is a plain immutable data class
 * with no Firebase annotations, because nothing in this app is ever
 * deserialised reflectively -- see Snapshots.kt. That keeps R8 from being able
 * to break the app in release builds.
 */

data class Destination(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double
)

data class Ride(
    val code: String,
    val hostUid: String,
    val destination: Destination?,
    val createdAt: Long,
    val active: Boolean = true
)

data class Rider(
    val uid: String,
    val name: String,
    val characterId: Int,
    val joinedAt: Long
)

data class RiderLocation(
    val uid: String,
    val lat: Double,
    val lng: Double,
    val bearing: Float = 0f,
    val speedKmh: Float = 0f,
    val accuracyM: Float = 0f,
    val batteryPct: Int = -1,
    val updatedAt: Long = 0L
)

enum class RideEventType {
    JOINED,
    LEFT,
    BREAK_REQUESTED,
    BREAK_ENDED,
    DESTINATION_SET,
    UNKNOWN;

    companion object {
        /** Never throws: an unrecognised type from a newer client degrades to UNKNOWN. */
        fun from(raw: String?): RideEventType =
            values().firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
    }
}

data class RideEvent(
    val id: String,
    val type: RideEventType,
    val uid: String,
    val riderName: String,
    val at: Long,
    val note: String? = null
) {
    /** Copy shown in the banner and the notification. */
    fun headline(): String = when (type) {
        RideEventType.BREAK_REQUESTED -> "$riderName wants to take a break"
        RideEventType.BREAK_ENDED -> "$riderName is ready to ride again"
        RideEventType.JOINED -> "$riderName joined the ride"
        RideEventType.LEFT -> "$riderName left the ride"
        RideEventType.DESTINATION_SET -> "$riderName set the destination"
        RideEventType.UNKNOWN -> "Ride updated"
    }

    /** Only these interrupt the group with a notification. */
    fun isAlert(): Boolean =
        type == RideEventType.BREAK_REQUESTED || type == RideEventType.BREAK_ENDED
}

/**
 * One rider as the ride screen needs them: who they are, where they are, and
 * how far away. [distanceMeters] is null until we have our own fix.
 */
data class RiderOnMap(
    val rider: Rider,
    val location: RiderLocation?,
    val distanceMeters: Double?,
    val bearingFromMe: Double?,
    val isMe: Boolean,
    val isStale: Boolean
)

/** Everything the ride screen renders, in one snapshot. */
data class RideState(
    val ride: Ride? = null,
    val riders: List<RiderOnMap> = emptyList(),
    val latestAlert: RideEvent? = null,
    val onBreak: Set<String> = emptySet(),
    val connected: Boolean = true,
    val error: String? = null
)
