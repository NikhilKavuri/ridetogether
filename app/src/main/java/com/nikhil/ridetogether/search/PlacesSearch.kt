package com.nikhil.ridetogether.search

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.nikhil.ridetogether.data.model.Destination
import kotlinx.coroutines.tasks.await

data class PlaceSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String,
    val distanceMeters: Int?
)

/**
 * Destination search, backed by Google Places.
 *
 * Every Places SDK call in the app lives in this one file. That is on purpose:
 * it is the only place the app touches a third-party API surface that could
 * shift under it, so if Google renames something, exactly one file needs
 * changing and nothing else in the codebase knows the difference.
 *
 * Billing note: autocomplete keystrokes and the single follow-up details fetch
 * share one [AutocompleteSessionToken]. Google bills a token-grouped session as
 * one unit -- "Autocomplete Session Usage", which sits in the unlimited-free
 * Essentials tier -- instead of billing every keystroke separately. Calling
 * [newSession] after each pick is what keeps that true, and is the difference
 * between free and a per-keystroke charge.
 */
class PlacesSearch(context: Context) {

    private val client: PlacesClient = Places.createClient(context.applicationContext)

    @Volatile
    private var session: AutocompleteSessionToken = AutocompleteSessionToken.newInstance()

    /** Call after a suggestion is chosen, or the next search reuses a spent session. */
    fun newSession() {
        session = AutocompleteSessionToken.newInstance()
    }

    suspend fun suggest(query: String, near: LatLng?): List<PlaceSuggestion> {
        if (query.isBlank() || query.length < 2) return emptyList()

        val builder = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setSessionToken(session)
            .setCountries("IN")

        // Origin makes Places rank nearby matches first and hand back a
        // straight-line distance, which is what turns a list of six identically
        // named towns into an obvious choice.
        if (near != null) builder.setOrigin(near)

        val response = client.findAutocompletePredictions(builder.build()).await()

        return response.autocompletePredictions.map { prediction ->
            PlaceSuggestion(
                placeId = prediction.placeId,
                primaryText = prediction.getPrimaryText(null).toString(),
                secondaryText = prediction.getSecondaryText(null).toString(),
                distanceMeters = prediction.distanceMeters
            )
        }
    }

    /** Resolves a chosen suggestion to coordinates, closing the billing session. */
    suspend fun resolve(suggestion: PlaceSuggestion): Destination? {
        val fields = listOf(
            Place.Field.ID,
            Place.Field.DISPLAY_NAME,
            Place.Field.FORMATTED_ADDRESS,
            Place.Field.LOCATION
        )

        val request = FetchPlaceRequest.builder(suggestion.placeId, fields)
            .setSessionToken(session)
            .build()

        val place = client.fetchPlace(request).await().place
        val latLng = place.location ?: return null

        // Session is spent once details are fetched.
        newSession()

        return Destination(
            name = place.displayName ?: suggestion.primaryText,
            address = place.formattedAddress ?: suggestion.secondaryText,
            lat = latLng.latitude,
            lng = latLng.longitude
        )
    }
}
