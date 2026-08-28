package com.nikhil.ridetogether.ride

import com.nikhil.ridetogether.data.model.Rider
import com.nikhil.ridetogether.data.model.RiderLocation
import com.nikhil.ridetogether.data.model.RiderOnMap
import com.nikhil.ridetogether.util.Geo

/**
 * Turns the four raw streams from the repository into the list the map and the
 * roster render.
 *
 * Kept as a pure function, deliberately: this is the logic most likely to be
 * subtly wrong (whose distance from whom, what counts as stale, what happens
 * when your own fix has not arrived yet) and it is worth being able to assert
 * on it directly, with no ViewModel, coroutines or Android runtime involved.
 */
object RideStateBuilder {

    /** A fix older than this is drawn faded, with a "last seen" time. */
    const val STALE_AFTER_MS = 90_000L

    fun build(
        myUid: String?,
        riders: List<Rider>,
        locations: Map<String, RiderLocation>,
        now: Long
    ): List<RiderOnMap> {
        val myLocation = myUid?.let { locations[it] }

        return riders
            .map { rider ->
                val location = locations[rider.uid]
                val isMe = rider.uid == myUid

                // Distance is always "from me to them". Before our own first
                // fix arrives there is no meaningful answer, so it stays null
                // and the UI shows a dash rather than a confident zero.
                val distance = when {
                    isMe -> null
                    myLocation == null || location == null -> null
                    else -> Geo.distanceMeters(
                        myLocation.lat, myLocation.lng,
                        location.lat, location.lng
                    )
                }

                val bearing = when {
                    isMe -> null
                    myLocation == null || location == null -> null
                    else -> Geo.bearingDegrees(
                        myLocation.lat, myLocation.lng,
                        location.lat, location.lng
                    )
                }

                RiderOnMap(
                    rider = rider,
                    location = location,
                    distanceMeters = distance,
                    bearingFromMe = bearing,
                    isMe = isMe,
                    isStale = location != null &&
                        Geo.isStale(location.updatedAt, now, STALE_AFTER_MS)
                )
            }
            // Me first, then nearest friend outward. On a two-person ride this
            // is trivially "me then them"; on a group run it puts whoever you
            // are about to lose at the bottom of the list where you look.
            .sortedWith(
                compareByDescending<RiderOnMap> { it.isMe }
                    .thenBy { it.distanceMeters ?: Double.MAX_VALUE }
                    .thenBy { it.rider.joinedAt }
            )
    }

    /** The furthest-apart pair, which is the number that matters when regrouping. */
    fun spreadMeters(riders: List<RiderOnMap>): Double? {
        val located = riders.mapNotNull { it.location }
        if (located.size < 2) return null

        var max = 0.0
        for (i in located.indices) {
            for (j in i + 1 until located.size) {
                val d = Geo.distanceMeters(
                    located[i].lat, located[i].lng,
                    located[j].lat, located[j].lng
                )
                if (d > max) max = d
            }
        }
        return max
    }
}
