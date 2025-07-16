package uk.co.maybeitsadam.cycles.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
//            WearApp("Adam")
            Surface(modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)) {
                LocationPermissionHandler()
            }
        }
    }
}

@Composable
fun LocationPermissionHandler() {
    var hasLocationPermission by remember {
        mutableStateOf(false)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasLocationPermission = isGranted }
    )

    LaunchedEffect(key1 = true) {
        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    if (hasLocationPermission) {
        MainFeature()
    } else {
        PermissionDeniedScreen(
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        )
    }
}

@Composable
fun MainFeature() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Text("Main app here")
    }
}

@Composable
fun PermissionDeniedScreen (onRequestPermission: () -> Unit) {
    Column (
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Permission to view your location is required to use this app")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Enable Location")
        }
    }
}


