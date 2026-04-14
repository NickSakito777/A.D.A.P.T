package com.example.adaptapp.controller

import android.util.Log
import com.example.adaptapp.model.SessionBaseline
import com.example.adaptapp.sensor.PhoneOrientationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AlignmentStatus {
    NOT_ALIGNED,
    ALIGNING,
    ALIGNED,
    FAILED
}

class AutoLevelController(
    private val armController: ArmController,
    private val orientationService: PhoneOrientationService
) {

    companion object {
        private const val TAG = "AutoLevel"
        const val SUCCESS_THRESHOLD_DEG = 1.5
        const val CONFIRM_COUNT = 2
        const val CONFIRM_INTERVAL_MS = 300L
        const val MAX_ITERATIONS = 50
        const val MAX_DURATION_MS = 120000L
        const val FLAT_THRESHOLD_MS2 = 8.5f
        const val INITIAL_FEEDBACK_TIMEOUT_MS = 1500L
        const val REANCHOR_FEEDBACK_TIMEOUT_MS = 800L
        const val STEP_REACH_TIMEOUT_MS = 7000L
        const val STEP_REACH_POLL_INTERVAL_MS = 120L
        const val SERVO_SETTLE_DELTA_DEG = 0.8
        const val SERVO_SETTLE_CONFIRM_COUNT = 2
        const val CONTROL_REACH_TOLERANCE_DEG = 5.0
    }

    private val _status = MutableStateFlow(AlignmentStatus.NOT_ALIGNED)
    val status: StateFlow<AlignmentStatus> = _status.asStateFlow()

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var pendingFeedbackRoll: Double? = null
    @Volatile private var waitingForFeedback = false

    fun start() {
        if (_status.value == AlignmentStatus.ALIGNING) return
        job?.cancel()
        // 确保 roll 扭矩开启，否则 servo 无法转动
        armController.setRollTorque(true)
        SessionBaseline.clear()
        pendingFeedbackRoll = null
        _status.value = AlignmentStatus.ALIGNING
        job = scope.launch { runCalibration() }
    }

    fun reset() {
        job?.cancel()
        job = null
        orientationService.stop()
        waitingForFeedback = false
        SessionBaseline.clear()
        _status.value = AlignmentStatus.NOT_ALIGNED
        Log.i(TAG, "Reset")
    }

    fun onFeedbackReceived(rollDeg: Double) {
        if (waitingForFeedback) {
            pendingFeedbackRoll = rollDeg
            waitingForFeedback = false
        }
    }

    // ─── 主流程：探测方向 → 一步步转到 error≈0 ────────────

    private suspend fun runCalibration() {
        Log.i(TAG, "=== Start ===")

        var currentServo = requestServoRoll() ?: run {
            fail("Cannot read servo"); return
        }
        orientationService.start()
        delay(300)
        val startTime = System.currentTimeMillis()

        try {
            val gz = orientationService.getGravityZ()
            if (Math.abs(gz) > FLAT_THRESHOLD_MS2) {
                fail("Phone nearly flat (gz=${fmt(gz)})"); return
            }

            var currentError = readStableRoll() ?: run {
                fail("Sensor not available"); return
            }
            logImu("Initial", currentServo, currentError)

            if (Math.abs(currentError) <= SUCCESS_THRESHOLD_DEG) {
                if (confirmConvergence()) { succeed(currentServo); return }
            }

            // ── 探测方向 ──
            val direction = detectDirection(currentServo, currentError) ?: return
            currentServo = requestServoRoll() ?: run {
                fail("Cannot read servo"); return
            }
            delay(100)
            currentError = readStableRoll() ?: run {
                fail("Sensor failed"); return
            }
            Log.i(TAG, "Direction=$direction, servo=${fmt(currentServo)}, error=${fmt(currentError)}")

            if (Math.abs(currentError) <= SUCCESS_THRESHOLD_DEG) {
                if (confirmConvergence()) { succeed(currentServo); return }
            }

            // ── 主循环：走近路，error 应该单调下降 ──
            var consecutiveFails = 0

            for (iter in 0 until MAX_ITERATIONS) {
                if (armController.isStopped) { fail("E-stop"); return }
                if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                    fail("Timeout"); return
                }

                val absErr = Math.abs(currentError)
                if (absErr <= SUCCESS_THRESHOLD_DEG) {
                    if (confirmConvergence()) { succeed(currentServo); return }
                }

                val step = chooseStep(absErr)
                val target = wrap(currentServo + direction * step)

                Log.i(TAG, "[$iter] error=${fmt(currentError)}, step=${fmt(step)}, target=${fmt(target)}")
                armController.sendRollAbsolute(target)

                val actual = moveAndGetActual(target) ?: run {
                    fail("Feedback timeout"); return
                }
                delay(150)

                val newError = readStableRoll() ?: run {
                    fail("Sensor failed"); return
                }
                val improved = Math.abs(newError) < absErr

                logImu("  [$iter] result", actual, newError)
                Log.i(TAG, "  -> ${if (improved) "OK" else "WORSE"}")

                currentServo = actual
                currentError = newError

                if (improved) {
                    consecutiveFails = 0
                } else {
                    consecutiveFails++
                    if (consecutiveFails >= 3) {
                        fail("Error not decreasing (${fmt(currentError)})"); return
                    }
                }
            }

            fail("Max iterations (error=${fmt(currentError)})")

        } catch (e: CancellationException) {
            throw e
        } finally {
            orientationService.stop()
        }
    }

    // 探测方向：试正方向，看 error 变好还是变坏
    private suspend fun detectDirection(servo: Double, error: Double): Int? {
        val absErr0 = Math.abs(error)

        for (testStep in doubleArrayOf(12.0, 16.0, 20.0)) {
            val target = wrap(servo + testStep)
            Log.i(TAG, "Dir test: +${fmt(testStep)} -> ${fmt(target)}")
            armController.sendRollAbsolute(target)

            val actual = moveAndGetActual(target)
            if (actual == null) { fail("Dir test timeout"); return null }

            val moved = Math.abs(shortestDiff(actual, servo))
            if (moved < 2.0) {
                Log.w(TAG, "  servo didn't move (delta=${fmt(moved)})")
                continue
            }

            delay(150)
            val newError = readStableRoll()
            if (newError == null) { fail("Sensor failed"); return null }

            val dir = if (Math.abs(newError) < absErr0) +1 else -1
            Log.i(TAG, "  error: ${fmt(error)} -> ${fmt(newError)}, direction=$dir")
            return dir
        }

        fail("Servo not responding")
        return null
    }

    // ─── 工具 ───────────────────────────────────────────

    private fun chooseStep(absErr: Double): Double = when {
        absErr > 90.0 -> 45.0
        absErr > 45.0 -> 30.0
        absErr > 20.0 -> 15.0
        absErr > 10.0 -> 5.0
        else          -> 3.0
    }

    // 等 servo 到位或超时，返回实际位置（接受部分到位）
    private suspend fun moveAndGetActual(target: Double): Double? {
        val deadline = System.currentTimeMillis() + STEP_REACH_TIMEOUT_MS
        var lastActual: Double? = null
        var settleCount = 0

        while (System.currentTimeMillis() < deadline) {
            val actual = requestServoRoll(REANCHOR_FEEDBACK_TIMEOUT_MS)
            if (actual != null) {
                val prev = lastActual
                lastActual = actual
                val remaining = Math.abs(shortestDiff(target, actual))

                if (remaining <= CONTROL_REACH_TOLERANCE_DEG) {
                    if (prev != null &&
                        Math.abs(shortestDiff(actual, prev)) <= SERVO_SETTLE_DELTA_DEG
                    ) {
                        settleCount++
                    } else {
                        settleCount = 1
                    }
                    if (settleCount >= SERVO_SETTLE_CONFIRM_COUNT) return actual
                } else {
                    settleCount = 0
                }
            }
            delay(STEP_REACH_POLL_INTERVAL_MS)
        }
        // 没完全到位，返回最后读到的位置
        return lastActual
    }

    private suspend fun requestServoRoll(timeoutMs: Long = INITIAL_FEEDBACK_TIMEOUT_MS): Double? {
        pendingFeedbackRoll = null
        waitingForFeedback = true
        armController.readFeedback()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (waitingForFeedback && System.currentTimeMillis() < deadline) { delay(50) }
        waitingForFeedback = false
        return pendingFeedbackRoll
    }

    private suspend fun readStableRoll(): Double? {
        var sinSum = 0.0
        var cosSum = 0.0
        var count = 0
        repeat(3) {
            val roll = orientationService.getCurrentRollDegrees()
            if (roll != null) {
                sinSum += Math.sin(Math.toRadians(roll))
                cosSum += Math.cos(Math.toRadians(roll))
                count++
            }
            delay(50)
        }
        if (count < 2) return null
        return Math.toDegrees(Math.atan2(sinSum, cosSum))
    }

    private suspend fun confirmConvergence(): Boolean {
        repeat(CONFIRM_COUNT - 1) {
            delay(CONFIRM_INTERVAL_MS)
            val roll = readStableRoll() ?: return false
            if (Math.abs(roll) > SUCCESS_THRESHOLD_DEG) {
                Log.i(TAG, "Confirm failed: ${fmt(roll)}")
                return false
            }
        }
        Log.i(TAG, "Aligned!")
        return true
    }

    private fun shortestDiff(a: Double, b: Double): Double {
        var d = a - b
        while (d <= -180.0) d += 360.0
        while (d > 180.0) d -= 360.0
        return d
    }

    private fun wrap(deg: Double) = ((deg % 360.0) + 360.0) % 360.0

    private suspend fun succeed(servo: Double) {
        orientationService.stop()
        val actual = requestServoRoll()
        val baseline = actual ?: servo
        SessionBaseline.set(baseline)
        Log.i(TAG, "=== Done! baseline=${fmt(baseline)} ===")
        _status.value = AlignmentStatus.ALIGNED
    }

    private fun fail(reason: String) {
        Log.w(TAG, "=== Failed: $reason ===")
        _status.value = AlignmentStatus.FAILED
        orientationService.stop()
    }

    private fun logImu(label: String, servo: Double, error: Double) {
        val gx = orientationService.getGravityX()
        val gy = orientationService.getGravityY()
        val gz = orientationService.getGravityZ()
        Log.i(TAG, "$label: servo=${fmt(servo)}, error=${fmt(error)}, " +
            "gx=${fmt(gx)}, gy=${fmt(gy)}, gz=${fmt(gz)}")
    }

    private fun fmt(v: Double) = String.format("%.1f", v)
    private fun fmt(v: Float) = String.format("%.1f", v)
}
