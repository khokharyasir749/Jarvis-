package com.aura.assistant

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed class JarvisToolCall {
    data class OpenWhatsAppChat(val contactName: String) : JarvisToolCall()
    data class WhatsAppMessage(val contactName: String, val messageBody: String) : JarvisToolCall()
    object SendCurrentMessage : JarvisToolCall()
    data class SystemAction(val actionType: String, val target: String = "") : JarvisToolCall()
    data class Conversation(val replyText: String) : JarvisToolCall()
}

class AuraJarvisBrain(private val aiEngine: AuraAIEngine) {

    private val tag = "JarvisBrain"

    /**
     * Processes raw user speech input through fast-path local tool routing and Gemini AI intent classification,
     * wrapped in a 2-second timeout safety to eliminate execution hangs.
     *
     * @param userText Raw transcribed speech from user.
     * @return Structured JarvisToolCall decision.
     */
    suspend fun processInput(userText: String): JarvisToolCall = withContext(Dispatchers.IO) {
        val cleanInput = cleanInputString(userText)
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .trim()

        if (cleanInput.isBlank()) {
            Log.w(tag, "Empty input received in JarvisBrain")
            return@withContext JarvisToolCall.Conversation("I'm listening, Boss. How may I assist you?")
        }

        Log.d(tag, "Processing Input: '$cleanInput' (raw='$userText')")

        val toolCall = try {
            withTimeout(2000L) {
                // 1. Bulletproof Fuzzy Token Matcher Core
                val fastToolCall = parseLocalFastPathTool(cleanInput)
                if (fastToolCall != null) {
                    fastToolCall
                } else {
                    // 2. AI Network Query to Gemini AI Engine
                    val command = aiEngine.processQuery(cleanInput)
                    mapCommandToJarvisTool(command, cleanInput)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(tag, "processInput timed out after 2000ms. Returning fallback fast-path...")
            val fallbackCall = parseLocalFastPathTool(cleanInput)
            fallbackCall ?: JarvisToolCall.Conversation("Executing command, Boss.")
        } catch (e: Exception) {
            Log.e(tag, "Error processing input: ${e.message}")
            JarvisToolCall.Conversation("I'm listening, Boss.")
        }

        Log.e("AuraEngine", "Heard text: $userText | Dispatched Intent: ${getToolName(toolCall)}")
        toolCall
    }

    private fun getToolName(toolCall: JarvisToolCall): String {
        return when (toolCall) {
            is JarvisToolCall.OpenWhatsAppChat -> "OPEN_WHATSAPP_CHAT(contact=${toolCall.contactName})"
            is JarvisToolCall.WhatsAppMessage -> "WHATSAPP_MESSAGE(contact=${toolCall.contactName}, msg=${toolCall.messageBody})"
            is JarvisToolCall.SendCurrentMessage -> "SEND_CURRENT_MESSAGE"
            is JarvisToolCall.SystemAction -> "SYSTEM_ACTION(action=${toolCall.actionType}, target=${toolCall.target})"
            is JarvisToolCall.Conversation -> "CONVERSATION(reply=${toolCall.replyText})"
        }
    }

    private fun cleanInputString(input: String): String {
        return input.trim()
            .replace(Regex("(?i)^please\\s+"), "")
            .replace(Regex("(?i)^kripya\\s+"), "")
            .replace(Regex("(?i)^zara\\s+"), "")
            .replace(Regex("(?i)^to\\s+"), "")
            .replace(Regex("(?i)^jarvis\\s+"), "")
            .replace(Regex("(?i)^aura\\s+"), "")
            .replace(Regex("(?i)\\s+please$"), "")
            .replace(Regex("(?i)\\s+zara$"), "")
            .replace(Regex("(?i)\\s+bhai$"), "")
            .replace(Regex("(?i)\\s+jarvis$"), "")
            .replace(Regex("(?i)\\s+aura$"), "")
            .trim()
    }

    private fun parseLocalFastPathTool(cleanText: String): JarvisToolCall? {
        val lower = cleanText.lowercase().trim().replace(Regex("[^a-zA-Z0-9\\s]"), "")
        if (lower.isEmpty()) return null

        // 1. Explicit Dialog Dismissal / Exit ('stop', 'turn off', 'bye', 'close', 'exit', 'be quiet', 'goodbye', 'turn off aura', 'bas')
        if (lower == "goodbye" || lower == "dismiss" || lower == "bas" || lower == "shut up" ||
            lower == "chup" || lower == "bye" || lower == "exit" || lower == "stop" || lower == "close" ||
            lower.contains("goodbye") || lower.contains("dismiss") || lower.contains("shut up") ||
            lower.contains("chup") || lower == "bas karo" || lower == "chup karo" || lower == "be quiet" ||
            lower == "turn off aura" || lower == "turn off assistant" || lower == "shut up jarvis") {
            return JarvisToolCall.SystemAction(actionType = "dismiss_dialog")
        }

        // 2. YouTube Broad Token Matcher ("youtube", "you tube", "u tube", "utube", "video")
        if ((lower.contains("youtube") || lower.contains("you tube") || lower.contains("u tube") || lower.contains("utube") || lower.contains("yutube") || lower.contains("video")) &&
            !lower.contains("home") && !lower.contains("screen")) {
            return JarvisToolCall.SystemAction(actionType = "open_app", target = "YouTube")
        }

        // 3. WhatsApp Broad Token Matcher ("whatsapp", "wts", "watsapp", "whatsap", "whats", "chat")
        if (lower.contains("whatsapp") || lower.contains("wts") || lower.contains("watsapp") || lower.contains("whatsap") || lower.contains("whats") || lower.contains("chat")) {
            val isCall = lower.contains("call") || lower.contains("dial") || lower.contains("voip") || lower.contains("milao")
            val isOpenChat = lower.contains("open chat") || lower.contains("show chat")

            if (isCall) {
                val contact = extractContactNameFromCall(cleanText)
                return JarvisToolCall.SystemAction(actionType = "whatsapp_call", target = contact)
            }

            if (isOpenChat) {
                val contact = extractContactNameFromOpen(cleanText)
                return JarvisToolCall.OpenWhatsAppChat(contact)
            }

            val messagePair = parseNaturalMessageExtraction(cleanText)
            if (messagePair != null) {
                return JarvisToolCall.WhatsAppMessage(contactName = messagePair.first, messageBody = messagePair.second)
            }

            return JarvisToolCall.SystemAction(actionType = "open_app", target = "WhatsApp")
        }

        // 4. Flashlight / Torch Broad Token Matcher ("torch", "flash", "light", "batti")
        if (lower.contains("torch") || lower.contains("flash") || lower.contains("light") || lower.contains("batti")) {
            val isOff = lower.contains("off") || lower.contains("disable") || lower.contains("stop") || lower.contains("close") || lower.contains("band")
            val type = if (isOff) "flashlight_off" else "flashlight_on"
            return JarvisToolCall.SystemAction(actionType = type, target = if (isOff) "off" else "on")
        }

        // 5. Camera Broad Token Matcher ("camera", "camra", "photo", "picture", "tasweer")
        if (lower.contains("camera") || lower.contains("camra") || lower.contains("photo") || lower.contains("picture") || lower.contains("tasweer")) {
            return JarvisToolCall.SystemAction(actionType = "open_camera")
        }

        // 6. Volume Stream Control Broad Token Matcher ("volume", "awaz", "sound", "louder", "quieter")
        if (lower.contains("volume") || lower.contains("awaz") || lower.contains("sound") || lower == "louder" || lower == "quieter") {
            val isDown = lower.contains("down") || lower.contains("decrease") || lower.contains("lower") || lower.contains("kam") || lower == "quieter"
            val isMute = lower.contains("mute") || lower.contains("silent")
            val type = when {
                isMute -> "volume_mute"
                isDown -> "volume_down"
                else -> "volume_up"
            }
            return JarvisToolCall.SystemAction(actionType = type, target = if (isDown) "down" else "up")
        }

        // 7. Bluetooth Controls
        if (lower.contains("bluetooth")) {
            val isOff = lower.contains("off") || lower.contains("disable") || lower.contains("stop") || lower.contains("band")
            val type = if (isOff) "bluetooth_off" else "bluetooth_on"
            return JarvisToolCall.SystemAction(actionType = type, target = if (isOff) "off" else "on")
        }

        // 8. Brightness Controls
        if (lower.contains("brightness")) {
            val isDown = lower.contains("down") || lower.contains("decrease") || lower.contains("lower") || lower.contains("kam")
            val type = if (isDown) "brightness_down" else "brightness_up"
            return JarvisToolCall.SystemAction(actionType = type, target = if (isDown) "down" else "up")
        }

        // 9. Back Navigation ("back", "piche", "go back")
        if (lower == "back" || lower == "go back" || lower == "piche" || lower.contains("go back")) {
            return JarvisToolCall.SystemAction(actionType = "go_back")
        }

        // 10. Device Diagnostics Trigger ('system status', 'device report', 'kya chal raha hai', 'diagnostics')
        if (lower.contains("system status") || lower.contains("device report") || lower.contains("device status") ||
            lower.contains("system report") || lower.contains("kya chal raha hai") || lower.contains("diagnostics") ||
            lower.contains("status report") || lower.contains("device health") || lower.contains("how is my device")) {
            return JarvisToolCall.SystemAction(actionType = "device_diagnostics")
        }

        // 11. Battery Status Hardware Diagnostic ('how is my battery', 'charge kitna hai', 'battery status')
        if (lower.contains("battery") || lower.contains("charge") || lower.contains("charging") || lower.contains("power level")) {
            return JarvisToolCall.SystemAction(actionType = "battery_status")
        }

        // 12. Media Playback Control
        if (lower.contains("pause music") || lower.contains("stop song") || lower.contains("pause song") ||
            lower.contains("play music") || lower.contains("play song") || lower.contains("gana bajao") ||
            lower.contains("stop music") || lower.contains("gana roko")) {
            return JarvisToolCall.SystemAction(actionType = "media_play_pause", target = "play_pause")
        }

        if (lower.contains("next song") || lower.contains("next track") || lower.contains("agla gana") || lower.contains("skip song")) {
            return JarvisToolCall.SystemAction(actionType = "media_next", target = "next")
        }

        if (lower.contains("previous song") || lower.contains("previous track") || lower.contains("pichla gana")) {
            return JarvisToolCall.SystemAction(actionType = "media_previous", target = "previous")
        }

        // 13. Phonetic Send Confirmation Normalization ('sendet', 'sand it', 'send eat', 'sand', 'sended', 'bhejo', 'send')
        if (lower == "send it" || lower == "sendet" || lower == "sand it" || lower == "send eat" || lower == "sand" ||
            lower == "sended" || lower == "send message" || lower == "bhejo" || lower == "send this" || lower == "click send" || lower == "send") {
            return JarvisToolCall.SendCurrentMessage
        }

        // 14. Phonetic Lock Screen Normalization ('luck screen', 'log screen', 'lockscreen', 'screen lock', 'band karo screen')
        if (lower.contains("lock screen") || lower.contains("luck screen") || lower.contains("log screen") || lower.contains("lockscreen") ||
            lower.contains("turn off screen") || lower.contains("screen lock") || lower.contains("lock phone") ||
            lower.contains("band karo screen") || lower.contains("screen band") || lower == "lock" || lower == "screen off") {
            return JarvisToolCall.SystemAction(actionType = "lock_screen")
        }

        // 15. Resilient Home Screen Matching (matches 'home', 'home screen', 'you home', 'do home', 'go to home', or any string containing 'home')
        if (lower.contains("home") || lower == "minimize") {
            return JarvisToolCall.SystemAction(actionType = "go_home")
        }

        // 16. Phonetic Close All Apps Normalization ('close all tabs', 'close all apps', 'clear all', 'recent apps', 'recents', 'task manager')
        if (lower.contains("close all") || lower.contains("closs all") || lower.contains("close all app") ||
            lower.contains("close all tab") || lower.contains("clear all") || lower.contains("recent apps") ||
            lower.contains("recents") || lower.contains("task manager")) {
            return JarvisToolCall.SystemAction(actionType = "close_all_apps")
        }

        // Standalone Common App Triggers
        if (lower == "chrome" || lower == "browser" || lower == "google chrome") {
            return JarvisToolCall.SystemAction(actionType = "open_app", target = "Chrome")
        }
        if (lower == "settings" || lower == "setting") {
            return JarvisToolCall.SystemAction(actionType = "open_app", target = "Settings")
        }

        if (lower.startsWith("tell ") || lower.startsWith("message ") || lower.startsWith("text ")) {
            val messagePair = parseNaturalMessageExtraction(cleanText)
            if (messagePair != null) {
                return JarvisToolCall.WhatsAppMessage(contactName = messagePair.first, messageBody = messagePair.second)
            }
        }

        // 17. Universal App Launcher Triggers ('open wts ap', 'launch Spotify', 'Calculator kholo', 'Camera kholo')
        val openMatch = Regex("(?i)^(?:open|launch|start|run|chalao)\\s+(.+)$").find(cleanText)
        if (openMatch != null) {
            val targetApp = openMatch.groupValues[1].trim()
            if (!targetApp.equals("chat", ignoreCase = true) &&
                !targetApp.equals("website", ignoreCase = true) &&
                !targetApp.startsWith("http", ignoreCase = true)) {
                return JarvisToolCall.SystemAction(actionType = "open_app", target = targetApp)
            }
        }

        val kholoMatch = Regex("(?i)^(.+)\\s+(?:kholo|open\\s+karo|chalao|khol\\s+do|open)$").find(cleanText)
        if (kholoMatch != null) {
            val targetApp = kholoMatch.groupValues[1].trim()
            if (!targetApp.equals("chat", ignoreCase = true)) {
                return JarvisToolCall.SystemAction(actionType = "open_app", target = targetApp)
            }
        }

        // 18. Direct Phone Calls & Dialer
        if (lower.contains("call ") || lower.contains("phone ") || lower.contains("dial ") || lower.startsWith("call") || lower.startsWith("dial")) {
            val isDial = lower.contains("dial ") || lower == "dial"
            val contact = extractContactNameFromCall(cleanText)
            val actionType = if (isDial) "dial_number" else "make_call"
            return JarvisToolCall.SystemAction(actionType = actionType, target = contact)
        }

        // 19. Open Web Search
        if (lower.startsWith("search ") || lower.startsWith("find ") || lower.startsWith("google ")) {
            val query = lower
                .replace(Regex("(?i)^search\\s+for\\s+"), "")
                .replace(Regex("(?i)^search\\s+"), "")
                .replace(Regex("(?i)^find\\s+"), "")
                .replace(Regex("(?i)^google\\s+"), "")
                .trim()
            return JarvisToolCall.SystemAction(actionType = "web_search", target = query)
        }

        // 20. Small Talk / Jarvis Greetings (Strict Tony Stark / JARVIS Persona)
        if (lower.contains("how are you") || lower.contains("kaise ho")) {
            return JarvisToolCall.Conversation("All systems operational and at peak efficiency, Boss. How may I assist you?")
        }

        if (lower == "hello" || lower == "hi" || lower == "hey" || lower == "jarvis" ||
            lower.contains("who are you") || lower.contains("your name")) {
            return JarvisToolCall.Conversation("I am JARVIS, your loyal AI assistant, Boss. How may I be of service?")
        }

        if (lower.contains("thank") || lower.contains("thanks") || lower.contains("shukriya")) {
            return JarvisToolCall.Conversation("Always a pleasure, Boss.")
        }

        return null
    }

    private fun parseNaturalMessageExtraction(prompt: String): Pair<String, String>? {
        val clean = prompt.trim()
            .replace(Regex("(?i)\\s+on\\s+whatsapp"), "")
            .replace(Regex("(?i)\\s+via\\s+whatsapp"), "")
            .replace(Regex("(?i)\\s+whatsapp"), "")
            .trim()

        val tellRegex = Regex("(?i)^tell\\s+([a-zA-Z0-9\\s]+?)\\s+(?:that\\s+)?(.+)$")
        val tellMatch = tellRegex.find(clean)
        if (tellMatch != null) {
            val contact = tellMatch.groupValues[1].trim()
            val message = tellMatch.groupValues[2].trim()
            return Pair(contact, message)
        }

        val sendToRegex = Regex("(?i)^(?:send|text|message)\\s+(.+)\\s+to\\s+(.+)$")
        val sendToMatch = sendToRegex.find(clean)
        if (sendToMatch != null) {
            val message = sendToMatch.groupValues[1].trim()
            val contact = sendToMatch.groupValues[2].trim()
            return Pair(contact, message)
        }

        val msgRegex = Regex("(?i)^(?:send|text|message)\\s+([a-zA-Z0-9\\s]+?)\\s+(.+)$")
        val msgMatch = msgRegex.find(clean)
        if (msgMatch != null) {
            val contact = msgMatch.groupValues[1].trim()
            val message = msgMatch.groupValues[2].trim()
            return Pair(contact, message)
        }

        return null
    }

    private fun extractContactNameFromCall(text: String): String {
        return text
            .replace(Regex("(?i)make\\s+a\\s+call\\s+to"), "")
            .replace(Regex("(?i)call\\s+to"), "")
            .replace(Regex("(?i)whatsapp\\s+call"), "")
            .replace(Regex("(?i)call"), "")
            .replace(Regex("(?i)dial"), "")
            .replace(Regex("(?i)phone"), "")
            .replace(Regex("(?i)milao"), "")
            .replace(Regex("(?i)karo"), "")
            .replace(Regex("(?i)ko\\s+"), "")
            .replace(Regex("(?i)on\\s+whatsapp"), "")
            .replace(Regex("(?i)via\\s+whatsapp"), "")
            .replace(Regex("(?i)whatsapp"), "")
            .replace(Regex("(?i)^to\\s+"), "")
            .replace(Regex("(?i)^the\\s+"), "")
            .replace(Regex("(?i)^my\\s+"), "")
            .replace(Regex("(?i)bhai"), "")
            .replace(Regex("(?i)please"), "")
            .trim()
    }

    private fun extractContactNameFromOpen(text: String): String {
        return text
            .replace(Regex("(?i)open\\s+chat\\s+of"), "")
            .replace(Regex("(?i)open\\s+chat\\s+for"), "")
            .replace(Regex("(?i)open\\s+chat"), "")
            .replace(Regex("(?i)show\\s+chat"), "")
            .replace(Regex("(?i)open"), "")
            .replace(Regex("(?i)on\\s+whatsapp"), "")
            .replace(Regex("(?i)via\\s+whatsapp"), "")
            .replace(Regex("(?i)whatsapp"), "")
            .replace(Regex("(?i)^to\\s+"), "")
            .replace(Regex("(?i)^the\\s+"), "")
            .replace(Regex("(?i)^my\\s+"), "")
            .trim()
    }

    private fun mapCommandToJarvisTool(command: AuraCommand, rawInput: String): JarvisToolCall {
        return when (command.action.lowercase()) {
            "send_current_message" -> JarvisToolCall.SendCurrentMessage
            "open_whatsapp_chat" -> JarvisToolCall.OpenWhatsAppChat(command.target)
            "whatsapp_message" -> {
                val pair = parseNaturalMessageExtraction(rawInput)
                if (pair != null) {
                    JarvisToolCall.WhatsAppMessage(pair.first, pair.second)
                } else {
                    JarvisToolCall.WhatsAppMessage(command.target, rawInput)
                }
            }
            "whatsapp_call" -> JarvisToolCall.SystemAction("whatsapp_call", command.target)
            "device_diagnostics" -> JarvisToolCall.SystemAction("device_diagnostics")
            "go_home" -> JarvisToolCall.SystemAction("go_home")
            "close_all_apps" -> JarvisToolCall.SystemAction("close_all_apps")
            "lock_screen" -> JarvisToolCall.SystemAction("lock_screen")
            "toggle_flashlight" -> JarvisToolCall.SystemAction(
                if (command.target.equals("off", ignoreCase = true)) "flashlight_off" else "flashlight_on",
                command.target
            )
            "open_app" -> JarvisToolCall.SystemAction("open_app", command.target)
            "open_url" -> JarvisToolCall.SystemAction("open_url", command.target)
            "call" -> JarvisToolCall.SystemAction("make_call", command.target)
            "dial" -> JarvisToolCall.SystemAction("dial_number", command.target)
            else -> {
                val response = if (command.speechResponse.contains("trouble processing", ignoreCase = true)) {
                    "Could you repeat that, Boss?"
                } else {
                    command.speechResponse
                }
                JarvisToolCall.Conversation(response)
            }
        }
    }
}
