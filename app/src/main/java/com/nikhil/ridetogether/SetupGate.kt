package com.nikhil.ridetogether

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * Fails loudly, early, and in plain language when the app is built without its
 * keys.
 *
 * Without this the two setup mistakes everybody makes produce symptoms that
 * look like app bugs rather than configuration problems: a missing Maps key
 * renders a blank grey rectangle with no error at all, and a placeholder
 * Firebase config makes every ride silently fail to sync. Both cost an evening
 * to diagnose. Checking up front turns each into a screen that names the file
 * to edit.
 */
object SetupGate {

    /** Matches the project_id in the committed placeholder google-services.json. */
    private const val PLACEHOLDER_PROJECT_ID = "ridetogether-placeholder"

    data class Problem(val title: String, val detail: String, val fix: String)

    fun check(context: Context): List<Problem> {
        val problems = mutableListOf<Problem>()

        if (BuildConfig.MAPS_API_KEY.isBlank()) {
            problems += Problem(
                title = "Google Maps key missing",
                detail = "The build had no MAPS_API_KEY, so the map will render blank.",
                fix = "Add MAPS_API_KEY=... to local.properties, or set it as a " +
                    "GitHub Actions secret, then rebuild."
            )
        }

        val projectId = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
        if (projectId.isNullOrBlank() || projectId == PLACEHOLDER_PROJECT_ID) {
            problems += Problem(
                title = "Firebase not configured",
                detail = "app/google-services.json is still the placeholder, so no " +
                    "ride can be created or joined.",
                fix = "Download google-services.json from your Firebase project and " +
                    "replace app/google-services.json, then rebuild."
            )
        }

        return problems
    }
}
