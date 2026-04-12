package com.example.adaptapp.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

class VoskStopDetector {

    companion object {
        private const val TAG = "VoskStopDetector"
        private const val SAMPLE_RATE = 16000f
        private const val GRAMMAR = """["stop", "[unk]"]"""
        private const val COOLDOWN_MS = 2000L
        private const val MODEL_ASSET_DIR = "model-en-us"
    }

    var onStopDetected: (() -> Unit)? = null

    @Volatile private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var lastDetectionTime = 0L

    fun initialize(context: Context) {
        StorageService.unpack(
            context,
            MODEL_ASSET_DIR,
            "model-en-us",
            { model ->
                Log.i(TAG, "Model unpacked, creating recognizer")
                this.model = model
                recognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR)
                Log.i(TAG, "Recognizer ready")
            },
            { e ->
                Log.e(TAG, "Model unpack failed: ${e.message}")
            }
        )
    }

    fun feedAudio(buffer: ShortArray, count: Int) {
        val rec = recognizer ?: return
        if (rec.acceptWaveForm(buffer, count)) {
            // finalResult — 也检查一下
            checkResult(rec.result)
        } else {
            checkResult(rec.partialResult)
        }
    }

    private fun checkResult(json: String) {
        try {
            val obj = JSONObject(json)
            val text = obj.optString("partial", obj.optString("text", ""))
            if (text.contains("stop", ignoreCase = true)) {
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime >= COOLDOWN_MS) {
                    lastDetectionTime = now
                    Log.i(TAG, "Stop detected: $json")
                    onStopDetected?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error: ${e.message}")
        }
    }

    fun reset() {
        recognizer?.reset()
        lastDetectionTime = 0L
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }
}
