package com.example.adaptapp.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.adaptapp.kinematics.ArmKinematics
import com.example.adaptapp.model.ArmPosition

class StepAdjustmentController(private val armController: ArmController) {

    companion object {
        private const val TAG = "StepAdjust"
        const val BASE_STEP_RAD = 0.052      // ~3°，adjust left/right
        const val SHOULDER_STEP_RAD = 0.035  // ~2°，adjust up/down（关节空间，避免 IK 跳变）
        // 若实测 adjust up 时手机下移，把 SHOULDER_UP_SIGN 翻转
        const val SHOULDER_UP_SIGN = -1.0
        const val TILT_STEP_DEG = 5.0
        // tilt 方向：在 0°-106° 安全区内，减小角度 = 手机仰角上调
        // 如果实测方向反了，翻转这个符号即可
        const val TILT_UP_SIGN = -1.0
        // tilt 危险区边界
        const val TILT_DANGER_MIN = 106.0
        const val TILT_DANGER_MAX = 284.0
        const val TILT_COMPENSATION_UP_DEG = 1.5    // adjust up 时镜头补偿量（向下转）
        const val TILT_COMPENSATION_DOWN_DEG = 2.5  // adjust down 时镜头补偿量（向上转）
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
    // 关节空间抬升：shoulder 旋转 + base 显式锁定，避免固件 IK 在某些角度跳变导致 base 180° 翻转
    fun adjustUp(feedback: ArmPosition): AdjustResult {
        val newShoulder = feedback.s + SHOULDER_UP_SIGN * SHOULDER_STEP_RAD
        val target = ArmPosition(name = "", b = feedback.b, s = newShoulder, e = feedback.e, t = feedback.t)
        Log.i(TAG, "adjustUp: shoulder ${feedback.s} -> $newShoulder, base locked=${feedback.b}")
        armController.moveTo(target, speed = 200, acc = 10)
        feedback.tilt?.let { currentTilt ->
            val tiltTarget = clampTiltSafe(currentTilt + TILT_COMPENSATION_UP_DEG)
            armController.scheduleCancellable(TILT_COMPENSATION_DELAY_MS) {
                armController.sendTiltAbsolute(tiltTarget)
            }
        }
        return AdjustResult.Success
    }

    fun adjustDown(feedback: ArmPosition): AdjustResult {
        val newShoulder = feedback.s - SHOULDER_UP_SIGN * SHOULDER_STEP_RAD
        val target = ArmPosition(name = "", b = feedback.b, s = newShoulder, e = feedback.e, t = feedback.t)
        Log.i(TAG, "adjustDown: shoulder ${feedback.s} -> $newShoulder, base locked=${feedback.b}")
        armController.moveTo(target, speed = 200, acc = 10)
        feedback.tilt?.let { currentTilt ->
            val tiltTarget = clampTiltSafe(currentTilt - TILT_COMPENSATION_DOWN_DEG)
            armController.scheduleCancellable(TILT_COMPENSATION_DELAY_MS) {
                armController.sendTiltAbsolute(tiltTarget)
            }
        }
        return AdjustResult.Success
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
