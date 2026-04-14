package com.example.adaptapp.model

object SessionBaseline {
    var rollDeg: Double? = null
        private set

    val isCalibrated: Boolean get() = rollDeg != null

    fun set(roll: Double) {
        rollDeg = roll
    }

    fun clear() {
        rollDeg = null
    }

    fun portraitAngle(): Double? = rollDeg

    fun landscapeAngle(): Double? = rollDeg?.let { it + LANDSCAPE_OFFSET_DEG }

    const val LANDSCAPE_OFFSET_DEG = 90.0
}
