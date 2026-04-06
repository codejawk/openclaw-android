package com.openclaw.native_app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VoiceManager
 *
 * Manages continuous voice input with wake-word detection ("Hey OpenClaw")
 * and TTS playback of gateway responses.
 *
 * Uses Android SpeechRecognizer (online by default, falls back to local if offline).
 * TTS uses Android's built-in TextToSpeech engine — no ElevenLabs key required.
 * Optional: set elevenLabsKey to use ElevenLabs for higher quality voice output.
 */
@Singleton
class VoiceManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG        = "VoiceManager"
        private const val WAKE_PHRASE = "hey openclaw"
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening   = false
    private var wakeWordMode  = true   // true = wait for wake phrase; false = full capture mode

    var authToken       = ""
    var elevenLabsKey   = ""   // Optional — leave empty for system TTS
    var enabled         = false
        set(value) { field = value; if (value) startListening() else stopListening() }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ──────────────────────────────────────────────────────────────

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                Log.i(TAG, "TTS initialized")
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
        }
    }

    fun destroy() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        tts = null
        scope.cancel()
    }

    fun speak(text: String) {
        if (elevenLabsKey.isNotEmpty()) {
            speakElevenLabs(text)
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "oc_${System.currentTimeMillis()}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Recognition

    private fun startListening() {
        if (!enabled || isListening) return
        isListening = true
        wakeWordMode = true
        createAndStartRecognizer()
    }

    private fun stopListening() {
        isListening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun createAndStartRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text    = matches?.firstOrNull()?.lowercase() ?: ""
                    Log.d(TAG, "Heard: $text")

                    when {
                        wakeWordMode && text.contains(WAKE_PHRASE) -> {
                            Log.i(TAG, "Wake word detected!")
                            speak("Yes?")
                            wakeWordMode = false
                            restartRecognizer()
                        }
                        !wakeWordMode -> {
                            val userInput = text.removePrefix(WAKE_PHRASE).trim()
                            if (userInput.isNotEmpty()) {
                                scope.launch { sendToGateway(userInput) }
                            }
                            wakeWordMode = true
                            restartRecognizer()
                        }
                        else -> restartRecognizer()
                    }
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error: $error")
                    if (enabled) restartRecognizer()
                }

                override fun onReadyForSpeech(params: Bundle?)         {}
                override fun onBeginningOfSpeech()                     {}
                override fun onRmsChanged(rmsdB: Float)                {}
                override fun onBufferReceived(buffer: ByteArray?)      {}
                override fun onEndOfSpeech()                           {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?)  {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,  RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,        Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,     3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    private fun restartRecognizer() {
        if (!enabled || !isListening) return
        scope.launch {
            delay(300)
            recognizer?.destroy()
            recognizer = null
            createAndStartRecognizer()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Gateway interaction

    private suspend fun sendToGateway(userText: String) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Sending to gateway: $userText")
        try {
            val body = JSONObject().apply {
                put("message", userText)
                put("channel", "voice")
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("http://127.0.0.1:${BootstrapManager.GATEWAY_PORT}/api/chat")
                .addHeader("Authorization", "Bearer $authToken")
                .post(body)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val reply = json.optString("response", json.optString("content", ""))
                if (reply.isNotEmpty()) {
                    withContext(Dispatchers.Main) { speak(reply) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gateway send failed: ${e.message}")
            withContext(Dispatchers.Main) { speak("Sorry, I couldn't reach the gateway.") }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ElevenLabs TTS (optional)

    private fun speakElevenLabs(text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("text", text)
                    put("model_id", "eleven_monolingual_v1")
                    put("voice_settings", JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.5)
                    })
                }.toString().toRequestBody("application/json".toMediaType())

                val req = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM")
                    .addHeader("xi-api-key", elevenLabsKey)
                    .addHeader("Accept", "audio/mpeg")
                    .post(body)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                        return@use
                    }
                    val audioBytes = resp.body?.bytes() ?: return@use
                    val tmp = java.io.File.createTempFile("oc_tts_", ".mp3", context.cacheDir)
                    tmp.writeBytes(audioBytes)
                    val mp = android.media.MediaPlayer().apply {
                        setDataSource(tmp.absolutePath)
                        prepare()
                        start()
                        setOnCompletionListener { tmp.delete() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ElevenLabs TTS failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
    }
}
