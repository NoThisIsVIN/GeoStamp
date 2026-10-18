package com.geostamp.camera.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.ScaleGestureDetector
import androidx.camera.core.*
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
import androidx.compose.ui.platform.LocalLifecycleOwner
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

    // Re-bind whenever screen is resumed or when previewView, cameraSelector, or imageCapture changes
    DisposableEffect(lifecycleOwner, previewViewRef, cameraSelector, imageCapture) {
        val pv = previewViewRef ?: return@DisposableEffect onDispose {}

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bindCamera(context, lifecycleOwner, pv, cameraSelector, imageCapture) { boundCam ->
                    camera = boundCam
                    onCameraReady(boundCam)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            bindCamera(context, lifecycleOwner, pv, cameraSelector, imageCapture) { boundCam ->
                camera = boundCam
                onCameraReady(boundCam)
            }
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
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val cam = camera ?: return false
                            val currentZoomRatio = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            val delta = detector.scaleFactor
                            val newZoomRatio = (currentZoomRatio * delta).coerceIn(
                                cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f,
                                cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 10f
                            )
                            cam.cameraControl.setZoomRatio(newZoomRatio)
                            onZoomChange(newZoomRatio)
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
                    bindWithSelector(cameraProvider, lifecycleOwner, previewView, targetSelector, imageCapture, onBound)
                } catch (_: Exception) {
                    bindWithSelector(cameraProvider, lifecycleOwner, previewView, cameraSelector, imageCapture, onBound)
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
    onBound: (Camera) -> Unit
) {
    try {
        val preview = Preview.Builder().build().also {
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
            val preview = Preview.Builder().build().also {
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
