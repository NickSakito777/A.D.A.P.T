package com.example.adaptapp.model

import kotlin.math.atan2
import kotlin.math.sqrt

data class IkParams(
    val l2A: Double,
    val l2B: Double,
    val l3A: Double,
    val l3B: Double,
    val l4A: Double = 67.85,
    val l4B: Double = 5.98
) {
    val l2: Double get() = sqrt(l2A * l2A + l2B * l2B)
    val t2rad: Double get() = atan2(l2B, l2A)
    val l3: Double get() = sqrt(l3A * l3A + l3B * l3B)
    val t3rad: Double get() = atan2(l3B, l3A)

    companion object {
        val DEFAULT = IkParams(
            l2A = 236.82,
            l2B = 30.0,
            l3A = 152.16,
            l3B = 0.0
        )
    }
}
