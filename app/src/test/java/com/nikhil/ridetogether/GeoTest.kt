package com.nikhil.ridetogether

import com.nikhil.ridetogether.util.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    // Hyderabad -> Pune, checked against an independent haversine implementation.
    private val hydLat = 17.3850
    private val hydLng = 78.4867
    private val puneLat = 18.5204
    private val puneLng = 73.8567

    @Test
    fun `distance over hundreds of kilometres is accurate to a metre`() {
        val d = Geo.distanceMeters(hydLat, hydLng, puneLat, puneLng)
        assertEquals(505_754.8, d, 1.0)
    }

    @Test
    fun `distance over a few tens of metres is accurate`() {
        val d = Geo.distanceMeters(hydLat, hydLng, 17.3855, 78.4870)
        assertEquals(64.07, d, 0.1)
    }

    @Test
    fun `distance to the same point is zero`() {
        assertEquals(0.0, Geo.distanceMeters(hydLat, hydLng, hydLat, hydLng), 0.0001)
    }

    @Test
    fun `distance is symmetric`() {
        assertEquals(
            Geo.distanceMeters(hydLat, hydLng, puneLat, puneLng),
            Geo.distanceMeters(puneLat, puneLng, hydLat, hydLng),
            0.0001
        )
    }

    @Test
    fun `bearing points west-north-west from Hyderabad to Pune`() {
        assertEquals(285.16, Geo.bearingDegrees(hydLat, hydLng, puneLat, puneLng), 0.05)
    }

    @Test
    fun `bearing due north is zero and due east is ninety`() {
        assertEquals(0.0, Geo.bearingDegrees(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(90.0, Geo.bearingDegrees(0.0, 0.0, 0.0, 1.0), 0.01)
    }

    @Test
    fun `short distances round to ten metres and long ones drop the decimal`() {
        assertEquals("60 m", Geo.formatDistance(64.0))
        assertEquals("120 m", Geo.formatDistance(118.0))
        assertEquals("1.2 km", Geo.formatDistance(1240.0))
        assertEquals("506 km", Geo.formatDistance(505_754.8))
    }

    @Test
    fun `format handles the values a missing fix produces`() {
        assertEquals("--", Geo.formatDistance(Double.NaN))
        assertEquals("--", Geo.formatDistance(-1.0))
    }

    @Test
    fun `compass points snap to the nearest of eight`() {
        assertEquals("N", Geo.compassPoint(0.0))
        assertEquals("N", Geo.compassPoint(359.0))
        assertEquals("NE", Geo.compassPoint(45.0))
        assertEquals("W", Geo.compassPoint(270.0))
        assertEquals("NW", Geo.compassPoint(315.0))
        // Hyderabad -> Pune. 285 sits 15 degrees off due west and 30 off
        // north-west, so it snaps to W.
        assertEquals("W", Geo.compassPoint(285.16))
    }

    @Test
    fun `compass survives out-of-range bearings`() {
        assertEquals("N", Geo.compassPoint(720.0))
        assertEquals("W", Geo.compassPoint(-90.0))
    }

    @Test
    fun `staleness uses the configured window in both directions`() {
        val now = 1_000_000L
        assertFalse(Geo.isStale(now - 30_000, now))
        assertTrue(Geo.isStale(now - 120_000, now))
        // A phone whose clock runs fast must not read as permanently fresh.
        assertTrue(Geo.isStale(now + 120_000, now))
    }

    @Test
    fun `age formatting is readable at every scale`() {
        assertEquals("just now", Geo.formatAge(5_000))
        assertEquals("45 s ago", Geo.formatAge(45_000))
        assertEquals("3 min ago", Geo.formatAge(200_000))
        assertEquals("2 h ago", Geo.formatAge(7_500_000))
    }
}
