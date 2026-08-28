package com.nikhil.ridetogether.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.nikhil.ridetogether.data.model.Characters
import com.nikhil.ridetogether.data.model.Destination
import com.nikhil.ridetogether.data.model.Ride
import com.nikhil.ridetogether.data.model.RideEvent
import com.nikhil.ridetogether.data.model.RideEventType
import com.nikhil.ridetogether.data.model.Rider
import com.nikhil.ridetogether.data.model.RiderLocation
import com.nikhil.ridetogether.util.RideCode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

/**
 * Firebase Realtime Database implementation of [RideRepository].
 *
 * Data layout, all under a single ride code so one listener subtree covers a
 * whole ride:
 *
 *   rides/{CODE}/meta      hostUid, createdAt, active, destination
 *   rides/{CODE}/members   {uid} -> name, characterId, joinedAt
 *   rides/{CODE}/live      {uid} -> lat, lng, bearing, speed, battery, updatedAt
 *   rides/{CODE}/breaks    {uid} -> true
 *   rides/{CODE}/events    push() -> type, uid, riderName, at, note
 *
 * Realtime Database rather than Firestore on purpose: this app's whole job is
 * pushing a small value many times a second-ish and fanning it out with minimal
 * latency, which is exactly RTDB's shape, at a fraction of the document-write
 * cost. It also stays inside the free Spark plan, so no card is needed here.
 */
class FirebaseRideRepository(
    private val db: FirebaseDatabase,
    private val auth: FirebaseAuth
) : RideRepository {

    companion object {
        /** One per character. Beyond this the map turns into soup anyway. */
        const val MAX_RIDERS = 8
        private const val CODE_ATTEMPTS = 5
    }

    private val rides: DatabaseReference get() = db.getReference("rides")

    @Volatile
    private var serverTimeOffsetMs: Long = 0L

    init {
        // Firebase publishes the delta between this device's clock and its own.
        db.getReference(".info/serverTimeOffset")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    serverTimeOffsetMs = (snapshot.getValue(Long::class.java) ?: 0L)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            })
    }

    override fun serverNow(): Long = System.currentTimeMillis() + serverTimeOffsetMs

    override suspend fun signIn(): String {
        auth.currentUser?.uid?.let { return it }
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous sign-in returned no user")
    }

    override fun currentUid(): String? = auth.currentUser?.uid

    override suspend fun createRide(displayName: String): Ride {
        val uid = signIn()

        // Codes are short enough that collisions are conceivable, so claim one
        // rather than assuming. Five attempts against a 729-million space is
        // more than anyone will ever need.
        repeat(CODE_ATTEMPTS) {
            val code = RideCode.generate()
            val metaRef = rides.child(code).child("meta")
            if (!metaRef.get().await().exists()) {
                val createdAt = System.currentTimeMillis()
                metaRef.setValue(
                    mapOf(
                        "hostUid" to uid,
                        "createdAt" to ServerValue.TIMESTAMP,
                        "active" to true
                    )
                ).await()

                addMember(code, uid, displayName, characterId = 0)
                postEvent(code, RideEventType.JOINED)

                return Ride(
                    code = code,
                    hostUid = uid,
                    destination = null,
                    createdAt = createdAt,
                    active = true
                )
            }
        }
        error("Could not allocate a ride code after $CODE_ATTEMPTS attempts")
    }

    override suspend fun joinRide(code: String, displayName: String): Ride {
        val normalised = RideCode.normalise(code)
        if (!RideCode.isValid(normalised)) throw RideNotFoundException(code)

        val uid = signIn()
        val rideRef = rides.child(normalised)

        val meta = rideRef.child("meta").get().await()
        val ride = Snapshots.ride(normalised, meta) ?: throw RideNotFoundException(normalised)
        if (!ride.active) throw RideNotFoundException(normalised)

        val members = rideRef.child("members").get().await()
        val existing = members.children.mapNotNull { Snapshots.rider(it) }

        // Re-joining after a crash or a reinstall must not consume a slot.
        val alreadyIn = existing.any { it.uid == uid }
        if (!alreadyIn && existing.size >= MAX_RIDERS) throw RideFullException()

        val characterId = existing.firstOrNull { it.uid == uid }?.characterId
            ?: Characters.firstFree(existing.map { it.characterId })

        addMember(normalised, uid, displayName, characterId)
        if (!alreadyIn) postEvent(normalised, RideEventType.JOINED)

        return ride
    }

    private suspend fun addMember(code: String, uid: String, name: String, characterId: Int) {
        rides.child(code).child("members").child(uid).setValue(
            mapOf(
                "name" to name.trim().ifBlank { "Rider" },
                "characterId" to characterId,
                "joinedAt" to ServerValue.TIMESTAMP
            )
        ).await()
    }

    override suspend fun leaveRide(code: String) {
        val uid = currentUid() ?: return
        postEvent(code, RideEventType.LEFT)
        val rideRef = rides.child(code)
        rideRef.child("live").child(uid).removeValue().await()
        rideRef.child("breaks").child(uid).removeValue().await()
        rideRef.child("members").child(uid).removeValue().await()
    }

    override suspend fun setDestination(code: String, destination: Destination) {
        rides.child(code).child("meta").updateChildren(
            mapOf(
                "destName" to destination.name,
                "destAddress" to destination.address,
                "destLat" to destination.lat,
                "destLng" to destination.lng
            )
        ).await()
        postEvent(code, RideEventType.DESTINATION_SET, destination.name)
    }

    override suspend fun publishLocation(code: String, location: RiderLocation) {
        val uid = currentUid() ?: return
        val ref = rides.child(code).child("live").child(uid)

        // Registered before the write, so a crash, a killed process or a dead
        // battery clears this rider from everyone else's map instead of
        // freezing their marker at the last known point forever.
        ref.onDisconnect().removeValue()

        ref.setValue(
            mapOf(
                "lat" to location.lat,
                "lng" to location.lng,
                "bearing" to location.bearing.toDouble(),
                "speedKmh" to location.speedKmh.toDouble(),
                "accuracyM" to location.accuracyM.toDouble(),
                "batteryPct" to location.batteryPct,
                "updatedAt" to ServerValue.TIMESTAMP
            )
        ).await()
    }

    override suspend fun postEvent(code: String, type: RideEventType, note: String?) {
        val uid = currentUid() ?: return
        val name = rides.child(code).child("members").child(uid).child("name")
            .get().await().getValue(String::class.java) ?: "A rider"

        rides.child(code).child("events").push().setValue(
            mapOf(
                "type" to type.name,
                "uid" to uid,
                "riderName" to name,
                "at" to ServerValue.TIMESTAMP,
                "note" to note
            )
        ).await()
    }

    override suspend fun setOnBreak(code: String, onBreak: Boolean) {
        val uid = currentUid() ?: return
        val ref = rides.child(code).child("breaks").child(uid)
        if (onBreak) {
            ref.onDisconnect().removeValue()
            ref.setValue(true).await()
        } else {
            ref.removeValue().await()
        }
    }

    override fun observeRide(code: String): Flow<Ride?> =
        rides.child(code).child("meta").valueFlow { Snapshots.ride(code, it) }

    override fun observeRiders(code: String): Flow<List<Rider>> =
        rides.child(code).child("members").valueFlow { snap ->
            snap.children.mapNotNull { Snapshots.rider(it) }.sortedBy { it.joinedAt }
        }

    override fun observeLocations(code: String): Flow<Map<String, RiderLocation>> =
        rides.child(code).child("live").valueFlow { snap ->
            snap.children.mapNotNull { Snapshots.location(it) }.associateBy { it.uid }
        }

    override fun observeBreaks(code: String): Flow<Set<String>> =
        rides.child(code).child("breaks").valueFlow { snap ->
            snap.children.mapNotNull { it.key }.toSet()
        }

    override fun observeEvents(code: String): Flow<RideEvent> = callbackFlow {
        val query = rides.child(code).child("events").limitToLast(15)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                Snapshots.event(snapshot)?.let { trySend(it) }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        query.addChildEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    override fun observeConnected(): Flow<Boolean> =
        db.getReference(".info/connected")
            .valueFlow { it.getValue(Boolean::class.java) ?: false }
            .distinctUntilChanged()

    /**
     * Bridges a Firebase value listener to a Flow, with the listener always
     * detached when the collector goes away. Every listener in this class goes
     * through here, so there is exactly one place a leak could hide.
     */
    private fun <T> DatabaseReference.valueFlow(map: (DataSnapshot) -> T): Flow<T> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // A malformed node must never take down the listener.
                runCatching { map(snapshot) }.onSuccess { trySend(it) }
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        addValueEventListener(listener)
        awaitClose { removeEventListener(listener) }
    }
}
