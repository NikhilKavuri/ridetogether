package com.nikhil.ridetogether.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.nikhil.ridetogether.ServiceLocator
import com.nikhil.ridetogether.data.model.Characters
import com.nikhil.ridetogether.data.model.RiderOnMap
import com.nikhil.ridetogether.ride.RideStateBuilder
import com.nikhil.ridetogether.ride.RideViewModel
import com.nikhil.ridetogether.search.SearchViewModel
import com.nikhil.ridetogether.util.Geo
import com.nikhil.ridetogether.util.RideCode
import kotlinx.coroutines.launch

object RideTags {
    const val CODE = "ride_code"
    const val BREAK_BUTTON = "ride_break_button"
    const val ALERT_BANNER = "ride_alert_banner"
    const val ROSTER = "ride_roster"
    const val DESTINATION = "ride_destination"
}

private val INDIA_CENTRE = LatLng(20.5937, 78.9629)

@Composable
fun RideScreen(
    code: String,
    locationGranted: Boolean,
    onRequestPermission: () -> Unit,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rideViewModel: RideViewModel = viewModel(
        key = "ride-$code",
        factory = viewModelFactory {
            initializer { RideViewModel(ServiceLocator.repository, code) }
        }
    )

    val state by rideViewModel.state.collectAsStateWithLifecycle()
    val alert by rideViewModel.alert.collectAsStateWithLifecycle()
    val actionError by rideViewModel.actionError.collectAsStateWithLifecycle()

    val me = state.riders.firstOrNull { it.isMe }
    val myLatLng = me?.location?.let { LatLng(it.lat, it.lng) }

    // The ViewModel factory runs once, so it must not capture myLatLng directly
    // -- it would capture the null from the first composition and never see a
    // fix, silently losing the distance ranking on search results.
    val originHolder = remember { mutableStateOf<LatLng?>(null) }
    SideEffect { originHolder.value = myLatLng }

    val searchViewModel: SearchViewModel = viewModel(
        key = "search-$code",
        factory = viewModelFactory {
            initializer { SearchViewModel(ServiceLocator.placesSearch) { originHolder.value } }
        }
    )

    var showSearch by rememberSaveable { mutableStateOf(false) }
    var hasCentred by rememberSaveable { mutableStateOf(false) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(INDIA_CENTRE, 4f)
    }

    // Snap to the rider once, on the first fix. After that the camera belongs
    // to whoever is holding the phone -- nothing yanks it back while they are
    // panning ahead to look at the road.
    LaunchedEffect(myLatLng) {
        if (!hasCentred && myLatLng != null) {
            hasCentred = true
            runCatching {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(myLatLng, 15f),
                    700
                )
            }
        }
    }

    if (showSearch) {
        SearchSheet(
            viewModel = searchViewModel,
            onPicked = { destination ->
                rideViewModel.setDestination(destination)
                showSearch = false
            },
            onDismiss = { showSearch = false }
        )
        return
    }

    Box(Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = locationGranted,
                // Traffic is genuinely useful leaving a city and costs nothing
                // extra, but it is a separate tile fetch, so it stays off by
                // default on the assumption of a patchy highway connection.
                isTrafficEnabled = false
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
                tiltGesturesEnabled = false,
                rotationGesturesEnabled = false
            )
        ) {
            state.riders.forEach { rider ->
                val location = rider.location ?: return@forEach
                key(rider.rider.uid) {
                    val position = LatLng(location.lat, location.lng)
                    val markerState = remember { MarkerState(position = position) }
                    LaunchedEffect(position) { markerState.position = position }

                    Marker(
                        state = markerState,
                        icon = MarkerIcons.forRider(
                            context = context,
                            character = Characters.byId(rider.rider.characterId),
                            label = if (rider.isMe) "You" else rider.rider.name,
                            stale = rider.isStale
                        ),
                        title = rider.rider.name,
                        snippet = rider.distanceMeters
                            ?.let { Geo.formatDistance(it) + " away" },
                        zIndex = if (rider.isMe) 1f else 0f
                    )
                }
            }

            state.ride?.destination?.let { destination ->
                key("destination") {
                    val position = LatLng(destination.lat, destination.lng)
                    val markerState = remember { MarkerState(position = position) }
                    LaunchedEffect(position) { markerState.position = position }
                    Marker(
                        state = markerState,
                        title = destination.name,
                        snippet = destination.address
                    )
                }
            }
        }

        TopBar(
            code = code,
            connected = state.connected,
            onShare = { shareInvite(context, code) },
            onLeave = onLeave,
            onFitAll = {
                scope.launch { fitAll(cameraPositionState, state.riders) }
            }
        )

        alert?.let { event ->
            AlertBanner(
                text = event.headline(),
                onDismiss = rideViewModel::dismissAlert,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp, start = 16.dp, end = 16.dp)
            )
        }

        BottomPanel(
            riders = state.riders,
            onBreakSet = state.onBreak,
            destinationLabel = state.ride?.destination?.name,
            // Derived from the collected state, not read off the ViewModel:
            // a plain getter is invisible to Compose, so the button label
            // would not flip when the break starts.
            amIOnBreak = me != null && state.onBreak.contains(me.rider.uid),
            locationGranted = locationGranted,
            actionError = actionError,
            onOpenSearch = { showSearch = true },
            onToggleBreak = {
                if (rideViewModel.amIOnBreak) rideViewModel.endBreak()
                else rideViewModel.requestBreak()
            },
            onRequestPermission = onRequestPermission,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun TopBar(
    code: String,
    connected: Boolean,
    onShare: () -> Unit,
    onLeave: () -> Unit,
    onFitAll: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                IconButton(onClick = onLeave) {
                    Icon(Icons.Filled.Close, contentDescription = "Leave ride")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(RideTags.CODE)
                    )
                    Text(
                        text = if (connected) "Live" else "Offline — reconnecting",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                IconButton(onClick = onFitAll) {
                    Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Fit all riders")
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share invite")
                }
            }
        }
    }
}

@Composable
private fun AlertBanner(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag(RideTags.ALERT_BANNER)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color.White)
            }
        }
    }
}

@Composable
private fun BottomPanel(
    riders: List<RiderOnMap>,
    onBreakSet: Set<String>,
    destinationLabel: String?,
    amIOnBreak: Boolean,
    locationGranted: Boolean,
    actionError: String?,
    onOpenSearch: () -> Unit,
    onToggleBreak: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {

            if (!locationGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRequestPermission)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Location permission needed",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Your friend cannot see you until you allow it. Tap to grant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSearch)
                    .testTag(RideTags.DESTINATION)
                    .padding(vertical = 6.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Destination",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = destinationLabel ?: "Tap to search",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (destinationLabel == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Column(Modifier.testTag(RideTags.ROSTER)) {
                riders.forEach { rider ->
                    RiderRow(rider = rider, onBreak = onBreakSet.contains(rider.rider.uid))
                }
            }

            RideStateBuilder.spreadMeters(riders)?.let { spread ->
                if (riders.size > 2) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Group spread ${Geo.formatDistance(spread)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (actionError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = actionError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(14.dp))

            if (amIOnBreak) {
                FilledTonalButton(
                    onClick = onToggleBreak,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag(RideTags.BREAK_BUTTON)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                    Text("Ready to ride", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(
                    onClick = onToggleBreak,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag(RideTags.BREAK_BUTTON)
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Tell everyone I need a break",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun RiderRow(rider: RiderOnMap, onBreak: Boolean) {
    val character = Characters.byId(rider.rider.characterId)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .background(Color(character.color), RoundedCornerShape(10.dp))
        ) {
            Text(character.glyph, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.size(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (rider.isMe) "${rider.rider.name} (you)" else rider.rider.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (onBreak) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "ON BREAK",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val subtitle = when {
                rider.location == null -> "Waiting for their location"
                rider.isMe -> "That's you"
                rider.distanceMeters == null -> "Waiting for your location"
                else -> buildString {
                    append(Geo.formatDistance(rider.distanceMeters))
                    rider.bearingFromMe?.let { append(" ${Geo.compassPoint(it)} of you") }
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (rider.isStale && !rider.isMe) {
            Text(
                text = "stale",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Zooms so every located rider is on screen. Falls back to a plain centre when
 * there is only one point, because LatLngBounds of a single coordinate produces
 * an infinite zoom.
 */
private suspend fun fitAll(
    cameraPositionState: CameraPositionState,
    riders: List<RiderOnMap>
) {
    val points = riders.mapNotNull { it.location }.map { LatLng(it.lat, it.lng) }
    if (points.isEmpty()) return

    if (points.size == 1) {
        runCatching {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(points.first(), 15f), 500)
        }
        return
    }

    val bounds = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
    runCatching {
        cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 140), 700)
    }
}

private fun shareInvite(context: android.content.Context, code: String) {
    val message = "Join my ride on RideTogether.\n\nCode: $code\n${RideCode.inviteLink(code)}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    context.startActivity(Intent.createChooser(intent, "Invite a rider"))
}
