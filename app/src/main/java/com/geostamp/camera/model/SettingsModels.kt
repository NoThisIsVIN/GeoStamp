package com.geostamp.camera.model

import android.graphics.Color

data class StampSettings(
    val template: StampTemplate = StampTemplate.CLASSIC_BOTTOM_BAR,
    val position: StampPosition = StampPosition.BOTTOM,
    val mapStyle: MapStyle = MapStyle.SATELLITE,
    val textSizeScale: Float = 1.0f,
    val backgroundOpacity: Float = 0.65f,
    val textColor: Int = Color.WHITE,
    val showMap: Boolean = true,
    val showAddress: Boolean = true,
    val showCoordinates: Boolean = true,
    val showAltitude: Boolean = true,
    val showAccuracy: Boolean = true,
    val showCompass: Boolean = true,
    val showDateTime: Boolean = true,
    val showWeather: Boolean = true,
    val showNote: Boolean = true,
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DECIMAL_DEGREES,
    val is24Hour: Boolean = true,
    val dateFormatPattern: String = "yyyy-MM-dd",
    val customNote: String = ""
)

data class AppSettings(
    val captureQuality: CaptureQuality = CaptureQuality.HIGH,
    val colorProfile: ColorProfile = ColorProfile.VIBRANT,
    val captureModeLatency: Boolean = false,
    val aspectRatio: AspectRatioChoice = AspectRatioChoice.RATIO_4_3,
    val timerOption: TimerOption = TimerOption.OFF,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val playShutterSound: Boolean = true,
    val saveUnstampedOriginal: Boolean = false,
    val mirrorSelfie: Boolean = false,
    val showGrid: Boolean = false,
    val allowCaptureWithoutGps: Boolean = true,
    val fileNamePattern: String = "GeoStamp_yyyyMMdd_HHmmss",
    val batteryTipDismissed: Boolean = false,
    val onboardingCompleted: Boolean = false
)
