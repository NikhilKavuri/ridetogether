package com.nikhil.ridetogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.ridetogether.data.RideFullException
import com.nikhil.ridetogether.data.RideNotFoundException
import com.nikhil.ridetogether.data.RideRepository
import com.nikhil.ridetogether.util.RideCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface AppScreen {
    data object Home : AppScreen
    data class InRide(val code: String) : AppScreen
}

data class HomeUiState(
    val name: String = "",
    val codeInput: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val screen: AppScreen = AppScreen.Home
) {
    val canCreate: Boolean get() = name.trim().length >= 2 && !busy
    val canJoin: Boolean get() = canCreate && RideCode.isValid(RideCode.normalise(codeInput))
}

/**
 * Owns the home screen and the create/join transitions.
 *
 * Navigation is a single [AppScreen] value rather than a navigation library.
 * With two destinations and one argument between them, Navigation Compose would
 * add a dependency, a route-string DSL and argument serialisation to replace
 * what a sealed interface does exactly.
 */
class MainViewModel(
    private val repo: RideRepository,
    private val prefs: RidePrefs
) : ViewModel() {

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }

    private val _ui = MutableStateFlow(HomeUiState(name = prefs.displayName))
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    fun updateName(value: String) {
        // Cap it: this string is rendered in a marker label on a moving map.
        _ui.update { it.copy(name = value.take(16), error = null) }
    }

    fun updateCode(value: String) {
        _ui.update { it.copy(codeInput = RideCode.normalise(value), error = null) }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun createRide() = run {
        val name = _ui.value.name.trim()
        if (name.length < 2) return@run
        launchGuarded {
            prefs.displayName = name
            val ride = repo.createRide(name)
            prefs.lastRideCode = ride.code
            _ui.update { it.copy(busy = false, screen = AppScreen.InRide(ride.code)) }
        }
    }

    fun joinRide() = run {
        val name = _ui.value.name.trim()
        val code = RideCode.normalise(_ui.value.codeInput)
        if (name.length < 2 || !RideCode.isValid(code)) return@run
        launchGuarded {
            prefs.displayName = name
            val ride = repo.joinRide(code, name)
            prefs.lastRideCode = ride.code
            _ui.update { it.copy(busy = false, screen = AppScreen.InRide(ride.code)) }
        }
    }

    /** Called when the app is opened from a ridetogether:// invite link. */
    fun applyInviteLink(link: String) {
        RideCode.codeFromLink(link)?.let { code ->
            _ui.update { it.copy(codeInput = code) }
        }
    }

    fun exitRide() {
        prefs.lastRideCode = null
        _ui.update { it.copy(screen = AppScreen.Home, codeInput = "", busy = false) }
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            // Firebase offline persistence queues writes and waits for a server
            // ack that never comes if the database is unreachable or was never
            // created -- so an await() here can hang forever with no error at
            // all. Every user-triggered call goes through this one function, so
            // one timeout covers create and join both.
            runCatching { withTimeout(TIMEOUT_MS) { block() } }.onFailure { e ->
                _ui.update { it.copy(busy = false, error = messageFor(e)) }
            }
        }
    }

    /**
     * Error text a rider can act on. "No ride with that code" tells you to
     * check the code; the exception's own message does not.
     */
    private fun messageFor(e: Throwable): String = when (e) {
        is TimeoutCancellationException ->
            "Could not reach the ride database. Check your connection — and in " +
                "Firebase, that Realtime Database exists and its rules are published."
        is RideNotFoundException ->
            "No active ride with code ${e.code}. Check the code and try again."
        is RideFullException ->
            "That ride is full."
        else ->
            e.message?.takeIf { it.isNotBlank() }
                ?: "Could not reach the ride. Check your connection."
    }
}
