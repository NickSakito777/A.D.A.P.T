package com.example.adaptapp.connection

import kotlinx.coroutines.flow.StateFlow

// 连接状态
enum class ConnectionState {
    DISCONNECTED,  // 未连接
    CONNECTING,    // 连接中
    CONNECTED      // 已连接
}

// 连接模式：USB 有线 / 蓝牙
enum class ConnectionMode {
    USB,
    BLUETOOTH
}

// 统一连接接口 — USB 和蓝牙各自实现
interface ConnectionManager {

    // 当前连接状态（可被 Compose UI 观察）
    val connectionState: StateFlow<ConnectionState>

    // 连接到 ESP32
    fun connect()

    // 断开连接
    fun disconnect()

    // 发送 JSON 命令（如 {"T":105}）
    fun send(json: String)

    // 多订阅接收回调：key 唯一标识订阅者，同一 key 重复调用会覆盖
    fun addOnReceiveListener(key: String, callback: (String) -> Unit)

    // 移除指定订阅者
    fun removeOnReceiveListener(key: String)
}
