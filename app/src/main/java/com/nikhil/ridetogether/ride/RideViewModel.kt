package com.nikhil.ridetogether.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.ridetogether.data.RideRepository
import com.nikhil.ridetogether.data.model.Destination
import com.nikhil.ridetogether.data.model.RideEvent
import com.nikhil.ridetogether.data.model.RideEventType
import com.nikhil.ridetogether.data.model.RideState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class RideViewModel(
    private val repo: RideRepository,
    val code: String
) : ViewModel() {

    /**
     * An event older than this is history, not news. Guards against the
     * last-15-events listener replaying a stale "taking a break" banner at
     * someone who has just opened the app.
     */
    private val alertFreshnessMs = 120_000L

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }

    private val seenEventIds = LinkedHashSet<String>()

    private val _alert = MutableStateFlow<RideEvent?>(null)
    val alert: StateFlow<RideEvent?> = _alert.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    val state: StateFlow<RideState> = combine(
        repo.observeRide(code),
        repo.observeRiders(code),
        repo.observeLocations(code),
        repo.observeBreaks(code),
        repo.observeConnected()
    ) { ride, riders, locations, breaks, connected ->
        RideState(
            ride = ride,
            riders = RideStateBuilder.build(
                myUid = repo.currentUid(),
                riders = riders,
                locations = locations,
                now = repo.serverNow()
            ),
            onBreak = breaks,
            connected = connected
        )
    }.stateIn(
        scope = viewModelScope,
        // Keeps listeners alive briefly across a rotation or a quick trip to
        // the notification shade, without holding Firebase open when the app
        // is genuinely backgrounded -- the foreground service does that.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RideState()
    )

    val amIOnBreak: Boolean
        get() = repo.currentUid()?.let { state.value.onBreak.contains(it) } ?: false

    init {
        viewModelScope.launch {
            repo.observeEvents(code).collect { event -> onEvent(event) }
        }
    }

    private fun onEvent(event: RideEvent) {
        // Dedupe: the listener re-delivers on reconnect.
        if (!seenEventIds.add(event.id)) return
        if (seenEventIds.size > 200) {
            seenEventIds.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
        }

        if (!event.isAlert()) return
        if (event.uid == repo.currentUid()) return
        if (repo.serverNow() - event.at > alertFreshnessMs) return

        _alert.value = event
    }

    fun dismissAlert() {
        _alert.value = null
    }

    fun clearActionError() {
        _actionError.value = null
    }

    fun requestBreak(note: String? = null) = runAction {
        repo.setOnBreak(code, true)
        if (note != null) repo.postEvent(code, RideEventType.BREAK_REQUESTED, note)
    }

    fun endBreak() = runAction {
        repo.setOnBreak(code, false)
    }

    fun setDestination(destination: Destination) = runAction {
        repo.setDestination(code, destination)
    }

    fun leave() = runAction {
        repo.leaveRide(code)
    }

    /**
     * Every user-triggered write goes through here. A failed write surfaces as
     * a message instead of an unhandled coroutine exception -- on a ride, a
     * silent failure to broadcast "I need to stop" is the worst outcome in the
     * app, so it is never allowed to fail quietly.
     */
    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            // Same reason as MainViewModel: an unreachable database makes an
            // awaited write hang rather than fail. Silently hanging on "I need
            // a break" is the worst failure this app has, so it gets a deadline.
            runCatching { withTimeout(TIMEOUT_MS) { block() } }.onFailure {
                _actionError.value = when (it) {
                    is TimeoutCancellationException ->
                        "Could not reach the group. Your message may not have been sent."
                    else -> it.message ?: "Something went wrong. Try again."
                }
            }
        }
    }
}
