package com.example.adaptapp.repository

import android.content.Context
import com.example.adaptapp.model.ArmPosition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class UsageRecord(val name: String, val time: Long)

// 位置持久化存储（SharedPreferences + JSON）
class PositionRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "adapt_positions"
        private const val KEY_POSITIONS = "saved_positions"
        private const val KEY_USAGE_HISTORY = "usage_history"
        private const val MAX_HISTORY = 20
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // 读取所有保存的位置
    fun getAll(): List<ArmPosition> {
        val json = prefs.getString(KEY_POSITIONS, null) ?: return emptyList()
        val type = object : TypeToken<List<ArmPosition>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 保存一个位置（同名覆盖）
    fun save(position: ArmPosition) {
        val positions = getAll().toMutableList()
        positions.removeAll { it.name == position.name }
        positions.add(position)
        writeAll(positions)
    }

    // 删除一个位置
    fun delete(name: String) {
        val positions = getAll().toMutableList()
        positions.removeAll { it.name == name }
        writeAll(positions)
    }

    // 重命名
    fun rename(oldName: String, newName: String) {
        val positions = getAll().toMutableList()
        val index = positions.indexOfFirst { it.name == oldName }
        if (index >= 0) {
            positions[index] = positions[index].copy(name = newName)
            writeAll(positions)
        }
    }

    // 写入全部位置
    private fun writeAll(positions: List<ArmPosition>) {
        prefs.edit().putString(KEY_POSITIONS, gson.toJson(positions)).apply()
    }

    // 记录一次位置使用
    fun recordUsage(name: String) {
        val history = getUsageHistory().toMutableList()
        history.removeAll { it.name == name }
        history.add(0, UsageRecord(name, System.currentTimeMillis()))
        if (history.size > MAX_HISTORY) history.subList(MAX_HISTORY, history.size).clear()
        prefs.edit().putString(KEY_USAGE_HISTORY, gson.toJson(history)).apply()
    }

    // 返回最近使用的位置名列表（按时间倒序，排除已删除的位置）
    fun getRecents(): List<String> {
        val savedNames = getAll().map { it.name }.toSet()
        return getUsageHistory().map { it.name }.filter { it in savedNames }
    }

    // 返回最近一次使用的位置名，如果已被删除则返回 null
    fun getCurrentPosition(): String? {
        val first = getUsageHistory().firstOrNull() ?: return null
        return if (getAll().any { it.name == first.name }) first.name else null
    }

    private fun getUsageHistory(): List<UsageRecord> {
        val json = prefs.getString(KEY_USAGE_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<UsageRecord>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { emptyList() }
    }
}
