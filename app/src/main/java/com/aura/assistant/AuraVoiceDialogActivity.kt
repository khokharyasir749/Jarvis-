package com.aura.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class AuraVoiceDialogActivity : ComponentActivity() {

    private val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var actionManager: AuraActionManager
    private lateinit var aiEngine: AuraAIEngine
    private lateinit var jarvisBrain: AuraJarvisBrain
    private lateinit var ttsManager: AuraTTSManager

    private var nativeRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null

    private var persistentWakeLock: PowerManager.WakeLock? = null

    private var assistantState by mutableStateOf(AssistantState.LISTENING)
    private var userPrompt by mutableStateOf("Listening, Boss...")
    private var auraReply by mutableStateOf("Yes Boss, I am Jarvis and I am your personal AI assistant.")
    private var isListeningSessionActive = false
    private var hasGreeted = false // STRICT SINGLE GREETING FLAG

    private fun dismissWithStandingBy() {
        try {
            if (isFinishing || isDestroyed) return
            isListeningSessionActive = false
            stopNativeRecognizer()
            assistantState = AssistantState.IDLE
            auraReply = "Bye Boss"
            AuraBackgroundService.onDialogClosed()
            ttsManager.speak("Bye Boss") {
                mainHandler.postDelayed({
                    finishAndRemoveTask()
                }, 300L)
            }
        } catch (e: Exception) {
            Log.e("JarvisCrash", "Error in dismissWithStandingBy: ${e.message}", e)
            AuraBackgroundService.onDialogClosed()
            finishAndRemoveTask()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d("AuraVoiceDialog", "onNewIntent received. Silencing greeting speech on re-entry.")
        startListeningSafely()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuraBackgroundService.isDialogActive = true
        AuraBackgroundService.pauseWakeWordListener()

        // Persistent Non-Modal System Overlay Parameters
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setGravity(Gravity.BOTTOM)

        // 1. Physical Screen Wake Up & Lockscreen Bypass
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        }

        // Persistent Display Lock Across Apps
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            persistentWakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "Jarvis:PersistentDisplayLock"
            )?.apply {
                acquire(10 * 60 * 1000L) // Acquire 10min persistent screen wake lock
            }
            Log.d("AuraVoiceDialog", "Persistent display WakeLock acquired successfully")
        } catch (e: Exception) {
            Log.e("AuraVoiceDialog", "Error acquiring WakeLock: ${e.message}")
        }

        actionManager = AuraActionManager(this)
        aiEngine = AuraAIEngine(GEMINI_API_KEY)
        jarvisBrain = AuraJarvisBrain(aiEngine)
        ttsManager = AuraTTSManager.getInstance(this)

        // 2. Direct Android Native SpeechRecognizer Initialization
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            nativeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createNativeRecognitionListener())
            }
        } else {
            Log.e("AuraNativeSpeech", "SpeechRecognizer is NOT available on this device!")
        }

        // Extra Sensitive RecognizerIntent Thresholds
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        // 3. Bottom HUD Redesign (Siri / Folex Bottom Docking Layout)
        setContent {
            AuraTheme {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    FolexBottomDockHudContent(
                        assistantState = assistantState,
                        userPrompt = userPrompt,
                        auraReply = auraReply,
                        onMicClick = { toggleListening() },
                        onDismissClick = { dismissWithStandingBy() }
                    )
                }
            }
        }

        // 4. Initial Activation Greeting (STRICTLY ONCE PER SESSION)
        if (savedInstanceState == null && !hasGreeted) {
            hasGreeted = true
            assistantState = AssistantState.SPEAKING
            userPrompt = "Listening, Boss..."
            val greetingText = "Yes Boss, I am Jarvis and I am your personal AI assistant."
            auraReply = greetingText
            Log.d("AuraNativeSpeech", "Jarvis Initial Activation Greeting Started (Single Play)")

            ttsManager.speak(greetingText) {
                mainHandler.postDelayed({
                    startListeningSafely()
                }, 150L) // Continuous speech handover
            }
        } else {
            startListeningSafely()
        }
    }

    private fun createNativeRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("AuraNativeSpeech", "onReadyForSpeech")
                isListeningSessionActive = true
                assistantState = AssistantState.LISTENING
                userPrompt = "Listening, Boss..."
            }

            override fun onBeginningOfSpeech() {
                Log.d("AuraNativeSpeech", "onBeginningOfSpeech")
                userPrompt = "Listening..."
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d("AuraNativeSpeech", "onEndOfSpeech")
            }

            override fun onError(error: Int) {
                Log.d("AuraNativeSpeech", "onError code=$error. Immediately re-connecting without delay...")
                isListeningSessionActive = false
                if (!isFinishing && !isDestroyed) {
                    userPrompt = "Listening, Boss..."
                    assistantState = AssistantState.LISTENING
                    mainHandler.removeCallbacksAndMessages(null)
                    startListeningSafely() // Immediate auto-reconnect for max sensitivity
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull()?.trim() ?: ""
                if (partialText.isNotBlank()) {
                    userPrompt = partialText
                    Log.d("AuraNativeSpeech", "Partial Result: $partialText")
                }
            }

            override fun onResults(results: Bundle?) {
                isListeningSessionActive = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedQuery = matches?.firstOrNull()?.trim() ?: ""
                Log.e("AuraNativeSpeech", "Final Recognized Query: '$recognizedQuery'")

                if (recognizedQuery.isNotBlank()) {
                    userPrompt = recognizedQuery
                    assistantState = AssistantState.THINKING
                    executeCommand(recognizedQuery)
                } else {
                    if (!isFinishing && !isDestroyed) {
                        mainHandler.postDelayed({
                            startListeningSafely()
                        }, 200L)
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun startListeningSafely() {
        if (isFinishing || isDestroyed) return
        if (isListeningSessionActive) return

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                isListeningSessionActive = true
                assistantState = AssistantState.LISTENING
                userPrompt = "Listening, Boss..."
                Log.d("AuraNativeSpeech", "startListening called on Native SpeechRecognizer")
                nativeRecognizer?.startListening(speechIntent)
            } else {
                Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }
        } catch (e: Exception) {
            Log.e("AuraNativeSpeech", "Error in startListeningSafely: ${e.message}", e)
            isListeningSessionActive = false
        }
    }

    private fun stopNativeRecognizer() {
        try {
            nativeRecognizer?.stopListening()
            nativeRecognizer?.cancel()
            isListeningSessionActive = false
        } catch (e: Exception) {
            Log.w("AuraNativeSpeech", "Error stopping native recognizer: ${e.message}")
        }
    }

    private fun toggleListening() {
        try {
            if (isListeningSessionActive || assistantState == AssistantState.LISTENING) {
                stopNativeRecognizer()
                assistantState = AssistantState.IDLE
            } else {
                startListeningSafely()
            }
        } catch (e: Exception) {
            Log.e("AuraNativeSpeech", "Error toggling listening: ${e.message}", e)
        }
    }

    /**
     * Non-Dismissive Direct Fast-Path & Online Gemini Execution Engine for Voice Queries.
     */
    private fun executeCommand(query: String) {
        val q = query.lowercase().trim()
        Log.e("AuraEngine", "Heard text: $query | Dispatched Intent: $q")

        // 1. Explicit Session Exit Triggers ONLY
        if (q.contains("stop") || q.contains("ruko") || q.contains("band karo") || q.contains("bye") || q.contains("goodbye") || q.contains("exit")) {
            dismissWithStandingBy()
            return
        }

        // 2. Time & Date Fast-Paths
        if (q.contains("time") || q.contains("date") || q.contains("waqt") || q.contains("din")) {
            val feedback = actionManager.getCurrentTimeAndDate()
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 3. Set Alarm Fast-Path
        if (q.contains("alarm")) {
            var hour = 7
            var minute = 0

            val timePattern = Regex("(\\d{1,2})[:\\s]+(\\d{1,2})")
            val timeMatch = timePattern.find(q)
            if (timeMatch != null) {
                hour = timeMatch.groupValues[1].toIntOrNull() ?: 7
                minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
            } else {
                val hourMatch = Regex("(\\d{1,2})").find(q)
                if (hourMatch != null) {
                    hour = hourMatch.groupValues[1].toIntOrNull() ?: 7
                }
            }

            if (q.contains("pm") && hour in 1..11) {
                hour += 12
            } else if (q.contains("am") && hour == 12) {
                hour = 0
            }

            val feedback = actionManager.setAlarm(hour, minute)
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 4. WhatsApp Direct VoIP Call / Chat / Message
        if (q.contains("whatsapp call")) {
            if (!AuraAccessibilityService.isServiceEnabled(this)) {
                speakFeedbackAndResumeMic("Boss, accessibility service off hai, please on karein.")
                return
            }
            val target = extractTargetName(q, "whatsapp call")
            val feedback = actionManager.whatsappCall(target)
            speakFeedbackAndResumeMic(feedback)
            return
        }

        if (q.contains("whatsapp chat") || (q.contains("message") && q.contains("whatsapp"))) {
            if (!AuraAccessibilityService.isServiceEnabled(this)) {
                speakFeedbackAndResumeMic("Boss, accessibility service off hai, please on karein.")
                return
            }
            val target = extractTargetName(q, "whatsapp")
            val feedback = actionManager.openWhatsAppChat(target)
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 5. Direct Phone Calls & Cellular Dialer
        if (q.contains("call") || q.contains("phone") || q.contains("dial") || q.contains("milao")) {
            val target = extractTargetName(q, "call")
            val feedback = actionManager.makeDirectCall(target)
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 6. SMS Text Messages
        if (q.contains("text") || q.contains("sms")) {
            val target = extractTargetName(q, "text")
            val feedback = actionManager.sendSms(target, "Hello")
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 7. YouTube Launch Fast-Path
        if (q.contains("youtube") || q.contains("tube")) {
            val feedback = actionManager.openApp("YouTube")
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 8. WhatsApp App Launch Fast-Path
        if (q.contains("whatsapp") || q.contains("wts")) {
            val feedback = actionManager.openApp("WhatsApp")
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 9. Flashlight / Torch Toggle Fast-Path
        if (q.contains("torch") || q.contains("flash") || q.contains("light") || q.contains("batti")) {
            val enable = !q.contains("off") && !q.contains("band")
            val feedback = actionManager.toggleFlashlight(enable)
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 10. Volume Stream Adjustment Fast-Path
        if (q.contains("volume") || q.contains("awaz") || q.contains("sound")) {
            val dir = when {
                q.contains("down") || q.contains("kam") -> "down"
                q.contains("mute") -> "mute"
                else -> "up"
            }
            val feedback = actionManager.adjustVolume(dir)
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 11. Home Navigation
        if (q.contains("home") || q.contains("minimize")) {
            val feedback = actionManager.goHome()
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 12. Back Navigation
        if (q.contains("back") || q.contains("piche")) {
            val feedback = actionManager.goBack()
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 13. Close All Apps / Recents
        if (q.contains("close all") || q.contains("recent") || q.contains("clear all")) {
            val feedback = actionManager.closeAllApps()
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 14. Screen Lock
        if (q.contains("lock")) {
            val feedback = actionManager.lockScreen()
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 15. Camera Launch
        if (q.contains("camera") || q.contains("photo") || q.contains("picture")) {
            val feedback = actionManager.openCamera()
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 16. Bluetooth Toggle
        if (q.contains("bluetooth")) {
            val enable = !q.contains("off") && !q.contains("band")
            val feedback = actionManager.toggleBluetooth(enable)
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 17. Brightness Adjustment
        if (q.contains("brightness")) {
            val dir = if (q.contains("down") || q.contains("kam")) "down" else "up"
            val feedback = actionManager.adjustBrightness(dir)
            speakFeedbackAndResumeMic(feedback)
            return
        }

        // 18. General Online Query to Gemini Flash AI Engine
        processQuery(query)
    }

    private fun extractTargetName(query: String, trigger: String): String {
        return query
            .replace(Regex("(?i)^$trigger\\s+"), "")
            .replace(Regex("(?i)call\\s+"), "")
            .replace(Regex("(?i)to\\s+"), "")
            .replace(Regex("(?i)ko\\s+"), "")
            .replace(Regex("(?i)on\\s+whatsapp"), "")
            .replace(Regex("(?i)via\\s+whatsapp"), "")
            .trim()
    }

    private fun speakFeedbackAndResumeMic(feedbackText: String) {
        auraReply = feedbackText
        assistantState = AssistantState.SPEAKING
        ttsManager.speak(feedbackText) {
            mainHandler.postDelayed({
                startListeningSafely()
            }, 150L) // Chained execution mic resumption
        }
    }

    private fun processQuery(query: String) {
        lifecycleScope.launch {
            try {
                auraReply = "Thinking..."
                assistantState = AssistantState.THINKING

                // Query Gemini Flash Online Engine for Human-Like AI Response and Structured Action
                val response = JarvisOnlineEngine.instance.queryGemini(query)
                Log.d("AuraEngine", "Gemini Action Response: action=${response.action}, reply='${response.reply}', params=${response.params}")

                val target = response.params["target"] ?: ""
                val message = response.params["message"] ?: ""

                when (response.action.uppercase()) {
                    "CLOSE_ASSISTANT" -> dismissWithStandingBy()

                    "WHATSAPP_CALL" -> {
                        if (!AuraAccessibilityService.isServiceEnabled(this@AuraVoiceDialogActivity)) {
                            speakFeedbackAndResumeMic("Boss, accessibility service off hai, please on karein.")
                        } else {
                            val feedback = actionManager.whatsappCall(target)
                            speakFeedbackAndResumeMic(feedback)
                        }
                    }

                    "WHATSAPP_MESSAGE" -> {
                        if (!AuraAccessibilityService.isServiceEnabled(this@AuraVoiceDialogActivity)) {
                            speakFeedbackAndResumeMic("Boss, accessibility service off hai, please on karein.")
                        } else {
                            val feedback = actionManager.whatsappMessage("$target $message")
                            speakFeedbackAndResumeMic(feedback)
                        }
                    }

                    "WHATSAPP_CHAT" -> {
                        val feedback = actionManager.openWhatsAppChat(target)
                        speakFeedbackAndResumeMic(feedback)
                    }

                    "OPEN_APP" -> {
                        val feedback = actionManager.openApp(if (target.isNotBlank()) target else query)
                        speakFeedbackAndResumeMic(feedback)
                    }

                    "PHONE_CALL" -> {
                        val feedback = actionManager.makeDirectCall(target)
                        speakFeedbackAndResumeMic(feedback)
                    }

                    "TOGGLE_TORCH" -> {
                        val feedback = actionManager.toggleFlashlight(!target.contains("off") && !target.contains("band"))
                        speakFeedbackAndResumeMic(feedback)
                    }

                    "ADJUST_VOLUME" -> {
                        val feedback = actionManager.adjustVolume(target)
                        speakFeedbackAndResumeMic(feedback)
                    }

                    "SET_ALARM" -> {
                        var hour = 7
                        var minute = 0
                        val timeMatch = Regex("(\\d{1,2})[:\\s]+(\\d{1,2})").find(target)
                        if (timeMatch != null) {
                            hour = timeMatch.groupValues[1].toIntOrNull() ?: 7
                            minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
                        }
                        val feedback = actionManager.setAlarm(hour, minute)
                        speakFeedbackAndResumeMic(feedback)
                    }

                    "SYSTEM_HOME" -> {
                        val feedback = actionManager.goHome()
                        speakFeedbackAndResumeMic(feedback)
                    }

                    "SYSTEM_RECENTS" -> {
                        val feedback = actionManager.closeAllApps()
                        speakFeedbackAndResumeMic(feedback)
                    }

                    "SYSTEM_LOCK", "LOCK_SCREEN" -> {
                        val feedback = actionManager.lockScreen()
                        speakFeedbackAndResumeMic(feedback)
                    }

                    else -> {
                        speakFeedbackAndResumeMic(response.reply)
                    }
                }
            } catch (e: Exception) {
                Log.e("JarvisCrash", "Error in processQuery: ${e.message}", e)
                startListeningSafely()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Do NOT stop native recognizer or dismiss overlay in onPause when launching apps
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AuraNativeSpeech", "Activity onDestroy, Resuming background hotword listener")

        try {
            if (persistentWakeLock?.isHeld == true) {
                persistentWakeLock?.release()
            }
            persistentWakeLock = null
        } catch (_: Exception) {}

        AuraBackgroundService.onDialogClosed()
        stopNativeRecognizer()
        try {
            nativeRecognizer?.destroy()
            nativeRecognizer = null
        } catch (_: Exception) {}
    }
}

// ---------------------------------------------------------------------------
// Siri / Folex Bottom Docking HUD UI Component
// ---------------------------------------------------------------------------

@Composable
fun FolexBottomDockHudContent(
    assistantState: AssistantState,
    userPrompt: String,
    auraReply: String,
    onMicClick: () -> Unit,
    onDismissClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE0F172A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row with Glowing Pulse Core Orb & Status Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Compact Pulse Core Orb
                    val isAnimated = assistantState == AssistantState.LISTENING || assistantState == AssistantState.SPEAKING
                    val infiniteTransition = rememberInfiniteTransition(label = "FolexGlowTransition")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.95f,
                        targetValue = if (isAnimated) 1.25f else 0.98f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "PulseScale"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF38BDF8),
                                        Color(0xFF0284C7)
                                    )
                                )
                            )
                            .clickable { onMicClick() }
                    ) {
                        Text(
                            text = if (assistantState == AssistantState.LISTENING) "●" else "🎙",
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "JARVIS HUD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 1.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = when (assistantState) {
                                AssistantState.LISTENING -> "LISTENING..."
                                AssistantState.THINKING -> "PROCESSING..."
                                AssistantState.SPEAKING -> "EXECUTING..."
                                else -> "ACTIVE SESSION"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Minimalist Pill Dismiss Button
                IconButton(
                    onClick = onDismissClick,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF334155))
                ) {
                    Text(
                        text = "✕",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF87171)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live Transcription User Prompt
            Text(
                text = "YOU: $userPrompt",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE2E8F0),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Jarvis Response / Status Tag
            Text(
                text = "JARVIS: $auraReply",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
