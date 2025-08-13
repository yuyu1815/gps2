package com.example.indoorpositioning

import android.content.Context
import android.hardware.SensorManager
import com.kyle.fsensor.sensor.FSensor
import com.kyle.fsensor.sensor.FSensorEvent
import com.kyle.fsensor.sensor.FSensorEventListener
import com.kyle.fsensor.sensor.acceleration.KalmanLinearAccelerationFSensor
import com.kyle.fsensor.sensor.orientation.KalmanOrientationFSensor
import kotlin.math.cos
import kotlin.math.sin

class PDRTracker(
    context: Context,
    private val onPositionUpdate: (x: Float, y: Float) -> Unit
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val orientationSensor: FSensor = KalmanOrientationFSensor(sensorManager)
    private val accelerationSensor: FSensor = KalmanLinearAccelerationFSensor(sensorManager)

    private var currentAzimuth: Float = 0f
    private var absoluteX: Float = 0f
    private var absoluteY: Float = 0f

    // Simple Step Detection Parameters
    private var stepThreshold = 1.2f // m/s^2, tune this value
    private var lastStepTime: Long = 0
    private var isPeak = false
    private val STEP_DELAY_MS = 300 // At least 300ms between steps

    private val orientationListener = FSensorEventListener { event ->
        // event.values[0] is the azimuth (direction)
        currentAzimuth = event.values[0]
    }

    private val accelerationListener = FSensorEventListener { event ->
        // Use vertical acceleration (event.values[2] assuming Z is up) for step detection
        val verticalAcceleration = event.values[2]

        // TODO: Implement a more robust step detection algorithm.
        // This is a very basic peak detection.
        val currentTime = System.currentTimeMillis()
        if (verticalAcceleration > stepThreshold && !isPeak && (currentTime - lastStepTime > STEP_DELAY_MS)) {
            isPeak = true
            lastStepTime = currentTime
            onStepDetected()
        } else if (verticalAcceleration < stepThreshold) {
            isPeak = false
        }
    }

    private fun onStepDetected() {
        val stepLength = 0.7f // Assume a fixed step length in meters

        // Update position based on azimuth and step length
        // Note: The conversion from azimuth to radians and coordinate system alignment is crucial.
        // Azimuth is in degrees, convert to radians for trig functions.
        val azimuthRad = Math.toRadians(currentAzimuth.toDouble()).toFloat()

        absoluteX += stepLength * sin(azimuthRad)
        absoluteY -= stepLength * cos(azimuthRad) // Assuming Y is north, so negative cos

        onPositionUpdate(absoluteX, absoluteY)
    }

    fun setOrigin(x: Float, y: Float) {
        absoluteX = x
        absoluteY = y
    }

    fun start() {
        orientationSensor.registerListener(orientationListener, SensorManager.SENSOR_DELAY_UI)
        accelerationSensor.registerListener(accelerationListener, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        orientationSensor.unregisterListener(orientationListener)
        accelerationSensor.unregisterListener(accelerationListener)
    }
}
