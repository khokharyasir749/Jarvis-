package com.aura.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Assistant States
enum class AssistantState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

class MainActivity : ComponentActivity() {

    // Google Gemini API Key
    private val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY
    private val PREFS_NAME = "aura_prefs"
    private val PREF_BG_MODE = "bg_mode_enabled"

    private lateinit var actionManager: AuraActionManager
    private lateinit var voiceManager: AuraVoiceManager
    private lateinit var aiEngine: AuraAIEngine

    // UI States
    private var assistantState by mutableStateOf(AssistantState.IDLE)
    private var userPrompt by mutableStateOf("Tap the microphone button and ask Aura anything!")
    private var auraReply by mutableStateOf("I'm ready to assist you.")
    private var isBackgroundServiceEnabled by mutableStateOf(false)
    private var isAccessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Managers
        actionManager = AuraActionManager(this)
        aiEngine = AuraAIEngine(GEMINI_API_KEY)

        voiceManager = AuraVoiceManager(
            context = this,
            onSpeechResult = { recognizedText ->
                userPrompt = recognizedText
                assistantState = AssistantState.THINKING
                processUserVoiceQuery(recognizedText)
            },
            onError = { _, errorMessage ->
                assistantState = AssistantState.ERROR
                auraReply = errorMessage
            }
        )

        // Request startup permissions for Camera, Calls, Contacts & Audio
        checkAndRequestStartupPermissions()

        // Restore Background Mode Preference
        loadPreferencesAndStartServices()

        // Initial check for accessibility service
        isAccessibilityEnabled = isAccessibilityServiceEnabled(this, AuraAccessibilityService::class.java)

        // Check if launched from background service hotword trigger
        if (intent?.getBooleanExtra("EXTRA_START_LISTENING", false) == true) {
            toggleListening()
        }

        setContent {
            AuraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A) // Deep Navy Dark Mode Background
                ) {
                    AuraAssistantScreen(
                        assistantState = assistantState,
                        userPrompt = userPrompt,
                        auraReply = auraReply,
                        isBackgroundModeEnabled = isBackgroundServiceEnabled,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        onToggleBackgroundMode = { toggleBackgroundService(it) },
                        onMicClick = { checkPermissionAndStartListening() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("EXTRA_START_LISTENING", false)) {
            toggleListening()
        }
    }

    override fun onResume() {
        super.onResume()

        // Robust Auto-Refresh Accessibility Service state in onResume
        isAccessibilityEnabled = isAccessibilityServiceEnabled(this, AuraAccessibilityService::class.java)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val shouldBeEnabled = prefs.getBoolean(PREF_BG_MODE, true)
        if (shouldBeEnabled && android.provider.Settings.canDrawOverlays(this)) {
            if (!isBackgroundServiceEnabled) {
                isBackgroundServiceEnabled = true
                AuraBackgroundService.startService(this)
            }
        }
    }

    /**
     * Robust Utility Function to verify whether a given Accessibility Service is active.
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun loadPreferencesAndStartServices() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedEnabled = prefs.getBoolean(PREF_BG_MODE, true)
        if (savedEnabled && android.provider.Settings.canDrawOverlays(this)) {
            isBackgroundServiceEnabled = true
            AuraBackgroundService.startService(this)
        } else {
            isBackgroundServiceEnabled = false
        }
    }

    private fun toggleBackgroundService(enable: Boolean) {
        if (enable) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please enable 'Display over other apps' for Aura", Toast.LENGTH_LONG).show()
                val overlayIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(overlayIntent)
                return
            }

            isBackgroundServiceEnabled = true
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_BG_MODE, true).apply()
            AuraBackgroundService.startService(this)
            Toast.makeText(this, "Hands-Free 'Hey Aura' Background Mode Active", Toast.LENGTH_SHORT).show()
        } else {
            isBackgroundServiceEnabled = false
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_BG_MODE, false).apply()
            AuraBackgroundService.stopService(this)
            Toast.makeText(this, "Background Mode Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processUserVoiceQuery(query: String) {
        lifecycleScope.launch {
            val command = aiEngine.processQuery(query)
            var actionFeedback: String? = null

            // Execute local system action if applicable
            when (command.action.lowercase()) {
                "device_diagnostics" -> {
                    actionFeedback = actionManager.getDeviceDiagnostics()
                }
                "battery_status" -> {
                    actionFeedback = actionManager.getBatteryStatus()
                }
                "go_home" -> {
                    actionFeedback = actionManager.goHome()
                }
                "whatsapp_call" -> {
                    actionFeedback = actionManager.whatsappCall(command.target)
                }
                "whatsapp_message" -> {
                    actionFeedback = actionManager.whatsappMessage(command.target)
                }
                "open_whatsapp_chat" -> {
                    actionFeedback = actionManager.openWhatsAppChat(command.target)
                }
                "web_search" -> {
                    actionFeedback = actionManager.webSearch(command.target)
                }
                "send_current_message" -> {
                    actionFeedback = actionManager.sendCurrentMessage()
                }
                "open_app" -> {
                    actionFeedback = actionManager.openApp(command.target)
                }
                "toggle_flashlight" -> {
                    val enable = command.target.equals("on", ignoreCase = true) ||
                            command.target.equals("true", ignoreCase = true)
                    actionFeedback = actionManager.toggleFlashlight(enable)
                }
                "open_url" -> {
                    actionFeedback = actionManager.openUrl(command.target)
                }
                "dial" -> {
                    actionFeedback = actionManager.dialNumber(command.target)
                }
                "call" -> {
                    actionFeedback = actionManager.makeDirectCall(command.target)
                }
                "lock_screen" -> {
                    actionFeedback = actionManager.lockScreen()
                }
                "close_all_apps" -> {
                    actionFeedback = actionManager.closeAllApps()
                }
                else -> {
                    // Conversational action
                }
            }

            val spokenResponse = if (!actionFeedback.isNullOrBlank() && actionFeedback.contains("Please enable", ignoreCase = true)) {
                actionFeedback
            } else {
                command.speechResponse
            }

            auraReply = spokenResponse

            // Speak the response to the user
            assistantState = AssistantState.SPEAKING
            voiceManager.speak(spokenResponse) {
                assistantState = AssistantState.IDLE
            }
        }
    }

    private fun checkAndRequestStartupPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )

        val ungranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            requestStartupPermissionsLauncher.launch(requiredPermissions)
        }
    }

    private val requestStartupPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "Microphone permission is required for Aura.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissionAndStartListening() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            toggleListening()
        } else {
            requestStartupPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS
                )
            )
        }
    }

    private fun toggleListening() {
        if (assistantState == AssistantState.LISTENING) {
            voiceManager.stopListening()
            assistantState = AssistantState.IDLE
        } else {
            assistantState = AssistantState.LISTENING
            auraReply = "Listening..."
            voiceManager.startListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.destroy()
    }
}

// ---------------------------------------------------------------------------
// Jetpack Compose Sleek UI Components
// ---------------------------------------------------------------------------

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF38BDF8), // Cyan Accent
            secondary = Color(0xFF818CF8), // Indigo Accent
            background = Color(0xFF0F172A), // Dark Slate
            surface = Color(0xFF1E293B) // Dark Card Surface
        ),
        content = content
    )
}

@Composable
fun AuraAssistantScreen(
    assistantState: AssistantState,
    userPrompt: String,
    auraReply: String,
    isBackgroundModeEnabled: Boolean,
    isAccessibilityEnabled: Boolean,
    onToggleBackgroundMode: (Boolean) -> Unit,
    onMicClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Section: Title & Status Subtitle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text(
                text = "Aura AI Assistant",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF8FAFC)
            )

            Spacer(modifier = Modifier.height(4.dp))

            val statusText = when (assistantState) {
                AssistantState.IDLE -> if (isBackgroundModeEnabled) "Listening for 'Hey Aura' in background" else "Tap mic to speak"
                AssistantState.LISTENING -> "Listening to your voice..."
                AssistantState.THINKING -> "Aura is thinking..."
                AssistantState.SPEAKING -> "Aura is speaking..."
                AssistantState.ERROR -> "Something went wrong"
            }

            val statusColor = when (assistantState) {
                AssistantState.LISTENING -> Color(0xFF38BDF8)
                AssistantState.THINKING -> Color(0xFFFBBF24)
                AssistantState.SPEAKING -> Color(0xFF34D399)
                AssistantState.ERROR -> Color(0xFFF87171)
                else -> if (isBackgroundModeEnabled) Color(0xFF38BDF8) else Color(0xFF94A3B8)
            }

            Text(
                text = statusText,
                fontSize = 13.sp,
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
        }

        // Middle Section: Background Mode Switch + Conversation Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // Hands-Free Hey Aura Mode Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hands-Free 'Hey Aura' Mode",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Listen for wake-word in background",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Switch(
                        checked = isBackgroundModeEnabled,
                        onCheckedChange = onToggleBackgroundMode,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF38BDF8),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }
            }

            // Accessibility Service Enable Card Button
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Aura Accessibility Automation",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = if (isAccessibilityEnabled) "Service Active (Auto-Send & Screen Lock Ready)" else "Tap to enable in Android Settings",
                            fontSize = 12.sp,
                            color = if (isAccessibilityEnabled) Color(0xFF34D399) else Color(0xFFFBBF24)
                        )
                    }

                    Button(
                        onClick = {
                            if (!isAccessibilityEnabled) {
                                AuraAccessibilityService.openAccessibilitySettings(context)
                            }
                        },
                        enabled = !isAccessibilityEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAccessibilityEnabled) Color(0xFF059669) else Color(0xFFD97706),
                            disabledContainerColor = Color(0xFF059669)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isAccessibilityEnabled) "Active" else "Enable",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }

            // Main Conversation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // User Query Section
                    Text(
                        text = "YOU",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8),
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = userPrompt,
                        fontSize = 16.sp,
                        color = Color(0xFFE2E8F0),
                        fontWeight = FontWeight.Normal
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Aura Response Section
                    Text(
                        text = "AURA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF818CF8),
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = auraReply,
                        fontSize = 18.sp,
                        color = Color(0xFFF8FAFC),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Bottom Section: Animated Mic Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            // Pulsing Animation when Listening or Speaking
            val isAnimated = assistantState == AssistantState.LISTENING || assistantState == AssistantState.SPEAKING
            val infiniteTransition = rememberInfiniteTransition(label = "MicPulseTransition")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isAnimated) 1.35f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "MicPulseScale"
            )

            // Outer Glowing Ring
            if (isAnimated) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.4f),
                                    Color(0xFF818CF8).copy(alpha = 0.1f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            // Main Microphone Circle Button
            val buttonGradient = when (assistantState) {
                AssistantState.LISTENING -> Brush.linearGradient(listOf(Color(0xFF0284C7), Color(0xFF38BDF8)))
                AssistantState.THINKING -> Brush.linearGradient(listOf(Color(0xFFD97706), Color(0xFFFBBF24)))
                AssistantState.SPEAKING -> Brush.linearGradient(listOf(Color(0xFF059669), Color(0xFF34D399)))
                else -> Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF0284C7)))
            }

            Button(
                onClick = onMicClick,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(buttonGradient)
            ) {
                Text(
                    text = if (assistantState == AssistantState.LISTENING) "●" else "🎙",
                    fontSize = 32.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
