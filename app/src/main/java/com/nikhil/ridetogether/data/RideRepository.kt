package com.nikhil.ridetogether.data

import com.nikhil.ridetogether.data.model.Destination
import com.nikhil.ridetogether.data.model.Ride
import com.nikhil.ridetogether.data.model.RideEvent
import com.nikhil.ridetogether.data.model.RideEventType
import com.nikhil.ridetogether.data.model.Rider
import com.nikhil.ridetogether.data.model.RiderLocation
import kotlinx.coroutines.flow.Flow

/**
 * Everything the app needs from the backend, expressed without a single
 * Firebase type. The UI and the ViewModels only ever see this interface, which
 * is what lets the entire ride flow be tested on a laptop with no emulator,
 * no network and no Firebase project (see FakeRideRepository).
 */
interface RideRepository {

    /** Anonymous sign-in. Idempotent; returns the stable uid for this install. */
    suspend fun signIn(): String

    /** uid of the signed-in user, or null before [signIn]. */
    fun currentUid(): String?

    /** Creates a ride with a fresh code and adds the caller as host. */
    suspend fun createRide(displayName: String): Ride

    /**
     * Adds the caller to an existing ride.
     * @throws RideNotFoundException if no active ride has that code.
     */
    suspend fun joinRide(code: String, displayName: String): Ride

    suspend fun leaveRide(code: String)

    suspend fun setDestination(code: String, destination: Destination)

    suspend fun publishLocation(code: String, location: RiderLocation)

    suspend fun postEvent(code: String, type: RideEventType, note: String? = null)

    /** Marks/unmarks the caller as stopped, so the flag survives an app restart. */
    suspend fun setOnBreak(code: String, onBreak: Boolean)

    fun observeRide(code: String): Flow<Ride?>

    fun observeRiders(code: String): Flow<List<Rider>>

    fun observeLocations(code: String): Flow<Map<String, RiderLocation>>

    fun observeBreaks(code: String): Flow<Set<String>>

    /** Emits events as they are added. Does not replay the whole history. */
    fun observeEvents(code: String): Flow<RideEvent>

    /** Firebase's own connection state, for the offline banner. */
    fun observeConnected(): Flow<Boolean>

    /**
     * Server-corrected wall clock.
     *
     * Two phones on a ride will not agree on the time -- a handset whose clock
     * is five minutes slow would otherwise have its marker permanently flagged
     * as "stale" on the other rider's screen. All freshness comparisons use
     * this instead of System.currentTimeMillis().
     */
    fun serverNow(): Long
}

class RideNotFoundException(val code: String) :
    Exception("No active ride with code $code")

class RideFullException :
    Exception("That ride already has the maximum number of riders")
