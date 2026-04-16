package com.example.adaptapp.kinematics

import com.example.adaptapp.model.IkParams
import kotlin.math.cos
import kotlin.math.sin

data class CartesianPosition(val x: Double, val y: Double, val z: Double)

object ArmKinematics {

    fun forwardKinematics(
        baseRad: Double,
        shoulderRad: Double,
        elbowRad: Double,
        handRad: Double,
        params: IkParams
    ): CartesianPosition {
        val aOut = params.l2 * cos(Math.PI / 2 - (shoulderRad + params.t2rad))
        val bOut = params.l2 * sin(Math.PI / 2 - (shoulderRad + params.t2rad))

        val cOut = params.l3 * cos(Math.PI / 2 - (elbowRad + shoulderRad))
        val dOut = params.l3 * sin(Math.PI / 2 - (elbowRad + shoulderRad))

        val rEe = aOut + cOut
        val zEe = bOut + dOut

        val xEe = rEe * cos(baseRad)
        val yEe = rEe * sin(baseRad)

        return CartesianPosition(xEe, yEe, zEe)
    }
}
