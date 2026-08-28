package com.nikhil.ridetogether

import com.nikhil.ridetogether.location.LocationThrottle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The throttle decides what the app costs to run, so its edges are worth
 * pinning down: too eager drains the battery and the Firebase quota, too lazy
 * and your friend's marker lags behind the road.
 */
class LocationThrottleTest {

    private val hydLat = 17.3850
    private val hydLng = 78.4867

    /** Roughly one metre of latitude. */
    private fun north(metres: Double) = hydLat + metres * 0.000008993

    @Test
    fun `the first fix always goes out`() {
        val throttle = LocationThrottle()
        assertTrue(throttle.shouldPublish(hydLat, hydLng, 1_000))
    }

    @Test
    fun `a second fix inside the minimum interval is dropped`() {
        val throttle = LocationThrottle(minIntervalMs = 4_000)
        throttle.recordPublished(hydLat, hydLng, 1_000)
        // A kilometre away, but only a second later: still too soon.
        assertFalse(throttle.shouldPublish(north(1_000.0), hydLng, 2_000))
    }

    @Test
    fun `standing still does not publish`() {
        val throttle = LocationThrottle(minIntervalMs = 4_000, maxIntervalMs = 25_000)
        throttle.recordPublished(hydLat, hydLng, 1_000)
        // Ten seconds later, three metres of GPS jitter. Nobody needs to know.
        assertFalse(throttle.shouldPublish(north(3.0), hydLng, 11_000))
    }

    @Test
    fun `real movement publishes`() {
        val throttle = LocationThrottle(minIntervalMs = 4_000, minDistanceMeters = 15.0)
        throttle.recordPublished(hydLat, hydLng, 1_000)
        assertTrue(throttle.shouldPublish(north(40.0), hydLng, 6_000))
    }

    @Test
    fun `a stationary rider still sends a heartbeat`() {
        val throttle = LocationThrottle(maxIntervalMs = 25_000)
        throttle.recordPublished(hydLat, hydLng, 1_000)
        // Parked at a chai stop. Without this the other rider's screen would
        // eventually mark them stale even though nothing is wrong.
        assertFalse(throttle.shouldPublish(hydLat, hydLng, 20_000))
        assertTrue(throttle.shouldPublish(hydLat, hydLng, 26_001))
    }

    @Test
    fun `a backwards clock does not wedge the throttle shut`() {
        val throttle = LocationThrottle()
        throttle.recordPublished(hydLat, hydLng, 1_000_000)
        // Network time correction moves the clock back. Without the guard the
        // rider would stop publishing until real time caught up.
        assertTrue(throttle.shouldPublish(hydLat, hydLng, 900_000))
    }

    @Test
    fun `reset returns it to the first-fix state`() {
        val throttle = LocationThrottle()
        throttle.recordPublished(hydLat, hydLng, 1_000)
        assertFalse(throttle.shouldPublish(hydLat, hydLng, 2_000))
        throttle.reset()
        assertTrue(throttle.shouldPublish(hydLat, hydLng, 2_000))
    }

    @Test
    fun `a highway hour settles at a sane write rate`() {
        val throttle = LocationThrottle()
        var published = 0
        var lat = hydLat

        // 80 km/h for one hour, with the fused provider offering a fix a second.
        // 80 km/h is about 22 m/s, so every second clears the 15 m threshold and
        // the 4 s minimum interval becomes the binding constraint.
        for (second in 1..3_600) {
            val now = second * 1_000L
            lat = north(22.0 * second)
            if (throttle.shouldPublish(lat, hydLng, now)) {
                throttle.recordPublished(lat, hydLng, now)
                published++
            }
        }

        // ~900 writes an hour. Firebase's free tier is nowhere near troubled.
        assertTrue("published $published", published in 850..950)
    }
}
