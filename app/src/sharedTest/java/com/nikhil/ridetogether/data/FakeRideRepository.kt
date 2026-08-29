package com.nikhil.ridetogether.data

import com.nikhil.ridetogether.data.model.Characters
import com.nikhil.ridetogether.data.model.Destination
import com.nikhil.ridetogether.data.model.Ride
import com.nikhil.ridetogether.data.model.RideEvent
import com.nikhil.ridetogether.data.model.RideEventType
import com.nikhil.ridetogether.data.model.Rider
import com.nikhil.ridetogether.data.model.RiderLocation
import com.nikhil.ridetogether.util.RideCode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

/**
 * A complete in-memory RideRepository.
 *
 * This is what makes "test the whole ride flow" a two-second JVM run instead of
 * an emulator, a Firebase project and two physical phones. Every test in the
 * suite that is not specifically about Firebase wiring runs against this,
 * including the multi-rider cases -- [simulateRider] lets a test add a second
 * rider and move them around, which is the scenario that is otherwise
 * impossible to exercise without a friend and a motorbike.
 */
class FakeRideRepository(
    private var uid: String = "me-uid",
    private var now: Long = 1_000_000L
) : RideRepository {

    private val ridesMeta = mutableMapOf<String, MutableStateFlow<Ride?>>()
    private val members = mutableMapOf<String, MutableStateFlow<List<Rider>>>()
    private val locations = mutableMapOf<String, MutableStateFlow<Map<String, RiderLocation>>>()
    private val breaks = mutableMapOf<String, MutableStateFlow<Set<String>>>()

    private val events = MutableSharedFlow<RideEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val connected = MutableStateFlow(true)
    private var eventCounter = 0
    private var signedIn = false

    /** Set by tests to force a specific failure. */
    var failNextJoin: Exception? = null

    /**
     * Makes the next join hang forever instead of failing, which is what
     * Firebase actually does when the database is unreachable.
     */
    var hangNextJoin: Boolean = false

    /** Every location published through the repository, for throttle assertions. */
    val publishedLocations = mutableListOf<RiderLocation>()

    // --- test controls -----------------------------------------------------

    fun advanceTime(millis: Long) {
        now += millis
    }

    fun setConnected(value: Boolean) {
        connected.value = value
    }

    /** Drops a second rider into the ride, as if they joined from another phone. */
    fun simulateRider(code: String, uid: String, name: String, characterId: Int = 1) {
        val list = members.getOrPut(code) { MutableStateFlow(emptyList()) }
        list.value = list.value + Rider(uid, name, characterId, now)
        emitEvent(code, RideEventType.JOINED, uid, name)
    }

    /** Moves a simulated rider, as if their phone published a fix. */
    fun simulateRiderAt(code: String, uid: String, lat: Double, lng: Double) {
        val map = locations.getOrPut(code) { MutableStateFlow(emptyMap()) }
        map.value = map.value + (uid to RiderLocation(uid, lat, lng, updatedAt = now))
    }

    /** [at] defaults to now; pass an older value to simulate a replayed event. */
    fun simulateRiderBreak(
        code: String,
        uid: String,
        name: String,
        onBreak: Boolean,
        at: Long = now
    ) {
        val set = breaks.getOrPut(code) { MutableStateFlow(emptySet()) }
        set.value = if (onBreak) set.value + uid else set.value - uid
        emitEvent(
            code,
            if (onBreak) RideEventType.BREAK_REQUESTED else RideEventType.BREAK_ENDED,
            uid,
            name,
            at
        )
    }

    /** Simulates the other rider's phone dying mid-ride. */
    fun simulateRiderDisconnect(code: String, uid: String) {
        val map = locations.getOrPut(code) { MutableStateFlow(emptyMap()) }
        map.value = map.value - uid
    }

    private fun emitEvent(
        code: String,
        type: RideEventType,
        uid: String,
        name: String,
        at: Long = now
    ) {
        events.tryEmit(
            RideEvent(
                id = "e${eventCounter++}",
                type = type,
                uid = uid,
                riderName = name,
                at = at
            )
        )
    }

    // --- RideRepository ----------------------------------------------------

    override suspend fun signIn(): String {
        signedIn = true
        return uid
    }

    override fun currentUid(): String? = if (signedIn) uid else null

    override suspend fun createRide(displayName: String): Ride {
        val code = RideCode.generate()
        val ride = Ride(code, uid, null, now, active = true)
        ridesMeta[code] = MutableStateFlow(ride)
        members[code] = MutableStateFlow(listOf(Rider(uid, displayName, 0, now)))
        locations[code] = MutableStateFlow(emptyMap())
        breaks[code] = MutableStateFlow(emptySet())
        signedIn = true
        emitEvent(code, RideEventType.JOINED, uid, displayName)
        return ride
    }

    override suspend fun joinRide(code: String, displayName: String): Ride {
        failNextJoin?.let { failNextJoin = null; throw it }
        if (hangNextJoin) {
            hangNextJoin = false
            awaitCancellation()
        }

        val normalised = RideCode.normalise(code)
        val ride = ridesMeta[normalised]?.value ?: throw RideNotFoundException(normalised)
        if (!ride.active) throw RideNotFoundException(normalised)

        val list = members.getOrPut(normalised) { MutableStateFlow(emptyList()) }
        if (list.value.none { it.uid == uid }) {
            if (list.value.size >= FirebaseRideRepository.MAX_RIDERS) throw RideFullException()
            val character = Characters.firstFree(list.value.map { it.characterId })
            list.value = list.value + Rider(uid, displayName, character, now)
            emitEvent(normalised, RideEventType.JOINED, uid, displayName)
        }
        signedIn = true
        return ride
    }

    override suspend fun leaveRide(code: String) {
        members[code]?.let { flow -> flow.value = flow.value.filterNot { it.uid == uid } }
        locations[code]?.let { it.value = it.value - uid }
        breaks[code]?.let { it.value = it.value - uid }
    }

    override suspend fun setDestination(code: String, destination: Destination) {
        ridesMeta[code]?.let { it.value = it.value?.copy(destination = destination) }
    }

    override suspend fun publishLocation(code: String, location: RiderLocation) {
        publishedLocations += location
        val map = locations.getOrPut(code) { MutableStateFlow(emptyMap()) }
        map.value = map.value + (uid to location.copy(uid = uid, updatedAt = now))
    }

    override suspend fun postEvent(code: String, type: RideEventType, note: String?) {
        val name = members[code]?.value?.firstOrNull { it.uid == uid }?.name ?: "Rider"
        emitEvent(code, type, uid, name)
    }

    override suspend fun setOnBreak(code: String, onBreak: Boolean) {
        val set = breaks.getOrPut(code) { MutableStateFlow(emptySet()) }
        set.value = if (onBreak) set.value + uid else set.value - uid
        postEvent(
            code,
            if (onBreak) RideEventType.BREAK_REQUESTED else RideEventType.BREAK_ENDED
        )
    }

    override fun observeRide(code: String): Flow<Ride?> =
        ridesMeta.getOrPut(code) { MutableStateFlow(null) }

    override fun observeRiders(code: String): Flow<List<Rider>> =
        members.getOrPut(code) { MutableStateFlow(emptyList()) }

    override fun observeLocations(code: String): Flow<Map<String, RiderLocation>> =
        locations.getOrPut(code) { MutableStateFlow(emptyMap()) }

    override fun observeBreaks(code: String): Flow<Set<String>> =
        breaks.getOrPut(code) { MutableStateFlow(emptySet()) }

    override fun observeEvents(code: String): Flow<RideEvent> = events.asSharedFlow()

    override fun observeConnected(): Flow<Boolean> = connected.map { it }

    override fun serverNow(): Long = now
}
