package com.geostamp.camera

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.geostamp.camera.model.PhotoItem
import com.geostamp.camera.ui.camera.CameraScreen
import com.geostamp.camera.ui.camera.CameraViewModel
import com.geostamp.camera.ui.gallery.GalleryScreen
import com.geostamp.camera.ui.gallery.GalleryViewModel
import com.geostamp.camera.ui.gallery.PhotoViewerScreen
import com.geostamp.camera.ui.privacy.PrivacyDialog
import com.geostamp.camera.ui.settings.SettingsScreen
import com.geostamp.camera.ui.settings.SettingsViewModel

object Screen {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val VIEWER = "viewer"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation(
    cameraViewModel: CameraViewModel,
    hasCameraPermission: Boolean = true,
    galleryViewModel: GalleryViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    navController: NavHostController = rememberNavController()
) {
    var selectedPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = Screen.CAMERA
    ) {
        composable(Screen.CAMERA) {
            CameraScreen(
                viewModel = cameraViewModel,
                hasCameraPermission = hasCameraPermission,
                onNavigateToGallery = {
                    galleryViewModel.refreshPhotos()
                    navController.navigate(Screen.GALLERY)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.SETTINGS)
                }
            )
        }

        composable(Screen.GALLERY) {
            GalleryScreen(
                viewModel = galleryViewModel,
                onBack = { navController.popBackStack() },
                onPhotoClick = { photo ->
                    selectedPhoto = photo
                    navController.navigate(Screen.VIEWER)
                }
            )
        }

        composable(Screen.VIEWER) {
            selectedPhoto?.let { photo ->
                PhotoViewerScreen(
                    photo = photo,
                    viewModel = galleryViewModel,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) {
                navController.popBackStack()
            }
        }

        composable(Screen.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onShowPrivacy = { showPrivacyDialog = true }
            )
        }
    }

    if (showPrivacyDialog) {
        PrivacyDialog(onDismiss = { showPrivacyDialog = false })
    }
}
