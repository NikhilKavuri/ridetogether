package com.nikhil.ridetogether.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.nikhil.ridetogether.data.model.Destination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val places: PlacesSearch,
    private val origin: () -> LatLng?
) : ViewModel() {

    private val query = MutableStateFlow("")

    private val _searching = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    /**
     * 280 ms of quiet before a request goes out.
     *
     * Typing "Lonavala" is eight keystrokes. Without debouncing that is eight
     * network round trips, eight sets of results racing each other into the
     * list, and eight times the quota burn. mapLatest then cancels any request
     * still in flight when the next keystroke lands, so the results on screen
     * always belong to what is currently in the box.
     */
    val suggestions: StateFlow<List<PlaceSuggestion>> = query
        .debounce(280)
        .distinctUntilChanged()
        .onEach { _searching.value = it.length >= 2 }
        .mapLatest { text ->
            if (text.length < 2) {
                emptyList()
            } else {
                runCatching { places.suggest(text, origin()) }
                    .onFailure { _error.value = "Search unavailable. Check your connection." }
                    .getOrDefault(emptyList())
            }
        }
        .onEach { _searching.value = false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val currentQuery: StateFlow<String> = query.asStateFlow()

    fun updateQuery(value: String) {
        _error.value = null
        query.value = value
    }

    fun clear() {
        query.value = ""
        _error.value = null
    }

    /** Resolves a suggestion to coordinates and hands it back via [onResolved]. */
    fun choose(suggestion: PlaceSuggestion, onResolved: (Destination) -> Unit) {
        viewModelScope.launch {
            runCatching { places.resolve(suggestion) }
                .onSuccess { destination ->
                    if (destination == null) {
                        _error.value = "Could not get coordinates for that place."
                    } else {
                        onResolved(destination)
                        clear()
                    }
                }
                .onFailure { _error.value = "Could not get coordinates for that place." }
        }
    }
}
