package com.example.adaptapp.controller

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.model.SessionBaseline
import org.json.JSONObject

// 机械臂控制器 — 封装所有 T-code 命令
class ArmController(var connection: ConnectionManager) {

    private val handler = Handler(Looper.getMainLooper())

    // Stop latch — emergencyStop() 置 true，之后 send() 拦截所有普通命令
    // 只有显式调用 resetStop() 才能解除（当前不接入任何 UI 自动调用）
    // 未来状态扩展预留：STOP_ACTIVE / RECOVERING / SAFE_REACHED_WAITING_RESUME / NORMAL
    var isStopped: Boolean by mutableStateOf(false)
        private set

    // 移动到指定位置（T:102 最短路径 + T:700 Roll + T:703 Tilt）
    // T:102 是 fire-and-forget（固件立即返回），T:700/T:703 是阻塞 P 控循环。
    // 如果三条命令无间隔发出，T:700/T:703 会在固件里嵌套执行，导致 T:0 急停
    // 无法及时打断。用 handler.post 把 phone 轴命令延后，确保 T:102 先被
    // 主循环处理完、T:700 在主循环启动（而非嵌套在另一个 P 控里）。
    fun moveTo(position: ArmPosition, speed: Int = 400, acc: Int = 10) {
        // 臂关节同步移动（fire-and-forget）
        send("""{"T":102,"base":${position.b},"shoulder":${position.s},"elbow":${position.e},"hand":${position.t},"spd":$speed,"acc":$acc}""")

        val now = android.os.SystemClock.uptimeMillis()

        // Phone Roll — 延后 50ms，等 T:102 被固件主循环处理
        // p 存的是相对 baseline 的 offset，recall 时还原为绝对角度
        // 必须挂 ESTOP_TOKEN，否则 emergencyStop() 取消不掉，stop 后还会继续发 T:700
        position.p?.let { rollOffset ->
            val baseline = SessionBaseline.rollDeg ?: return@let
            val absolute = baseline + rollOffset
            handler.postAtTime({
                if (!isStopped) sendRollAbsolute(absolute)
            }, ESTOP_TOKEN, now + 50)
        }

        // Phone Tilt — 再延后 50ms，避免 T:700 和 T:703 嵌套；同样挂 ESTOP_TOKEN
        position.tilt?.let { tilt ->
            handler.postAtTime({
                if (!isStopped) send("""{"T":703,"angle":$tilt}""")
            }, ESTOP_TOKEN, now + 100)
        }
    }

    // 直接移动（T:120 无最短路径，用于安全折叠等需要确定路径的场景）
    fun moveDirectTo(position: ArmPosition, speed: Int = 600, acc: Int = 20) {
        send("""{"T":120,"base":${position.b},"shoulder":${position.s},"elbow":${position.e},"hand":${position.t},"spd":$speed,"acc":$acc}""")
    }

    // 急停：设 stop latch → 清定时任务 → forced 发 T:0
    // 不发 T:210，不做 safe-position 定时器
    // 恢复只能通过 resetStop()（当前未接入任何 UI）
    fun emergencyStop() {
        isStopped = true
        handler.removeCallbacksAndMessages(ESTOP_TOKEN)
        sendForced("""{"T":0}""")
    }

    // 显式解除 stop latch — 当前不接线到任何 UI / 自动逻辑
    // 未来应在收到固件 SAFE_REACHED 回报后才允许调用
    fun resetStop() {
        isStopped = false
    }

    // 测试恢复入口：先通知固件清除 emergency flag，再解除本地 stop latch
    // 未来正式版本应由 SAFE_REACHED / Resume 流程统一调用
    fun releaseEmergencyStop(): Boolean {
        if (connection.connectionState.value != ConnectionState.CONNECTED) {
            return false
        }
        sendForced("""{"T":999}""")
        resetStop()
        return true
    }

    // 读取当前关节反馈（T:105 → ESP32 返回 T:1051）
    fun readFeedback() {
        send("""{"T":105}""")
    }

    // 扭矩开关
    fun setTorque(enabled: Boolean) {
        val cmd = if (enabled) 1 else 0
        send("""{"T":210,"cmd":$cmd}""")
    }

    // Phone Roll 扭矩开关
    fun setRollTorque(enabled: Boolean) {
        val cmd = if (enabled) 1 else 0
        send("""{"T":702,"cmd":$cmd}""")
    }

    // Phone 横竖屏模式 — 有 baseline 时用 T:700 绝对角度，无 baseline 时回退到 T:701
    fun setPhoneMode(landscape: Boolean) {
        val baseline = SessionBaseline.rollDeg
        if (baseline != null) {
            val target = if (landscape) baseline + SessionBaseline.LANDSCAPE_OFFSET_DEG else baseline
            sendRollAbsolute(target)
        } else {
            val mode = if (landscape) "landscape" else "portrait"
            send("""{"T":701,"mode":"$mode"}""")
        }
    }

    // 当前生效的 IK 参数（连接时下发给固件，标定后可更新）
    var currentIkParams: com.example.adaptapp.model.IkParams = com.example.adaptapp.model.IkParams.DEFAULT

    // 下发 IK 参数到固件（T:116）
    fun sendIkParams(params: com.example.adaptapp.model.IkParams = currentIkParams) {
        send("""{"T":116,"l2A":${params.l2A},"l2B":${params.l2B},"l3A":${params.l3A},"l3B":${params.l3B}}""")
    }

    // 笛卡尔绝对坐标控制（T:104）— 供 StepAdjustmentController 使用
    fun sendCartesianGoal(x: Double, y: Double, z: Double, t: Double, spd: Double = 0.25) {
        send("""{"T":104,"x":$x,"y":$y,"z":$z,"t":$t,"spd":$spd}""")
    }

    // Roll 绝对角度控制（T:700）— 自动 wrap 到 0-360
    fun sendRollAbsolute(angleDeg: Double) {
        val wrapped = ((angleDeg % 360.0) + 360.0) % 360.0
        send("""{"T":700,"angle":$wrapped}""")
    }

    // Tilt 绝对角度控制（T:703）
    fun sendTiltAbsolute(angleDeg: Double) {
        send("""{"T":703,"angle":$angleDeg}""")
    }

    // 安全折叠序列：先移到 torque closed 位置，再松扭矩
    fun foldAndRelease() {
        val torqueClosed = ArmPosition(
            name = "torque closed",
            b = 0.058, s = -0.060, e = 1.580, t = 3.137
        )
        moveDirectTo(torqueClosed)
        // 注意：实际使用时需要等机械臂到位后再松扭矩
        // 这里只发移动命令，松扭矩由 UI 层在确认到位后调用 setTorque(false)
    }

    // 解析 T:1051 反馈 JSON 为 ArmPosition
    companion object {
        private val ESTOP_TOKEN = Object()
        fun parseFeedback(json: String): ArmPosition? {
            return try {
                val obj = JSONObject(json)
                if (obj.optInt("T") != 1051) return null
                ArmPosition(
                    name = "",
                    b = obj.getDouble("b"),
                    s = obj.getDouble("s"),
                    e = obj.getDouble("e"),
                    t = obj.getDouble("t"),
                    p = obj.optDouble("p").takeIf { !it.isNaN() },
                    tilt = obj.optDouble("tilt").takeIf { !it.isNaN() }
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // 可被 emergencyStop 取消的延迟任务
    // 其他 Controller 应优先使用此方法，而不是自己的 handler.postDelayed
    fun scheduleCancellable(delayMs: Long, action: () -> Unit) {
        handler.postAtTime({
            if (!isStopped) action()
        }, ESTOP_TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    private fun send(json: String) {
        if (isStopped) return
        if (connection.connectionState.value == ConnectionState.CONNECTED) {
            connection.send(json)
        }
    }

    // 仅供 emergencyStop() 内部使用，绕过 stop latch
    private fun sendForced(json: String) {
        if (connection.connectionState.value == ConnectionState.CONNECTED) {
            connection.send(json)
        }
    }
}
