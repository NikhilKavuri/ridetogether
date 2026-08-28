package com.nikhil.ridetogether.location

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.nikhil.ridetogether.MainActivity
import com.nikhil.ridetogether.Notifications
import com.nikhil.ridetogether.R
import com.nikhil.ridetogether.ServiceLocator
import com.nikhil.ridetogether.data.model.RiderLocation
import kotlinx.coroutines.launch

/**
 * Keeps publishing this rider's position while the app is backgrounded, the
 * screen is off, or the phone is in a tank bag.
 *
 * A foreground service rather than a background one because that is the only
 * thing Android will not kill mid-ride, and because the persistent notification
 * is honest: the app is using your GPS and other people can see where you are,
 * so that should be visible the whole time.
 *
 * The service is also where break alerts are received. Doing it here rather
 * than in the ride screen means a break request still buzzes your phone when
 * the screen is off, which is the only time it matters.
 */
class LocationService : LifecycleService() {

    companion object {
        private const val EXTRA_RIDE_CODE = "rideCode"

        fun start(context: Context, rideCode: String) {
            val intent = Intent(context, LocationService::class.java)
                .putExtra(EXTRA_RIDE_CODE, rideCode)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val repo by lazy { ServiceLocator.repository }
    private val throttle = LocationThrottle()

    private var rideCode: String? = null
    private var started = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val code = rideCode ?: return
            val now = System.currentTimeMillis()

            if (!throttle.shouldPublish(location.latitude, location.longitude, now)) return

            lifecycleScope.launch {
                val fix = RiderLocation(
                    uid = repo.currentUid().orEmpty(),
                    lat = location.latitude,
                    lng = location.longitude,
                    bearing = if (location.hasBearing()) location.bearing else 0f,
                    speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f,
                    accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
                    batteryPct = batteryPercent(),
                    updatedAt = now
                )

                // Only mark the fix as sent if the write actually landed, so a
                // dropped connection does not silently skip the next 25 seconds
                // of updates.
                runCatching { repo.publishLocation(code, fix) }
                    .onSuccess { throttle.recordPublished(fix.lat, fix.lng, now) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val code = intent?.getStringExtra(EXTRA_RIDE_CODE) ?: rideCode
        if (code == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        rideCode = code

        promoteToForeground(code)

        if (!started) {
            started = true
            beginLocationUpdates()
            watchForAlerts(code)
        }

        // START_REDELIVER_INTENT so a service restarted after a low-memory kill
        // still knows which ride it was publishing to.
        return START_REDELIVER_INTENT
    }

    private fun promoteToForeground(code: String) {
        val notification = buildTrackingNotification(code)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_TRACKING, notification, type)
    }

    private fun beginLocationUpdates() {
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(3_000L)
            // The provider itself filters small movements before waking us,
            // which is cheaper than filtering in our callback.
            .setMinUpdateDistanceMeters(8f)
            .setWaitForAccurateLocation(false)
            .build()

        // Permission can be revoked from Settings while the service runs.
        runCatching { fused.requestLocationUpdates(request, callback, mainLooper) }
            .onFailure { stopSelf() }
    }

    private fun watchForAlerts(code: String) {
        lifecycleScope.launch {
            repo.observeEvents(code).collect { event ->
                if (!event.isAlert()) return@collect
                if (event.uid == repo.currentUid()) return@collect
                if (repo.serverNow() - event.at > 120_000L) return@collect
                postAlert(event.headline())
            }
        }
    }

    private fun postAlert(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_ALERTS)
            .setContentTitle("Break request")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

        androidx.core.app.NotificationManagerCompat.from(this)
            .notify(Notifications.ID_ALERT, notification)
    }

    private fun buildTrackingNotification(code: String): Notification =
        NotificationCompat.Builder(this, Notifications.CHANNEL_TRACKING)
            .setContentTitle("Ride $code")
            .setContentText("Sharing your location with the group")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun batteryPercent(): Int =
        runCatching {
            getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }.getOrDefault(-1)

    override fun onDestroy() {
        runCatching { fused.removeLocationUpdates(callback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
