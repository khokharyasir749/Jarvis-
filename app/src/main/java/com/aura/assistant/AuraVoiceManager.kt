package com.aura.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class AuraVoiceManager(
    private val context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onError: (Int, String) -> Unit,
    private val onPartialSpeechResult: ((String) -> Unit)? = null,
    private val onReadyForSpeech: (() -> Unit)? = null,
    private val onBeginningOfSpeech: (() -> Unit)? = null
) : RecognitionListener {

    private val tag = "AuraVoiceManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var voskSpeechService: SpeechService? = null
    private var isListening = false
    private var hasBeganSpeech = false

    private val ttsManager = AuraTTSManager.getInstance(context)

    init {
        mainHandler.post {
            try {
                ensureModelLoaded()
            } catch (e: Exception) {
                Log.e("JarvisCrash", "Caught ensureModelLoaded in init: ${e.message}", e)
            }
        }
    }

    private fun ensureModelLoaded(onReady: (() -> Unit)? = null) {
        if (AuraBackgroundService.sharedVoskModel != null) {
            Log.d(tag, "Shared Vosk model already loaded in memory!")
            onReady?.invoke()
            return
        }

        try {
            Log.d(tag, "Unpacking Vosk offline model-en-us for AuraVoiceManager...")
            StorageService.unpack(context, "model-en-us", "model",
                { model: Model ->
                    Log.d(tag, "Vosk Model unpacked successfully for AuraVoiceManager!")
                    AuraBackgroundService.sharedVoskModel = model
                    mainHandler.post {
                        onReady?.invoke()
                    }
                },
                { exception: IOException ->
                    Log.e(tag, "Failed to unpack Vosk model: ${exception.localizedMessage}")
                    mainHandler.post {
                        onError(1, "Failed to load offline speech model.")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "Exception unpacking Vosk model: ${e.localizedMessage}")
            onError(1, "Exception loading offline speech model.")
        }
    }

    /**
     * Starts Vosk offline speech recognition using open vocabulary model for maximum recognition accuracy.
     */
    fun startListening() {
        mainHandler.post {
            try {
                stopListeningInternal()

                ensureModelLoaded {
                    val model = AuraBackgroundService.sharedVoskModel ?: return@ensureModelLoaded
                    try {
                        Log.d(tag, "Starting Vosk SpeechService with open acoustic vocabulary...")
                        val recognizer = Recognizer(model, 16000.0f)
                        voskSpeechService = SpeechService(recognizer, 16000.0f).apply {
                            startListening(this@AuraVoiceManager)
                        }
                        isListening = true
                        hasBeganSpeech = false
                        Log.d("JarvisAudio", "Vosk SpeechService open vocabulary listening active")
                        onReadyForSpeech?.invoke()
                    } catch (e: Exception) {
                        Log.e("JarvisCrash", "Error starting Vosk SpeechService: ${e.message}", e)
                        onError(1, "Error starting voice recognition: ${e.localizedMessage}")
                    }
                }
            } catch (e: Exception) {
                Log.e("JarvisCrash", "Error in startListening: ${e.message}", e)
            }
        }
    }

    /**
     * Stops Vosk speech recognition.
     */
    fun stopListening() {
        mainHandler.post {
            stopListeningInternal()
        }
    }

    private fun stopListeningInternal() {
        try {
            voskSpeechService?.stop()
            voskSpeechService?.shutdown()
            voskSpeechService = null
            isListening = false
            hasBeganSpeech = false
            Log.d(tag, "Vosk SpeechService stopped cleanly")
        } catch (e: Exception) {
            Log.w(tag, "Exception stopping Vosk SpeechService: ${e.message}")
        }
    }

    /**
     * Converts text response to speech asynchronously using persistent singleton AuraTTSManager.
     *
     * @param text Speech string to articulate.
     * @param onDone Callback invoked when TTS finishes speaking.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        ttsManager.speak(text, onDone)
    }

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = parseVoskPartialJson(hypothesis)
        if (text.isNotBlank() && text != "[unk]") {
            Log.d("AuraSpeech", "Partial: $text")
            if (!hasBeganSpeech) {
                hasBeganSpeech = true
                mainHandler.post {
                    onBeginningOfSpeech?.invoke()
                }
            }
            mainHandler.post {
                onPartialSpeechResult?.invoke(text)
            }
        }
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = parseVoskResultJson(hypothesis)
        if (text.isNotBlank() && text != "[unk]") {
            Log.d("AuraSpeech", "Recognized: $text")
            stopListeningInternal()
            mainHandler.post {
                Log.e("JarvisCapture", "Vosk Recognized Text: '$text'")
                onSpeechResult(text)
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = parseVoskResultJson(hypothesis)
        if (text.isNotBlank() && text != "[unk]") {
            Log.d("AuraSpeech", "Recognized Final: $text")
            stopListeningInternal()
            mainHandler.post {
                Log.e("JarvisCapture", "Vosk Final Recognized Text: '$text'")
                onSpeechResult(text)
            }
        }
    }

    override fun onError(exception: Exception?) {
        Log.e("JarvisAudio", "Vosk onError: ${exception?.localizedMessage}")
        stopListeningInternal()
        mainHandler.post {
            onError(1, exception?.localizedMessage ?: "Voice recognition error")
        }
    }

    override fun onTimeout() {
        Log.d("JarvisAudio", "Vosk onTimeout")
    }

    private fun parseVoskPartialJson(json: String): String {
        return try {
            val jsonObject = JSONObject(json)
            if (jsonObject.has("partial")) {
                jsonObject.getString("partial").trim()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseVoskResultJson(json: String): String {
        return try {
            val jsonObject = JSONObject(json)
            if (jsonObject.has("text")) {
                jsonObject.getString("text").trim()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Cleanly shuts down SpeechService to prevent memory leaks.
     */
    fun destroy() {
        mainHandler.post {
            stopListeningInternal()
        }
    }
}
