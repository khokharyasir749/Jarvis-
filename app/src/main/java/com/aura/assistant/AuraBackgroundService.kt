package com.aura.assistant

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale

class AuraBackgroundService : Service(), org.vosk.android.RecognitionListener {

    private val tag = "AuraBgService"
    private val channelId = "aura_background_channel"
    private val headsUpChannelId = "aura_heads_up_channel"
    private val notificationId = 1001

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Vosk Offline Wake-Word Recognizer
    private var voskModel: Model? = null
    private var voskSpeechService: SpeechService? = null
    private var isVoskListening = false

    // Native SpeechRecognizer Fallback
    private var nativeRecognizer: SpeechRecognizer? = null
    private var nativeIntent: Intent? = null
    private var isNativeListening = false

    private var lastWakeTriggerTimestamp = 0L

    // WindowManager Floating Visual Assistant Orb
    private var windowManager: WindowManager? = null
    private var floatingOrbView: FrameLayout? = null

    private var partialWakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "AuraBackgroundService onCreate")
        registerActiveService(this)

        acquirePartialWakeLock()

        createNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(notificationId, notification)

        // Show interactive floating visual assistant orb
        showFloatingOrbOverlay()

        // Check if hands-free setting is enabled before starting audio listener
        if (isHandsFreeEnabled()) {
            initVoskOfflineRecognizer()
        } else {
            Log.d(tag, "Hands-Free mode is disabled in settings. Skipping background listener.")
        }
    }

    private fun acquirePartialWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            partialWakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "Aura:PartialWakeLock"
            )?.apply {
                acquire(10 * 60 * 1000L) // 10 minutes partial CPU wake lock for background hotword listening
            }
            Log.d(tag, "Aura CPU Partial WakeLock acquired for screen-off hotword listening")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire partial wake lock: ${e.localizedMessage}")
        }
    }

    private fun releasePartialWakeLock() {
        try {
            if (partialWakeLock?.isHeld == true) {
                partialWakeLock?.release()
            }
            partialWakeLock = null
        } catch (_: Exception) {}
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (powerManager != null && !powerManager.isInteractive) {
                @Suppress("DEPRECATION")
                val screenWakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Aura:ScreenWakeLock"
                )
                screenWakeLock.acquire(3000L) // Hold for 3s to light up screen
                Log.d(tag, "Screen lit up via SCREEN_BRIGHT_WAKE_LOCK")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error turning screen on: ${e.localizedMessage}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "AuraBackgroundService onStartCommand")
        showFloatingOrbOverlay()

        if (isHandsFreeEnabled()) {
            if (voskSpeechService == null && !isVoskListening && !isNativeListening) {
                initVoskOfflineRecognizer()
            }
        } else {
            Log.d(tag, "Hands-Free mode is OFF. Stopping background audio listeners.")
            pauseBackgroundListeners()
        }
        return START_STICKY
    }

    private fun isHandsFreeEnabled(): Boolean {
        val prefs = getSharedPreferences("aura_prefs", MODE_PRIVATE)
        return prefs.getBoolean("bg_mode_enabled", false)
    }

    private fun initVoskOfflineRecognizer() {
        if (!isHandsFreeEnabled()) {
            Log.d(tag, "isHandsFreeEnabled() is false. Not initializing Vosk.")
            pauseBackgroundListeners()
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.e(tag, "RECORD_AUDIO permission missing! Skipping wake-word initialization.")
            return
        }

        serviceScope.launch {
            try {
                Log.d(tag, "Unpacking Vosk offline model-en-us assets...")
                StorageService.unpack(this@AuraBackgroundService, "model-en-us", "model",
                    { model: Model ->
                        Log.d(tag, "Vosk Model unpacked successfully!")
                        voskModel = model
                        sharedVoskModel = model
                        if (isHandsFreeEnabled()) {
                            startVoskListeningService()
                        }
                    },
                    { exception: IOException ->
                        Log.e(tag, "Vosk asset unpack failed: ${exception.localizedMessage}. Starting fallback listener...")
                        if (isHandsFreeEnabled()) {
                            initNativeFallbackRecognizer()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(tag, "Exception during Vosk initialization: ${e.localizedMessage}. Starting fallback listener...")
                if (isHandsFreeEnabled()) {
                    initNativeFallbackRecognizer()
                }
            }
        }
    }

    private fun startVoskListeningService() {
        if (!isHandsFreeEnabled()) return

        mainHandler.post {
            try {
                val model = voskModel ?: return@post
                if (isDialogShowing || isDialogActive || !isHandsFreeEnabled()) return@post

                val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) return@post

                // JARVIS WAKE-WORD GRAMMAR ONLY: "wake up jarvis", "jarvis", "hey jarvis"
                val grammar = "[\"wake up jarvis\", \"jarvis\", \"hey jarvis\", \"[unk]\"]"
                val recognizer = Recognizer(model, 16000.0f, grammar)

                voskSpeechService = SpeechService(recognizer, 16000.0f).apply {
                    startListening(this@AuraBackgroundService)
                }

                isVoskListening = true
                Log.d(tag, "Strict Vosk Wake-Word Listener Active for 'Hey Jarvis'")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start Vosk SpeechService: ${e.localizedMessage}")
                initNativeFallbackRecognizer()
            }
        }
    }

    private fun initNativeFallbackRecognizer() {
        if (!isHandsFreeEnabled()) return

        mainHandler.post {
            if (isDialogShowing || isDialogActive || isNativeListening || !isHandsFreeEnabled()) return@post
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    nativeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                        setRecognitionListener(createNativeRecognitionListener())
                    }
                    nativeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    }
                    startNativeFallbackListening()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to init native fallback recognizer: ${e.localizedMessage}")
            }
        }
    }

    private fun startNativeFallbackListening() {
        if (isDialogShowing || isDialogActive || !isHandsFreeEnabled()) return
        mainHandler.post {
            try {
                isNativeListening = true
                nativeRecognizer?.startListening(nativeIntent)
                Log.d(tag, "Native fallback keyword listener active")
            } catch (e: Exception) {
                Log.e(tag, "Error starting native fallback listener: ${e.localizedMessage}")
                isNativeListening = false
                restartNativeFallbackWithDelay(3000L)
            }
        }
    }

    private fun restartNativeFallbackWithDelay(delayMs: Long) {
        isNativeListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isDialogShowing && !isDialogActive && isHandsFreeEnabled()) {
                startNativeFallbackListening()
            }
        }, delayMs.coerceAtLeast(2500L))
    }

    private fun createNativeRecognitionListener(): android.speech.RecognitionListener {
        return object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.d(tag, "Native fallback listener onError $error. Retry in 3s...")
                restartNativeFallbackWithDelay(3000L)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                if (text.isNotBlank()) checkWakeWord(text)
            }

            override fun onResults(results: Bundle?) {
                isNativeListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                if (text.isNotBlank()) checkWakeWord(text)
                restartNativeFallbackWithDelay(3000L)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = parseVoskJsonText(hypothesis)
        if (text.isNotBlank()) checkWakeWord(text)
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = parseVoskJsonText(hypothesis)
        if (text.isNotBlank()) checkWakeWord(text)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = parseVoskJsonText(hypothesis)
        if (text.isNotBlank()) checkWakeWord(text)
    }

    override fun onError(exception: Exception?) {
        Log.e(tag, "Vosk Listener onError: ${exception?.localizedMessage}")
        isVoskListening = false
    }

    override fun onTimeout() {
        Log.d(tag, "Vosk Listener timeout reset")
    }

    private fun checkWakeWord(text: String) {
        if (!isHandsFreeEnabled() || isDialogShowing || isDialogActive) {
            Log.d(tag, "Hands-Free setting is OFF, Dialog showing ($isDialogShowing), or active ($isDialogActive). Ignoring background input.")
            pauseBackgroundListeners()
            return
        }

        val lower = text.lowercase().trim()
        Log.d("VoskListener", "Checking recognized text for JARVIS hotword: '$lower'")

        // JARVIS HOTWORD MATCHING: "wake up jarvis", "jarvis", "hey jarvis"
        val isStrictMatch = lower.contains("jarvis") || lower.contains("hey jarvis") || lower.contains("wake up jarvis")

        if (isStrictMatch) {
            val now = System.currentTimeMillis()
            if (now - lastWakeTriggerTimestamp < 3000L) {
                Log.d("VoskListener", "Trigger debounced (within 3000ms window). Ignoring '$lower'")
                return
            }
            lastWakeTriggerTimestamp = now

            Log.d("VoskListener", "JARVIS HOTWORD MATCHED: '$text' -> Hard-stopping mic & launching activity")

            isDialogShowing = true
            isDialogActive = true

            // Hard-stop background mic & release AudioRecord completely BEFORE activity launch
            pauseBackgroundListeners()

            mainHandler.postDelayed({
                triggerSiriVoiceDialogSession()
            }, 200L)
        }
    }

    private fun parseVoskJsonText(json: String): String {
        return try {
            val jsonObject = JSONObject(json)
            if (jsonObject.has("partial")) {
                jsonObject.getString("partial")
            } else if (jsonObject.has("text")) {
                jsonObject.getString("text")
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun pauseBackgroundListeners() {
        try {
            voskSpeechService?.stop()
            voskSpeechService?.shutdown()
            voskSpeechService = null
            isVoskListening = false
            Log.d(tag, "Vosk background speech service stopped & mic hardware completely freed")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Vosk service: ${e.localizedMessage}")
        }
        try {
            nativeRecognizer?.cancel()
            nativeRecognizer?.destroy()
            nativeRecognizer = null
            isNativeListening = false
        } catch (_: Exception) {}
    }

    fun resumeVoskListener() {
        if (!isHandsFreeEnabled() || isDialogShowing || isDialogActive) {
            Log.d(tag, "Cannot resume Vosk listener: isHandsFreeEnabled=${isHandsFreeEnabled()}, isDialogShowing=$isDialogShowing, isDialogActive=$isDialogActive")
            return
        }
        mainHandler.post {
            if (voskSpeechService == null && !isVoskListening && !isNativeListening) {
                Log.d(tag, "Resuming Vosk background wake-word listener...")
                startVoskListeningService()
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("JARVIS AI Assistant")
            .setContentText("Tap floating orb or say 'Hey Jarvis'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 1. Low Priority Channel for Ongoing Background Service
            val bgChannel = NotificationChannel(
                channelId,
                "JARVIS Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS assistant active in background"
            }
            manager?.createNotificationChannel(bgChannel)

            // 2. High Priority Heads-Up Channel for Over-App Popups
            val headsUpChannel = NotificationChannel(
                headsUpChannelId,
                "JARVIS Assistant Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pops up JARVIS Assistant over active third-party apps"
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager?.createNotificationChannel(headsUpChannel)
        }
    }

    private fun triggerSiriVoiceDialogSession() {
        wakeUpScreen()

        // Immediately release background mic before starting dialog
        pauseBackgroundListeners()

        playAudioBeep()
        triggerVibrationFeedback()

        val intent = Intent(this, AuraVoiceDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
        }

        try {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+ / API 34
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            startActivity(intent, options.toBundle())
            Log.d(tag, "Successfully triggered isolated AuraVoiceDialogActivity via direct startActivity(options)")
        } catch (e: Exception) {
            Log.w(tag, "Direct startActivity failed (${e.localizedMessage}). Launching via Full-Screen Heads-Up Notification fallback...")
            showFullScreenHeadsUpNotification()
        }
    }

    private fun showFullScreenHeadsUpNotification() {
        val fullScreenIntent = Intent(this, AuraVoiceDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            2002,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val headsUpNotification = NotificationCompat.Builder(this, headsUpChannelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS AI Assistant")
            .setContentText("Tap or say 'Hey Jarvis'...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(2002, headsUpNotification)
        Log.d(tag, "Full-Screen Heads-Up Notification fired for over-app popup")
    }

    private fun showFloatingOrbOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(tag, "Overlay permission not granted. Skipping floating orb view.")
            return
        }
        if (floatingOrbView != null) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val orbDensitySize = (52 * resources.displayMetrics.density).toInt()
            val orbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0284C7.toInt()) // Deep Sky Blue
            }

            val orbView = FrameLayout(this).apply {
                background = orbDrawable
            }

            val params = WindowManager.LayoutParams(
                orbDensitySize,
                orbDensitySize,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (16 * resources.displayMetrics.density).toInt()
                y = (140 * resources.displayMetrics.density).toInt()
            }

            // Drag and Tap Listener for Floating Assistant Orb
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            orbView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX - (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(orbView, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 12 && diffY < 12) {
                            Log.d(tag, "Floating Orb tapped! Launching Siri-style voice dialog.")
                            triggerSiriVoiceDialogSession()
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(orbView, params)
            floatingOrbView = orbView
            Log.d(tag, "Floating visual assistant orb displayed on screen")
        } catch (e: Exception) {
            Log.e(tag, "Error showing floating orb: ${e.localizedMessage}")
        }
    }

    private fun removeFloatingOrbOverlay() {
        try {
            floatingOrbView?.let {
                windowManager?.removeView(it)
                floatingOrbView = null
            }
        } catch (_: Exception) {}
    }

    private fun playAudioBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            mainHandler.postDelayed({
                try {
                    toneGen.release()
                } catch (_: Exception) {}
            }, 350)
        } catch (e: Exception) {
            Log.e(tag, "Audio beep exception: ${e.localizedMessage}")
        }
    }

    private fun triggerVibrationFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(150)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Vibration feedback error: ${e.localizedMessage}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "AuraBackgroundService onDestroy")
        releasePartialWakeLock()
        unregisterActiveService()
        pauseBackgroundListeners()
        try {
            voskSpeechService?.stop()
            voskSpeechService?.shutdown()
            voskSpeechService = null
            voskModel?.close()
            voskModel = null
        } catch (_: Exception) {}
        try {
            nativeRecognizer?.cancel()
            nativeRecognizer?.destroy()
            nativeRecognizer = null
        } catch (_: Exception) {}
        serviceScope.cancel()
        removeFloatingOrbOverlay()
    }

    companion object {
        private var activeServiceRef: java.lang.ref.WeakReference<AuraBackgroundService>? = null

        @Volatile
        var sharedVoskModel: Model? = null

        var isDialogShowing = false
        var isDialogActive = false
            set(value) {
                field = value
                if (value) {
                    activeServiceRef?.get()?.pauseBackgroundListeners()
                } else {
                    activeServiceRef?.get()?.resumeVoskListener()
                }
            }

        fun onDialogClosed() {
            Log.d("AuraBgService", "onDialogClosed() called. Resetting flags & re-arming background wake-word listener.")
            isDialogShowing = false
            isDialogActive = false
            activeServiceRef?.get()?.resumeVoskListener()
        }

        fun pauseWakeWordListener() {
            activeServiceRef?.get()?.pauseBackgroundListeners()
        }

        fun resumeWakeWordListener() {
            if (!isDialogActive && !isDialogShowing) {
                activeServiceRef?.get()?.resumeVoskListener()
            }
        }

        fun stopBackgroundAudioListeners() {
            activeServiceRef?.get()?.pauseBackgroundListeners()
        }

        private fun registerActiveService(service: AuraBackgroundService) {
            activeServiceRef = java.lang.ref.WeakReference(service)
        }

        private fun unregisterActiveService() {
            activeServiceRef = null
        }

        fun startService(context: Context) {
            val intent = Intent(context, AuraBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AuraBackgroundService::class.java)
            context.stopService(intent)
        }
    }
}
