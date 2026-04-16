package com.example.adaptapp.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.atan2

class PhoneOrientationService(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "PhoneOrientation"
        private const val WINDOW_SIZE = 5
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val lock = Any()
    private val rollWindow = ArrayDeque<Float>(WINDOW_SIZE)
    @Volatile private var latestGx: Float = 0f
    @Volatile private var latestGy: Float = 0f
    @Volatile private var latestGz: Float = 0f
    @Volatile
    private var started = false

    val isAvailable: Boolean get() = gravitySensor != null

    fun start() {
        if (started || gravitySensor == null) return
        synchronized(lock) { rollWindow.clear() }
        sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        started = true
        Log.i(TAG, "Started (TYPE_GRAVITY)")
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        synchronized(lock) { rollWindow.clear() }
        started = false
        Log.i(TAG, "Stopped")
    }

    fun getCurrentRollDegrees(): Double? {
        synchronized(lock) {
            if (rollWindow.size < 3) return null
            // Circular mean：把弧度映射到单位圆再求平均方向，不受 ±π 跨界影响
            var sinSum = 0.0
            var cosSum = 0.0
            for (angle in rollWindow) {
                sinSum += Math.sin(angle.toDouble())
                cosSum += Math.cos(angle.toDouble())
            }
            return Math.toDegrees(Math.atan2(sinSum, cosSum))
        }
    }

    fun getGravityX(): Float = latestGx
    fun getGravityY(): Float = latestGy
    fun getGravityZ(): Float = latestGz

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GRAVITY) return
        val gx = event.values[0]
        val gy = event.values[1]
        latestGx = gx
        latestGy = gy
        latestGz = event.values[2]
        // 画面内歪斜角：atan2(-gx, -gy)
        // 手机正立 portrait: gx≈0, gy≈-9.8 → atan2(0, 9.8) = 0
        // 顺时针歪: gx>0 → 角为负; 逆时针歪: gx<0 → 角为正
        // 手机正立（camera 朝上、USB 朝下）时 gy>0 → error≈0
        val tilt = atan2(-gx, gy)
        synchronized(lock) {
            if (rollWindow.size >= WINDOW_SIZE) rollWindow.removeFirst()
            rollWindow.addLast(tilt)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
