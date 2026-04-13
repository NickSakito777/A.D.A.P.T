package com.example.adaptapp.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * Minimal Vosk-based detector for "resume control" phrase.
 * Runs independently with its own AudioRecord and Recognizer.
 * Only active during stop overlay — started/stopped by MainActivity.
 */
class VoskResumeDetector(private val context: Context) {

    companion object {
        private const val TAG = "VoskResumeDetector"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280 // 80ms at 16kHz
        private const val GRAMMAR = """["resume control", "[unk]"]"""
        private const val COOLDOWN_MS = 3000L
        private const val MODEL_ASSET_DIR = "model-en-us"
    }

    var onResumeDetected: (() -> Unit)? = null

    @Volatile private var recognizer: Recognizer? = null
    private var model: Model? = null
    @Volatile private var isRunning = false
    @Volatile private var wantsStart = false
    @Volatile private var destroyed = false
    private var thread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var lastDetectionTime = 0L
    private var lastLoggedText = ""

    fun initialize() {
        destroyed = false
        StorageService.unpack(
            context,
            MODEL_ASSET_DIR,
            "model-en-us",
            { model ->
                Log.i(TAG, "Model ready, creating recognizer")
                if (destroyed) {
                    model.close()
                    return@unpack
                }
                this.model = model
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), GRAMMAR)
                Log.i(TAG, "Recognizer ready")
                if (wantsStart && !isRunning) {
                    Log.i(TAG, "Late recognizer ready -> auto-start requested")
                    start()
                }
            },
            { e ->
                Log.e(TAG, "Model unpack failed: ${e.message}")
            }
        )
    }

    fun start() {
        if (destroyed) return
        wantsStart = true
        if (isRunning) return
        if (recognizer == null) {
            Log.w(TAG, "Recognizer not ready, skipping start (will auto-start when ready)")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            return
        }

        isRunning = true
        lastDetectionTime = 0L
        lastLoggedText = ""
        recognizer?.reset()

        thread = Thread({
            initAudioRecord()
            audioLoop()
            releaseAudioRecord()
        }, "VoskResume-Audio").apply { start() }

        Log.i(TAG, "Started listening for 'resume control'")
    }

    fun stop() {
        wantsStart = false
        if (!isRunning) return
        isRunning = false
        runCatching { audioRecord?.stop() }
        thread?.join(1000)
        thread = null
        recognizer?.reset()
        Log.i(TAG, "Stopped")
    }

    fun destroy() {
        destroyed = true
        stop()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SIZE * 4)
        )
        audioRecord?.startRecording()
    }

    private fun releaseAudioRecord() {
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
    }

    private fun audioLoop() {
        val buffer = ShortArray(FRAME_SIZE)
        while (isRunning) {
            val read = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: break
            if (read != FRAME_SIZE) continue

            val rec = recognizer ?: continue
            if (rec.acceptWaveForm(buffer, read)) {
                // Only trigger on final result to avoid premature firing
                // from partial matches mid-utterance
                checkResult(rec.result)
            }
            // partialResult intentionally ignored — "resume control" is a
            // safety-critical action, require full phrase confirmation
        }
    }

    private fun checkResult(json: String) {
        try {
            val obj = JSONObject(json)
            val text = obj.optString("text", "").trim()
            if (text.isNotEmpty() && text != lastLoggedText) {
                lastLoggedText = text
                Log.i(TAG, "Heard resume final: '$text'")
            }
            if (text.equals("resume control", ignoreCase = true)) {
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime >= COOLDOWN_MS) {
                    lastDetectionTime = now
                    Log.i(TAG, "Detected 'resume control': $json")
                    onResumeDetected?.invoke()
                } else {
                    Log.i(TAG, "Resume candidate suppressed by cooldown: $json")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error: ${e.message}")
        }
    }
}
