package com.example.adaptapp.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.adaptapp.kinematics.ArmKinematics
import com.example.adaptapp.model.ArmPosition

class StepAdjustmentController(private val armController: ArmController) {

    companion object {
        private const val TAG = "StepAdjust"
        const val STEP_Z_MM = 10.0
        const val BASE_STEP_RAD = 0.052  // ~3°
        const val TILT_STEP_DEG = 5.0
        // tilt 方向：在 0°-106° 安全区内，减小角度 = 手机仰角上调
        // 如果实测方向反了，翻转这个符号即可
        const val TILT_UP_SIGN = -1.0
        // tilt 危险区边界
        const val TILT_DANGER_MIN = 106.0
        const val TILT_DANGER_MAX = 284.0
        const val TILT_COMPENSATION_DEG = 5.0
        const val TILT_COMPENSATION_DELAY_MS = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())

    sealed class AdjustResult {
        data object Success : AdjustResult()
        data class Failed(val reason: String) : AdjustResult()
    }

    fun adjustLeft(feedback: ArmPosition): AdjustResult {
        val newBase = feedback.b - BASE_STEP_RAD
        val target = ArmPosition(name = "", b = newBase, s = feedback.s, e = feedback.e, t = feedback.t)
        Log.i(TAG, "adjustLeft: base ${feedback.b} -> $newBase")
        armController.moveTo(target, speed = 200, acc = 10)
        return AdjustResult.Success
    }

    fun adjustRight(feedback: ArmPosition): AdjustResult {
        val newBase = feedback.b + BASE_STEP_RAD
        val target = ArmPosition(name = "", b = newBase, s = feedback.s, e = feedback.e, t = feedback.t)
        Log.i(TAG, "adjustRight: base ${feedback.b} -> $newBase")
        armController.moveTo(target, speed = 200, acc = 10)
        return AdjustResult.Success
    }
    fun adjustUp(feedback: ArmPosition): AdjustResult {
        val result = adjustCartesian(feedback, dz = STEP_Z_MM)
        if (result is AdjustResult.Success) {
            feedback.tilt?.let { currentTilt ->
                val target = clampTiltSafe(currentTilt + TILT_COMPENSATION_DEG)
                handler.postDelayed({
                    if (!armController.isStopped) armController.sendTiltAbsolute(target)
                }, TILT_COMPENSATION_DELAY_MS)
            }
        }
        return result
    }

    fun adjustDown(feedback: ArmPosition): AdjustResult {
        val result = adjustCartesian(feedback, dz = -STEP_Z_MM)
        if (result is AdjustResult.Success) {
            feedback.tilt?.let { currentTilt ->
                val target = clampTiltSafe(currentTilt - TILT_COMPENSATION_DEG)
                handler.postDelayed({
                    if (!armController.isStopped) armController.sendTiltAbsolute(target)
                }, TILT_COMPENSATION_DELAY_MS)
            }
        }
        return result
    }

    fun tiltUp(feedback: ArmPosition): AdjustResult {
        feedback.p?.let { armController.sendRollAbsolute(it) }
        val currentTilt = feedback.tilt ?: return AdjustResult.Failed("No tilt data")
        val newTilt = clampTiltSafe(currentTilt + TILT_UP_SIGN * TILT_STEP_DEG)
        Log.i(TAG, "tiltUp: $currentTilt -> $newTilt")
        armController.sendTiltAbsolute(newTilt)
        return AdjustResult.Success
    }

    fun tiltDown(feedback: ArmPosition): AdjustResult {
        feedback.p?.let { armController.sendRollAbsolute(it) }
        val currentTilt = feedback.tilt ?: return AdjustResult.Failed("No tilt data")
        val newTilt = clampTiltSafe(currentTilt - TILT_UP_SIGN * TILT_STEP_DEG)
        Log.i(TAG, "tiltDown: $currentTilt -> $newTilt")
        armController.sendTiltAbsolute(newTilt)
        return AdjustResult.Success
    }

    private fun clampTiltSafe(angle: Double): Double {
        val normalized = ((angle % 360.0) + 360.0) % 360.0
        return if (normalized > TILT_DANGER_MIN && normalized < TILT_DANGER_MAX) {
            if (normalized <= (TILT_DANGER_MIN + TILT_DANGER_MAX) / 2) TILT_DANGER_MIN else TILT_DANGER_MAX
        } else normalized
    }

    private fun adjustCartesian(feedback: ArmPosition, dy: Double = 0.0, dz: Double = 0.0): AdjustResult {
        val params = armController.currentIkParams
        val pos = ArmKinematics.forwardKinematics(
            baseRad = feedback.b,
            shoulderRad = feedback.s,
            elbowRad = feedback.e,
            handRad = feedback.t,
            params = params
        )
        val newX = pos.x
        val newY = pos.y + dy
        val newZ = pos.z + dz
        val t = feedback.t
        Log.i(TAG, "adjustCartesian: (${pos.x}, ${pos.y}, ${pos.z}) -> ($newX, $newY, $newZ)")
        armController.sendCartesianGoal(newX, newY, newZ, t)
        return AdjustResult.Success
    }
}
