package com.aura.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AuraTTSManager private constructor(context: Context) : TextToSpeech.OnInitListener {

    private val tag = "AuraTTS"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        mainHandler.post {
            try {
                Log.d(tag, "Initializing persistent singleton TextToSpeech engine...")
                tts = TextToSpeech(appContext, this)
            } catch (e: Exception) {
                Log.e("JarvisCrash", "Error instantiating TextToSpeech: ${e.message}", e)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallbackResult = tts?.setLanguage(Locale.US)
                isTtsReady = (fallbackResult != TextToSpeech.LANG_MISSING_DATA && fallbackResult != TextToSpeech.LANG_NOT_SUPPORTED)
            } else {
                isTtsReady = true
            }
            Log.d(tag, "Persistent Singleton TTS initialized: isTtsReady=$isTtsReady")
        } else {
            isTtsReady = false
            Log.e(tag, "TextToSpeech initialization failed with status: $status")
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            mainHandler.post {
                try {
                    onDone?.invoke()
                } catch (e: Exception) {
                    Log.e("JarvisCrash", "Caught in blank TTS onDone: ${e.message}", e)
                }
            }
            return
        }

        mainHandler.post {
            try {
                if (!isTtsReady || tts == null) {
                    Log.w(tag, "TTS not ready yet, invoking onDone fallback...")
                    onDone?.invoke()
                    return@post
                }

                val utteranceId = "aura_tts_${System.currentTimeMillis()}"

                if (onDone != null) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(tag, "TTS onStart utteranceId=$utteranceId")
                        }

                        override fun onDone(id: String?) {
                            if (id == utteranceId) {
                                mainHandler.post {
                                    try {
                                        Log.d(tag, "TTS onDone utteranceId=$id -> Invoking onDone callback on MainThread")
                                        onDone()
                                    } catch (e: Exception) {
                                        Log.e("JarvisCrash", "Caught in TTS onDone mainHandler: ${e.message}", e)
                                    }
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) {
                                mainHandler.post {
                                    try {
                                        Log.w(tag, "TTS onError utteranceId=$id -> Invoking onDone callback fallback")
                                        onDone()
                                    } catch (e: Exception) {
                                        Log.e("JarvisCrash", "Caught in TTS onError mainHandler: ${e.message}", e)
                                    }
                                }
                            }
                        }
                    })
                } else {
                    tts?.setOnUtteranceProgressListener(null)
                }

                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } catch (e: Exception) {
                Log.e("JarvisCrash", "TTS speak exception: ${e.message}", e)
                mainHandler.post {
                    try {
                        onDone?.invoke()
                    } catch (ex: Exception) {
                        Log.e("JarvisCrash", "Caught in TTS speak catch block: ${ex.message}", ex)
                    }
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: AuraTTSManager? = null

        fun getInstance(context: Context): AuraTTSManager {
            return instance ?: synchronized(this) {
                instance ?: AuraTTSManager(context).also { instance = it }
            }
        }
    }
}
