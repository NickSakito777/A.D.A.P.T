package com.example.adaptapp.voice

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.Locale

class VoskStopDetector {

    companion object {
        private const val TAG = "VoskStopDetector"
        private const val SAMPLE_RATE = 16000f
        private const val TRIGGER_PHRASE = "stop abort"
        private const val GRAMMAR = """["stop abort", "[unk]"]"""
        private const val COOLDOWN_MS = 1200L
        private const val MODEL_ASSET_DIR = "model-en-us"
        private const val MIN_WORD_CONFIDENCE = 0.85
    }

    var onStopDetected: (() -> Unit)? = null

    @Volatile private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var lastDetectionTime = 0L
    private var lastLoggedPartialText = ""
    private var lastLoggedFinalText = ""
    @Volatile private var suppressUntilMs = 0L

    fun initialize(context: Context) {
        StorageService.unpack(
            context,
            MODEL_ASSET_DIR,
            "model-en-us",
            { model ->
                Log.i(TAG, "Model unpacked, creating recognizer")
                this.model = model
                val configuredRecognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR).apply {
                    setWords(true)
                }
                recognizer = configuredRecognizer
                Log.i(TAG, "Recognizer ready (words=true)")
            },
            { e ->
                Log.e(TAG, "Model unpack failed: ${e.message}")
            }
        )
    }

    fun feedAudio(buffer: ShortArray, count: Int) {
        val rec = recognizer ?: return
        if (rec.acceptWaveForm(buffer, count)) {
            checkFinalResult(rec.result)
        } else {
            logPartialResult(rec.partialResult)
        }
    }

    private fun logPartialResult(json: String) {
        try {
            val text = normalizePhrase(JSONObject(json).optString("partial", ""))
            if (text.isEmpty()) return
            if (text != lastLoggedPartialText) {
                lastLoggedPartialText = text
                Log.i(TAG, "Heard emergency partial: '$text'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Partial JSON parse error: ${e.message}")
        }
    }

    private fun checkFinalResult(json: String) {
        try {
            val obj = JSONObject(json)
            val text = normalizePhrase(obj.optString("text", ""))

            if (text.isNotEmpty() && text != lastLoggedFinalText) {
                lastLoggedFinalText = text
                Log.i(TAG, "Heard emergency final: '$text'")
            }

            if (text != TRIGGER_PHRASE) return

            if (!hasConfidentExactMatch(obj.optJSONArray("result"))) {
                Log.i(TAG, "Emergency final rejected by per-word confidence: $json")
                return
            }

            val now = System.currentTimeMillis()
            if (now < suppressUntilMs) {
                Log.i(TAG, "Emergency final suppressed by resume window: $json")
            } else if (now - lastDetectionTime >= COOLDOWN_MS) {
                lastDetectionTime = now
                Log.i(TAG, "Emergency phrase detected (final): $json")
                onStopDetected?.invoke()
            } else {
                Log.i(TAG, "Emergency final suppressed by cooldown: $json")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Final JSON parse error: ${e.message}")
        }
    }

    private fun hasConfidentExactMatch(words: JSONArray?): Boolean {
        if (words == null) return false

        val expectedWords = TRIGGER_PHRASE.split(" ")
        if (words.length() != expectedWords.size) return false

        for (i in expectedWords.indices) {
            val wordObj = words.optJSONObject(i) ?: return false
            val word = normalizePhrase(wordObj.optString("word", ""))
            val conf = wordObj.optDouble("conf", Double.NaN)

            if (word != expectedWords[i] || conf.isNaN() || conf < MIN_WORD_CONFIDENCE) {
                Log.i(
                    TAG,
                    "Rejecting word[$i]: expected='${expectedWords[i]}', actual='$word', conf=$conf"
                )
                return false
            }
        }

        return true
    }

    private fun normalizePhrase(text: String): String {
        return text
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")
    }

    fun reset() {
        recognizer?.reset()
        lastDetectionTime = 0L
        lastLoggedPartialText = ""
        lastLoggedFinalText = ""
    }

    fun suppressFor(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        suppressUntilMs = maxOf(suppressUntilMs, until)
        Log.i(TAG, "Suppressing emergency phrase detection for ${durationMs}ms")
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }
}
