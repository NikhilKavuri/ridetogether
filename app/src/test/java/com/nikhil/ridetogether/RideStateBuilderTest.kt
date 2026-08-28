package com.nikhil.ridetogether

import com.nikhil.ridetogether.data.model.Rider
import com.nikhil.ridetogether.data.model.RiderLocation
import com.nikhil.ridetogether.ride.RideStateBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStateBuilderTest {

    private val now = 1_000_000L

    private val me = Rider("me", "Nikhil", 0, joinedAt = 10)
    private val friend = Rider("friend", "Arjun", 1, joinedAt = 20)
    private val third = Rider("third", "Sai", 2, joinedAt = 30)

    private fun at(uid: String, lat: Double, lng: Double, age: Long = 0) =
        RiderLocation(uid, lat, lng, updatedAt = now - age)

    @Test
    fun `distance is measured from me to each friend`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(me, friend),
            locations = mapOf(
                "me" to at("me", 17.3850, 78.4867),
                "friend" to at("friend", 17.3855, 78.4870)
            ),
            now = now
        )

        val arjun = riders.first { it.rider.uid == "friend" }
        assertEquals(64.07, arjun.distanceMeters!!, 0.1)
        assertNotNull(arjun.bearingFromMe)
    }

    @Test
    fun `my own row never shows a distance to myself`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(me, friend),
            locations = mapOf(
                "me" to at("me", 17.3850, 78.4867),
                "friend" to at("friend", 17.3855, 78.4870)
            ),
            now = now
        )

        val mine = riders.first { it.isMe }
        assertNull(mine.distanceMeters)
        assertNull(mine.bearingFromMe)
    }

    @Test
    fun `before my own fix arrives no distance is invented`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(me, friend),
            // Friend is on the map, I am not yet.
            locations = mapOf("friend" to at("friend", 17.3855, 78.4870)),
            now = now
        )

        // A confident "0 m" here would be a lie, and the worst kind: it reads
        // as "they are right next to you".
        assertNull(riders.first { it.rider.uid == "friend" }.distanceMeters)
    }

    @Test
    fun `a rider with no fix at all is still listed`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(me, friend),
            locations = mapOf("me" to at("me", 17.3850, 78.4867)),
            now = now
        )

        assertEquals(2, riders.size)
        assertNull(riders.first { it.rider.uid == "friend" }.location)
    }

    @Test
    fun `an old fix is flagged stale and a recent one is not`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(me, friend, third),
            locations = mapOf(
                "me" to at("me", 17.3850, 78.4867),
                "friend" to at("friend", 17.3855, 78.4870, age = 30_000),
                "third" to at("third", 17.3860, 78.4880, age = 200_000)
            ),
            now = now
        )

        assertFalse(riders.first { it.rider.uid == "friend" }.isStale)
        assertTrue(riders.first { it.rider.uid == "third" }.isStale)
    }

    @Test
    fun `I am first and the rest are ordered nearest outward`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(third, friend, me),
            locations = mapOf(
                "me" to at("me", 17.3850, 78.4867),
                // third is far, friend is close
                "third" to at("third", 17.5000, 78.6000),
                "friend" to at("friend", 17.3855, 78.4870)
            ),
            now = now
        )

        assertEquals(listOf("me", "friend", "third"), riders.map { it.rider.uid })
    }

    @Test
    fun `riders without a fix sort last rather than first`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(me, friend, third),
            locations = mapOf(
                "me" to at("me", 17.3850, 78.4867),
                "third" to at("third", 17.5000, 78.6000)
            ),
            now = now
        )

        assertEquals("friend", riders.last().rider.uid)
    }

    @Test
    fun `an unauthenticated build still renders everyone`() {
        val riders = RideStateBuilder.build(
            myUid = null,
            riders = listOf(me, friend),
            locations = mapOf("me" to at("me", 17.3850, 78.4867)),
            now = now
        )

        assertEquals(2, riders.size)
        assertTrue(riders.none { it.isMe })
    }

    @Test
    fun `group spread is the widest gap in the group`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(me, friend, third),
            locations = mapOf(
                "me" to at("me", 17.3850, 78.4867),
                "friend" to at("friend", 17.3855, 78.4870),
                "third" to at("third", 17.5000, 78.6000)
            ),
            now = now
        )

        val spread = RideStateBuilder.spreadMeters(riders)!!
        val meToThird = riders.first { it.rider.uid == "third" }.distanceMeters!!
        assertEquals(meToThird, spread, 1.0)
    }

    @Test
    fun `spread is undefined with fewer than two fixes`() {
        val riders = RideStateBuilder.build(
            myUid = "me",
            riders = listOf(me, friend),
            locations = mapOf("me" to at("me", 17.3850, 78.4867)),
            now = now
        )
        assertNull(RideStateBuilder.spreadMeters(riders))
    }

    @Test
    fun `an empty ride does not blow up`() {
        val riders = RideStateBuilder.build("me", emptyList(), emptyMap(), now)
        assertTrue(riders.isEmpty())
        assertNull(RideStateBuilder.spreadMeters(riders))
    }
}
