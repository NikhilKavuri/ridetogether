package com.nikhil.ridetogether

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.google.android.libraries.places.api.Places
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.nikhil.ridetogether.data.FirebaseRideRepository
import com.nikhil.ridetogether.data.RideRepository
import com.nikhil.ridetogether.search.PlacesSearch

class RideApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        Notifications.createChannels(this)
    }
}

/**
 * Manual dependency wiring.
 *
 * A dependency-injection framework would earn its keep on a codebase with many
 * modules and many implementations; here there are three singletons and one
 * interface with two implementations. Hilt would add a Kotlin annotation
 * processor, roughly twenty seconds to every build, and a class of
 * generated-code errors, in exchange for saving these fifteen lines.
 */
/**
 * The two pieces of state that outlive a process. An interface so the
 * ViewModel tests can run without SharedPreferences or a Context.
 */
interface RidePrefs {
    var displayName: String
    var lastRideCode: String?
}

object ServiceLocator : RidePrefs {

    private lateinit var appContext: Context

    @Volatile
    private var repositoryOverride: RideRepository? = null

    val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("ridetogether", Context.MODE_PRIVATE)
    }

    val repository: RideRepository by lazy {
        repositoryOverride ?: FirebaseRideRepository(
            db = FirebaseDatabase.getInstance(),
            auth = FirebaseAuth.getInstance()
        )
    }

    val placesSearch: PlacesSearch by lazy { PlacesSearch(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext

        // Cached rides and queued writes survive a tunnel, a dead zone, or the
        // app being killed. Must happen before anything else touches the
        // database, hence the guard -- calling it twice throws.
        runCatching { FirebaseDatabase.getInstance().setPersistenceEnabled(true) }

        val key = BuildConfig.MAPS_API_KEY
        if (key.isNotBlank() && !Places.isInitialized()) {
            runCatching { Places.initializeWithNewPlacesApiEnabled(appContext, key) }
        }
    }

    /** Used by instrumented tests to swap in FakeRideRepository. */
    fun setRepositoryForTest(repository: RideRepository?) {
        repositoryOverride = repository
    }

    // --- small persisted bits of state ------------------------------------

    override var displayName: String
        get() = prefs.getString("displayName", "").orEmpty()
        set(value) = prefs.edit().putString("displayName", value.trim()).apply()

    override var lastRideCode: String?
        get() = prefs.getString("lastRideCode", null)
        set(value) = prefs.edit().putString("lastRideCode", value).apply()
}

object Notifications {

    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_ALERTS = "alerts"

    const val ID_TRACKING = 1001
    const val ID_ALERT = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // Silent and unobtrusive: this one is up for the whole ride.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                "Ride in progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while your location is being shared with your ride"
                setShowBadge(false)
            }
        )

        // Loud on purpose: this is someone saying they need to stop, and it has
        // to get through a jacket pocket at 80 km/h.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Break requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "When another rider asks the group to stop"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
        )
    }
}
