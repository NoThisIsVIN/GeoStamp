package com.geostamp.camera.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.ScaleGestureDetector
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.Executor

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector,
    imageCapture: ImageCapture,
    aspectRatioStrategy: AspectRatioStrategy = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
    onCameraReady: (Camera) -> Unit = {},
    onTapToFocus: (Offset) -> Unit = {},
    onZoomChange: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasPermission) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        )
        return
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Binding completes asynchronously; always report to the latest callbacks, not the ones captured at bind time.
    val currentOnCameraReady by rememberUpdatedState(onCameraReady)
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)

    // Re-bind whenever screen is resumed or when previewView, cameraSelector, imageCapture or the ratio changes
    DisposableEffect(lifecycleOwner, previewViewRef, cameraSelector, imageCapture, aspectRatioStrategy) {
        val pv = previewViewRef ?: return@DisposableEffect onDispose {}

        val onBound: (Camera) -> Unit = { boundCam ->
            camera = boundCam
            currentOnCameraReady(boundCam)
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bindCamera(context, lifecycleOwner, pv, cameraSelector, imageCapture, aspectRatioStrategy, onBound)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            bindCamera(context, lifecycleOwner, pv, cameraSelector, imageCapture, aspectRatioStrategy, onBound)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val pv = previewViewRef ?: return@detectTapGestures
                        val factory = pv.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                        onTapToFocus(offset)
                    }
                },
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                // Scale gesture for pinch-to-zoom
                val scaleGestureDetector = ScaleGestureDetector(
                    ctx,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        // Track the requested ratio locally: zoomState lags behind setZoomRatio, which makes
                        // pinching feel sticky if every step is computed from the last reported value.
                        private var pinchRatio = 1f

                        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                            val cam = camera ?: return false
                            pinchRatio = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            return true
                        }

                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val cam = camera ?: return false
                            val zoomState = cam.cameraInfo.zoomState.value
                            pinchRatio = (pinchRatio * detector.scaleFactor).coerceIn(
                                zoomState?.minZoomRatio ?: 1f,
                                zoomState?.maxZoomRatio ?: 10f
                            )
                            cam.cameraControl.setZoomRatio(pinchRatio)
                            currentOnZoomChange(pinchRatio)
                            return true
                        }
                    }
                )

                previewView.setOnTouchListener { _, event ->
                    scaleGestureDetector.onTouchEvent(event)
                    false
                }

                previewViewRef = previewView
                previewView
            },
            update = {
                // Do not rebind camera on every Compose recomposition!
            }
        )
    }
}

private fun bindCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    cameraSelector: CameraSelector,
    imageCapture: ImageCapture,
    aspectRatioStrategy: AspectRatioStrategy,
    onBound: (Camera) -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    val executor: Executor = ContextCompat.getMainExecutor(context)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()
            val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(context, cameraProvider)

            extensionsManagerFuture.addListener({
                try {
                    val extensionsManager = extensionsManagerFuture.get()
                    val targetSelector = when {
                        extensionsManager.isExtensionAvailable(cameraSelector, ExtensionMode.AUTO) -> {
                            extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, ExtensionMode.AUTO)
                        }
                        extensionsManager.isExtensionAvailable(cameraSelector, ExtensionMode.HDR) -> {
                            extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, ExtensionMode.HDR)
                        }
                        else -> cameraSelector
                    }
                    bindWithSelector(cameraProvider, lifecycleOwner, previewView, targetSelector, imageCapture, aspectRatioStrategy, onBound)
                } catch (_: Exception) {
                    bindWithSelector(cameraProvider, lifecycleOwner, previewView, cameraSelector, imageCapture, aspectRatioStrategy, onBound)
                }
            }, executor)
        } catch (_: Exception) {}
    }, executor)
}

private fun bindWithSelector(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    cameraSelector: CameraSelector,
    imageCapture: ImageCapture,
    aspectRatioStrategy: AspectRatioStrategy,
    onBound: (Camera) -> Unit
) {
    // The preview stream uses the same aspect strategy as ImageCapture so the viewfinder frames what is saved.
    val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(aspectRatioStrategy)
        .build()
    try {
        val preview = Preview.Builder().setResolutionSelector(resolutionSelector).build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        cameraProvider.unbindAll()
        if (cameraProvider.hasCamera(cameraSelector)) {
            val cam = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            onBound(cam)
        }
    } catch (e: Exception) {
        try {
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            val preview = Preview.Builder().setResolutionSelector(resolutionSelector).build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            cameraProvider.unbindAll()
            if (cameraProvider.hasCamera(cameraSelector)) {
                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                onBound(cam)
            }
        } catch (_: Exception) {}
    }
}
