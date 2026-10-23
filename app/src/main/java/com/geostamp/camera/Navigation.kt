package com.geostamp.camera

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.geostamp.camera.model.PhotoItem
import com.geostamp.camera.ui.camera.CameraScreen
import com.geostamp.camera.ui.camera.CameraViewModel
import com.geostamp.camera.ui.edit.PhotoEditScreen
import com.geostamp.camera.ui.gallery.GalleryScreen
import com.geostamp.camera.ui.gallery.GalleryViewModel
import com.geostamp.camera.ui.gallery.PhotoViewerScreen
import com.geostamp.camera.ui.privacy.PrivacyDialog
import com.geostamp.camera.ui.settings.SettingsScreen
import com.geostamp.camera.ui.settings.SettingsViewModel

object Screen {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val SETTINGS = "settings"

    const val ARG_PHOTO_ID = "photoId"
    const val VIEWER = "viewer/{$ARG_PHOTO_ID}"
    const val EDIT = "edit/{$ARG_PHOTO_ID}"

    fun viewer(photoId: Long) = "viewer/$photoId"
    fun edit(photoId: Long) = "edit/$photoId"
}

private const val NAV_ANIM_MS = 280

private val photoIdArgs = listOf(navArgument(Screen.ARG_PHOTO_ID) { type = NavType.LongType })

// Shared-axis style: a short slide plus fade reads as depth without the full-width swipe feel.
private val defaultEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) +
        slideInHorizontally(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { it / 10 }
}
private val defaultExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(NAV_ANIM_MS / 2))
}
private val defaultPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing))
}
private val defaultPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(NAV_ANIM_MS / 2)) +
        slideOutHorizontally(tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { it / 10 }
}

@Composable
fun AppNavigation(
    cameraViewModel: CameraViewModel,
    hasCameraPermission: Boolean = true,
    galleryViewModel: GalleryViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    navController: NavHostController = rememberNavController()
) {
    var showPrivacyDialog by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = Screen.CAMERA,
        enterTransition = defaultEnter,
        exitTransition = defaultExit,
        popEnterTransition = defaultPopEnter,
        popExitTransition = defaultPopExit
    ) {
        composable(Screen.CAMERA) {
            CameraScreen(
                viewModel = cameraViewModel,
                hasCameraPermission = hasCameraPermission,
                // The gallery refreshes itself whenever it resumes.
                onNavigateToGallery = { navController.navigate(Screen.GALLERY) },
                onNavigateToSettings = { navController.navigate(Screen.SETTINGS) }
            )
        }

        composable(Screen.GALLERY) { entry ->
            GalleryScreen(
                viewModel = galleryViewModel,
                onBack = { navController.popIfCurrent(entry) },
                onOpenCamera = { navController.popBackStack(Screen.CAMERA, inclusive = false) },
                onPhotoClick = { photo ->
                    // Guard against a double tap stacking two viewers.
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        navController.navigate(Screen.viewer(photo.id))
                    }
                }
            )
        }

        composable(
            route = Screen.VIEWER,
            arguments = photoIdArgs,
            // The viewer zooms up out of the grid rather than sliding in.
            enterTransition = { fadeIn(tween(NAV_ANIM_MS)) + scaleIn(tween(NAV_ANIM_MS), initialScale = 0.94f) },
            popExitTransition = { fadeOut(tween(NAV_ANIM_MS)) + scaleOut(tween(NAV_ANIM_MS), targetScale = 0.94f) }
        ) { entry ->
            val photoId = entry.arguments?.getLong(Screen.ARG_PHOTO_ID) ?: -1L
            PhotoViewerScreen(
                initialPhotoId = photoId,
                viewModel = galleryViewModel,
                onBack = { navController.popIfCurrent(entry) },
                onEdit = { photo ->
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        navController.navigate(Screen.edit(photo.id))
                    }
                }
            )
        }

        composable(route = Screen.EDIT, arguments = photoIdArgs) { entry ->
            val photoId = entry.arguments?.getLong(Screen.ARG_PHOTO_ID) ?: -1L
            val photo = rememberPhotoById(galleryViewModel, photoId) {
                // Deleted underneath us (or never existed): nothing left to edit.
                navController.popIfCurrent(entry)
            }
            photo?.let {
                PhotoEditScreen(
                    photo = it,
                    onBack = { navController.popIfCurrent(entry) },
                    // Only the viewer opens the editor, so popping lands back on it. The viewer
                    // keeps tracking this photo id, so it follows the photo if its sort position moved.
                    onSaved = { navController.popIfCurrent(entry) }
                )
            }
        }

        composable(Screen.SETTINGS) { entry ->
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popIfCurrent(entry) },
                onShowPrivacy = { showPrivacyDialog = true }
            )
        }
    }

    if (showPrivacyDialog) {
        PrivacyDialog(onDismiss = { showPrivacyDialog = false })
    }
}

/**
 * Pops only if [entry] is still on top. Screens can request "go back" asynchronously (e.g. after a
 * delete or save completes); without this guard a late call could pop whatever screen is now on top.
 */
private fun NavHostController.popIfCurrent(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/**
 * Resolves a photo from the live gallery list so screens always see its latest version (e.g. a new
 * dateModified after an edit). Calls [onMissing] once the library has loaded without it.
 */
@Composable
private fun rememberPhotoById(
    viewModel: GalleryViewModel,
    photoId: Long,
    onMissing: () -> Unit
): PhotoItem? {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val hasLoaded by viewModel.hasLoaded.collectAsStateWithLifecycle()
    val photo = remember(photos, photoId) { photos.firstOrNull { it.id == photoId } }
    val currentOnMissing by rememberUpdatedState(onMissing)

    LaunchedEffect(Unit) {
        // After process death the list starts empty; load it before deciding the photo is gone.
        if (!hasLoaded) viewModel.refreshPhotos()
    }
    var handledMissing by remember { mutableStateOf(false) }
    LaunchedEffect(photo, hasLoaded) {
        if (photo == null && hasLoaded && !handledMissing) {
            handledMissing = true
            currentOnMissing()
        }
    }
    return photo
}
