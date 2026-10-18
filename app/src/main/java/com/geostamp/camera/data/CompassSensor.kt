package com.geostamp.camera.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class CompassState(
    val azimuthDegrees: Float = 0f,
    val cardinalDirection: String = "N"
)

class CompassSensor(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _compassFlow = MutableStateFlow(CompassState())
    val compassFlow: StateFlow<CompassState> = _compassFlow.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var smoothedAzimuth: Float = 0f
    private val alpha = 0.15f // Low pass filter factor

    private val accelReading = FloatArray(3)
    private val magnetReading = FloatArray(3)
    private var hasAccel = false
    private var hasMagnet = false

    private var isListening = false

    fun startListening() {
        if (isListening) return
        isListening = true

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(
                this,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_UI
            )
        } else {
            // Fallback for devices without rotation vector
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            }
            if (magnetometer != null) {
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        var calculatedAzimuth: Float? = null

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            calculatedAzimuth = computeRemappedAzimuth()
        } else {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, accelReading, 0, 3)
                hasAccel = true
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magnetReading, 0, 3)
                hasMagnet = true
            }
            if (hasAccel && hasMagnet) {
                val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelReading, magnetReading)
                if (success) {
                    calculatedAzimuth = computeRemappedAzimuth()
                }
            }
        }

        if (calculatedAzimuth != null) {
            // Handle degree wraparound around 0/360 for smooth low-pass interpolation
            var diff = calculatedAzimuth - smoothedAzimuth
            while (diff < -180f) diff += 360f
            while (diff > 180f) diff -= 360f

            smoothedAzimuth = (smoothedAzimuth + alpha * diff + 360f) % 360f

            val cardinal = getCardinal(smoothedAzimuth)
            _compassFlow.value = CompassState(
                azimuthDegrees = smoothedAzimuth,
                cardinalDirection = cardinal
            )
        }
    }

    private fun computeRemappedAzimuth(): Float {
        val displayRotation = try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }

        var axisX = SensorManager.AXIS_X
        var axisY = SensorManager.AXIS_Y

        when (displayRotation) {
            Surface.ROTATION_90 -> {
                axisX = SensorManager.AXIS_Y
                axisY = SensorManager.AXIS_MINUS_X
            }
            Surface.ROTATION_180 -> {
                axisX = SensorManager.AXIS_MINUS_X
                axisY = SensorManager.AXIS_MINUS_Y
            }
            Surface.ROTATION_270 -> {
                axisX = SensorManager.AXIS_MINUS_Y
                axisY = SensorManager.AXIS_X
            }
        }

        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
        SensorManager.getOrientation(remappedMatrix, orientationAngles)

        val azimuthRad = orientationAngles[0]
        var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
        if (azimuthDeg < 0) azimuthDeg += 360f
        return azimuthDeg
    }

    private fun getCardinal(azimuth: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = (((azimuth + 22.5f) % 360f) / 45f).toInt()
        return directions[index.coerceIn(0, 7)]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
