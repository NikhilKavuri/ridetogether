package com.nikhil.ridetogether

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.content.ContextCompat
import com.nikhil.ridetogether.location.LocationService
import com.nikhil.ridetogether.ui.HomeScreen
import com.nikhil.ridetogether.ui.RideScreen
import com.nikhil.ridetogether.ui.SetupScreen
import com.nikhil.ridetogether.ui.theme.RideTogetherTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer { MainViewModel(ServiceLocator.repository, ServiceLocator) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.dataString?.let { viewModel.applyInviteLink(it) }

        setContent {
            RideTogetherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RideTogetherApp(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { viewModel.applyInviteLink(it) }
    }
}

@Composable
private fun RideTogetherApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    // Configuration problems are checked once, before anything else renders.
    val problems = remember { SetupGate.check(context) }
    if (problems.isNotEmpty()) {
        SetupScreen(problems)
        return
    }

    var locationGranted by remember {
        mutableStateOf(hasLocationPermission(context))
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        locationGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    when (val screen = ui.screen) {
        is AppScreen.Home -> {
            // Leaving a ride must stop the service, or the notification and the
            // GPS stay on for the rest of the day.
            LaunchedEffect(Unit) { LocationService.stop(context) }

            HomeScreen(
                state = ui,
                onNameChange = viewModel::updateName,
                onCodeChange = viewModel::updateCode,
                onCreate = viewModel::createRide,
                onJoin = viewModel::joinRide,
                onDismissError = viewModel::dismissError
            )
        }

        is AppScreen.InRide -> {
            LaunchedEffect(screen.code, locationGranted) {
                if (locationGranted) {
                    LocationService.start(context, screen.code)
                } else {
                    permissionLauncher.launch(permissionsToRequest())
                }
            }

            RideScreen(
                code = screen.code,
                locationGranted = locationGranted,
                onRequestPermission = { permissionLauncher.launch(permissionsToRequest()) },
                onLeave = {
                    LocationService.stop(context)
                    viewModel.exitRide()
                }
            )
        }
    }
}

private fun permissionsToRequest(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    // Without this on Android 13+, a break request would buzz nobody.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun hasLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
