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
        private const val COOLDOWN_MS = 1200L
        private const val MODEL_ASSET_DIR = "model-en-us"
    }

    var onStopDetected: (() -> Unit)? = null

    @Volatile private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var lastDetectionTime = 0L
    private var lastLoggedText = ""
    @Volatile private var suppressUntilMs = 0L

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
            checkResult(rec.result, "final")
        } else {
            checkResult(rec.partialResult, "partial")
        }
    }

    private fun checkResult(json: String, source: String) {
        try {
            val obj = JSONObject(json)
            val text = obj.optString("partial", obj.optString("text", "")).trim()
            if (text.isNotEmpty() && text != lastLoggedText) {
                lastLoggedText = text
                Log.i(TAG, "Heard stop candidate ($source): '$text'")
            }
            if (text.contains("stop", ignoreCase = true)) {
                val now = System.currentTimeMillis()
                if (now < suppressUntilMs) {
                    Log.i(TAG, "Stop candidate suppressed by resume window ($source): $json")
                } else if (now - lastDetectionTime >= COOLDOWN_MS) {
                    lastDetectionTime = now
                    Log.i(TAG, "Stop detected ($source): $json")
                    onStopDetected?.invoke()
                } else {
                    Log.i(TAG, "Stop candidate suppressed by cooldown ($source): $json")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error: ${e.message}")
        }
    }

    fun reset() {
        recognizer?.reset()
        lastDetectionTime = 0L
        lastLoggedText = ""
    }

    fun suppressFor(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        suppressUntilMs = maxOf(suppressUntilMs, until)
        Log.i(TAG, "Suppressing stop detection for ${durationMs}ms")
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }
}
