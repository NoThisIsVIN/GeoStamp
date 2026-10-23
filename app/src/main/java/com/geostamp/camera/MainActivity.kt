package com.geostamp.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.geostamp.camera.theme.GeoStampCameraTheme
import com.geostamp.camera.ui.camera.CameraViewModel
import com.geostamp.camera.ui.onboarding.OnboardingDialog
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val cameraViewModel: CameraViewModel by viewModels()

    /**
     * Set by the camera screen while it is composed. Volume keys act as a shutter only then;
     * everywhere else (gallery, settings) they keep adjusting the volume.
     */
    var volumeShutterEnabled = false

    // Permissions launcher
    private var hasCameraPermission by mutableStateOf(false)
    private var hasLocationPermission by mutableStateOf(false)
    private var isPermanentlyDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val camGranted = permissions[Manifest.permission.CAMERA] == true
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        hasCameraPermission = camGranted
        hasLocationPermission = fineGranted || coarseGranted

        if (hasCameraPermission && hasLocationPermission) {
            lifecycleScope.launch {
                cameraViewModel.settingsDataStore.setOnboardingCompleted()
            }
            cameraViewModel.onResume()
        } else {
            val camDeniedPermanent = !camGranted && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
            val locDeniedPermanent = (!fineGranted && !coarseGranted) &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
            if (camDeniedPermanent || locDeniedPermanent) {
                isPermanentlyDenied = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on while camera is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        checkPermissions()

        setContent {
            GeoStampCameraTheme {
                val appSettings by cameraViewModel.appSettings.collectAsStateWithLifecycle()
                val needsOnboarding = !appSettings.onboardingCompleted || !hasCameraPermission || !hasLocationPermission

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    AppNavigation(
                        cameraViewModel = cameraViewModel,
                        hasCameraPermission = hasCameraPermission
                    )

                    if (needsOnboarding) {
                        OnboardingDialog(
                            onGrantPermissionsClick = { requestAppPermissions() },
                            isPermanentlyDenied = isPermanentlyDenied
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        hasCameraPermission = cam
        hasLocationPermission = fine || coarse
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        if (hasCameraPermission && hasLocationPermission) {
            cameraViewModel.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        cameraViewModel.onPause()
    }

    // Hardware volume button shutter trigger
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeShutterEnabled && isVolumeKey(keyCode)) {
            // Holding the key auto-repeats; fire once per press, not a burst.
            if ((event?.repeatCount ?: 0) == 0) {
                cameraViewModel.requestShutter()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Consume the matching key-up too, otherwise the system may still show the volume panel.
        if (volumeShutterEnabled && isVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
}
