package com.aura.assistant

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class AuraCommand(
    val action: String, // 'open_app', 'toggle_flashlight', 'open_url', 'dial', 'call', 'lock_screen', 'go_home', 'whatsapp_call', 'whatsapp_message', or 'chat'
    val target: String = "", // app name, phone number, or url
    val speechResponse: String
)

class AuraAIEngine(private val apiKey: String) {

    private val gson = Gson()
    private val tag = "AuraAI"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val modelEndpoints = listOf(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"
    )

    /**
     * Processes user spoken or text query using local fast-path matcher first,
     * falling back to Google Gemini REST API with exponential backoff on HTTP 429.
     *
     * @param userText Input prompt spoken or typed by user.
     * @return Structured AuraCommand parsed locally or from AI response.
     */
    suspend fun processQuery(userText: String): AuraCommand = withContext(Dispatchers.IO) {
        val fallbackCommand = AuraCommand(
            action = "chat",
            target = "",
            speechResponse = "Could you repeat that, Boss?"
        )

        val rateLimitCommand = AuraCommand(
            action = "chat",
            target = "",
            speechResponse = "Please give me a moment, Boss, processing limit reached."
        )

        if (userText.isBlank()) {
            Log.w(tag, "User text query is blank!")
            return@withContext AuraCommand(
                action = "chat",
                target = "",
                speechResponse = "Could you repeat that, Boss?"
            )
        }

        // 1. Fast-Path Local Intent Matcher (Instant execution, 0 quota usage)
        val localCommand = matchLocalFastPath(userText)
        if (localCommand != null) {
            Log.d(tag, "Fast-Path local match found for query '$userText': $localCommand")
            return@withContext localCommand
        }

        if (apiKey.isBlank()) {
            Log.e(tag, "Gemini API key is blank or missing!")
            return@withContext AuraCommand(
                action = "chat",
                target = "",
                speechResponse = "API key is missing. Please set your Gemini API key in settings."
            )
        }

        // 2. Network Query to Gemini REST API with Exponential Backoff
        val systemInstructionText = """
            You are JARVIS, an ultra-intelligent, sharp, loyal, and polite AI assistant for Android modeled after Tony Stark's JARVIS.
            Your tone is refined, crisp, and respectful. You MUST address the user exclusively as 'Boss' in every response.

            Analyze the user's voice input and classify it into ONE of these exact actions:
            1. 'open_app': Target is the app name to launch (e.g. 'YouTube', 'WhatsApp', 'Camera').
            2. 'toggle_flashlight': Target is 'true' or 'on' to turn on, 'false' or 'off' to turn off.
            3. 'open_url': Target is the website URL (e.g. 'google.com', 'wikipedia.org').
            4. 'call': Target is the contact name or phone number to call directly (e.g. 'Dad', '1234567890').
            5. 'dial': Target is the contact name or phone number to open in the dialer.
            6. 'lock_screen': Target is empty string "". Used when user asks to lock screen or turn off screen.
            7. 'go_home': Target is empty string "". Used when user asks to go home, go to home screen, or minimize.
            8. 'whatsapp_call': Target is contact name to call on WhatsApp.
            9. 'whatsapp_message': Target is raw prompt to message on WhatsApp.
            10. 'chat': Conversational response, general knowledge, or question. Target is empty string "".

            You MUST ALWAYS respond strictly in a single JSON object with this format:
            {"action": "open_app"|"toggle_flashlight"|"open_url"|"call"|"dial"|"lock_screen"|"go_home"|"whatsapp_call"|"whatsapp_message"|"chat", "target": "<value>", "speechResponse": "<sharp, loyal, polite response addressing user as 'Boss'>"}

            Do NOT include markdown formatting, code block backticks (```), or any text outside the JSON object.
        """.trimIndent()

        val combinedPrompt = "$systemInstructionText\n\nUser Voice Request: \"$userText\""

        val requestPayload = JsonObject().apply {
            add("contents", gson.toJsonTree(listOf(
                mapOf("parts" to listOf(mapOf("text" to combinedPrompt)))
            )))
            add("generationConfig", JsonObject().apply {
                addProperty("responseMimeType", "application/json")
                addProperty("temperature", 0.2)
            })
        }

        val payloadJson = gson.toJson(requestPayload)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        var isRateLimitError = false
        val backoffDelaysMs = listOf(3000L, 5000L)

        for (baseUrl in modelEndpoints) {
            for (attempt in 0..backoffDelaysMs.size) {
                try {
                    val endpoint = if (baseUrl.contains("?")) "$baseUrl&key=$apiKey" else "$baseUrl?key=$apiKey"
                    Log.d(tag, "Attempt ${attempt + 1} sending request to $baseUrl")

                    val requestBody = payloadJson.toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url(endpoint)
                        .addHeader("X-goog-api-key", apiKey)
                        .post(requestBody)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseCode = response.code
                    val responseBodyString = response.body?.string() ?: ""

                    Log.d(tag, "HTTP Response Code ($baseUrl): $responseCode")

                    // Catch HTTP 429 / Rate Limit
                    if (responseCode == 429 ||
                        responseBodyString.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        responseBodyString.contains("Quota exceeded", ignoreCase = true) ||
                        responseBodyString.contains("rate limit", ignoreCase = true)) {
                        isRateLimitError = true
                        if (attempt < backoffDelaysMs.size) {
                            val waitMs = backoffDelaysMs[attempt]
                            Log.w(tag, "HTTP 429 Rate Limit hit. Retrying in ${waitMs / 1000}s (Attempt ${attempt + 1}/${backoffDelaysMs.size})...")
                            kotlinx.coroutines.delay(waitMs)
                            continue
                        } else {
                            Log.e(tag, "Exhausted retries for $baseUrl due to rate limit 429")
                            break
                        }
                    }

                    if (!response.isSuccessful) {
                        Log.w(tag, "HTTP Error $responseCode for $baseUrl. Trying next model endpoint...")
                        break
                    }

                    // Extract text from Gemini API response JSON
                    val rawText = parseGeminiResponseText(responseBodyString)
                    if (rawText.isNullOrBlank()) {
                        Log.w(tag, "Failed to extract text from response body for $baseUrl")
                        break
                    }

                    Log.d(tag, "Extracted Raw AI Text: $rawText")

                    // Sanitize JSON string (strip markdown codeblocks if any)
                    val sanitizedJson = sanitizeJsonResponse(rawText)
                    Log.d(tag, "Sanitized JSON for Gson: $sanitizedJson")

                    // Parse sanitized JSON string into AuraCommand
                    val parsedCommand = try {
                        gson.fromJson(sanitizedJson, AuraCommand::class.java)
                    } catch (e: Exception) {
                        Log.e(tag, "Gson Parsing Error: ${e.localizedMessage} for text '$sanitizedJson'")
                        null
                    }

                    if (parsedCommand != null && parsedCommand.action.isNotBlank() && parsedCommand.speechResponse.isNotBlank()) {
                        Log.d(tag, "Successfully processed query via Gemini API: $parsedCommand")
                        return@withContext parsedCommand
                    } else {
                        Log.w(tag, "Parsed command missing required fields: $parsedCommand")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Exception during request to $baseUrl: ${e.localizedMessage}", e)
                    break
                }
            }
        }

        if (isRateLimitError) {
            return@withContext rateLimitCommand
        }

        Log.e(tag, "All API attempts failed for query: \"$userText\"")
        fallbackCommand
    }

    /**
     * Fast-Path Rule-based Intent Matcher with Weighted Keywords.
     * Cleans incoming speech string (lowercase, trim, remove punctuation) and routes accurately.
     */
    private fun matchLocalFastPath(userText: String): AuraCommand? {
        val lower = userText.lowercase().trim().replace(Regex("[^a-zA-Z0-9\\s]"), "")
        if (lower.isEmpty()) return null

        // Explicit 'Send It' / 'Bhejo' Send Confirmation
        if (lower == "send it" || lower == "send message" || lower == "bhejo" || lower == "send this" || lower == "click send" || lower == "send") {
            Log.d(tag, "FastPath matched Explicit Send Confirmation: '$lower'")
            return AuraCommand(action = "send_current_message", target = "", speechResponse = "Sent, Boss.")
        }

        // 1. WhatsApp Weighted Routing (Call, Message, Open Chat)
        if (lower.contains("whatsapp")) {
            val isCall = lower.contains("call") || lower.contains("dial") || lower.contains("voip")
            val isMessage = lower.contains("message") || lower.contains("text") || lower.contains("send")
            val isOpenChat = lower.contains("open") || lower.contains("chat") || lower.contains("show")

            if (isCall) {
                val cleanTarget = lower
                    .replace(Regex("(?i)make\\s+a\\s+call\\s+to"), "")
                    .replace(Regex("(?i)call\\s+to"), "")
                    .replace(Regex("(?i)call"), "")
                    .replace(Regex("(?i)dial"), "")
                    .replace(Regex("(?i)on\\s+whatsapp"), "")
                    .replace(Regex("(?i)via\\s+whatsapp"), "")
                    .replace(Regex("(?i)whatsapp"), "")
                    .replace(Regex("(?i)^to\\s+"), "")
                    .replace(Regex("(?i)^the\\s+"), "")
                    .replace(Regex("(?i)^my\\s+"), "")
                    .replace(Regex("(?i)bhai"), "")
                    .replace(Regex("(?i)please"), "")
                    .trim()

                Log.d(tag, "Weighted FastPath matched WhatsApp Call ONLY: '$cleanTarget'")
                return AuraCommand(action = "whatsapp_call", target = cleanTarget, speechResponse = "Calling $cleanTarget on WhatsApp, Boss.")
            }

            if (isMessage) {
                Log.d(tag, "Weighted FastPath matched WhatsApp Message ONLY: '$userText'")
                return AuraCommand(action = "whatsapp_message", target = userText, speechResponse = "Messaging on WhatsApp, Boss.")
            }

            if (isOpenChat) {
                val cleanTarget = lower
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

                Log.d(tag, "FastPath matched Open WhatsApp Chat: '$cleanTarget'")
                return AuraCommand(action = "open_whatsapp_chat", target = cleanTarget, speechResponse = "Opening WhatsApp chat for $cleanTarget, Boss.")
            }
        }

        // Universal Web Search Trigger
        if (lower.startsWith("search ") || lower.startsWith("find ") || lower.startsWith("google ") || lower.startsWith("search for ")) {
            val searchQuery = lower
                .replace(Regex("(?i)^search\\s+for\\s+"), "")
                .replace(Regex("(?i)^search\\s+"), "")
                .replace(Regex("(?i)^find\\s+"), "")
                .replace(Regex("(?i)^google\\s+"), "")
                .trim()

            Log.d(tag, "FastPath matched Universal Web Search: '$searchQuery'")
            return AuraCommand(action = "web_search", target = searchQuery, speechResponse = "Searching for $searchQuery, Boss.")
        }

        // 2. Home Screen / Minimize Weighted Routing
        if (lower == "home" || lower == "go home" || lower.contains("home screen") || lower == "minimize" || lower == "go to home") {
            Log.d(tag, "Weighted FastPath matched Home Screen")
            return AuraCommand(action = "go_home", target = "", speechResponse = "Going home, Boss.")
        }

        // Close All Apps / Recents Weighted Routing
        if (lower.contains("close all") || lower.contains("clear recent") || lower.contains("close recent") || lower.contains("clear all apps")) {
            Log.d(tag, "Weighted FastPath matched Close All Apps")
            return AuraCommand(action = "close_all_apps", target = "", speechResponse = "Closing all recent apps, Boss.")
        }

        // 3. Flashlight / Torch Weighted Routing
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("stop") || lower.contains("close")
            val enableStr = if (turnOff) "off" else "on"
            val speech = if (turnOff) "Turning off the flashlight, Boss." else "Turning on the flashlight, Boss."
            Log.d(tag, "Weighted FastPath matched Flashlight: $enableStr")
            return AuraCommand(action = "toggle_flashlight", target = enableStr, speechResponse = speech)
        }

        // 4. Lock Screen / Screen Off
        if (lower.contains("lock screen") || lower.contains("turn off screen") || lower.contains("screen lock") ||
            lower.contains("lock the screen") || lower.contains("screen off") || lower.contains("lock phone") ||
            lower.contains("band karo screen") || lower.contains("screen band karo") || lower == "lock" || lower == "screen off") {
            Log.d(tag, "FastPath matched Lock Screen")
            return AuraCommand(action = "lock_screen", target = "", speechResponse = "Locking screen, Boss.")
        }

        // 5. Direct Phone Calls & Dialer
        if (lower.contains("call ") || lower.contains("phone ") || lower.contains("dial ") || lower.startsWith("call") || lower.startsWith("dial")) {
            val isDial = lower.contains("dial ") || lower == "dial"
            val targetName = lower
                .replace(Regex("(?i)make\\s+a\\s+call\\s+to"), "")
                .replace(Regex("(?i)call\\s+to"), "")
                .replace(Regex("(?i)call"), "")
                .replace(Regex("(?i)phone"), "")
                .replace(Regex("(?i)dial"), "")
                .replace(Regex("(?i)^to\\s+"), "")
                .replace(Regex("(?i)^the\\s+"), "")
                .replace(Regex("(?i)^my\\s+"), "")
                .replace(Regex("(?i)^please\\s+"), "")
                .trim()

            val actionStr = if (isDial) "dial" else "call"
            val speech = if (isDial) "Opening dialer, Boss..." else "Calling $targetName, Boss..."
            Log.d(tag, "FastPath matched Direct Phone Call/Dial: '$targetName' ($actionStr)")
            return AuraCommand(action = actionStr, target = targetName, speechResponse = speech)
        }

        // 6. Common Popular App Launchers
        val appKeywords = mapOf(
            "youtube" to "YouTube",
            "whatsapp" to "WhatsApp",
            "camera" to "Camera",
            "gallery" to "Gallery",
            "chrome" to "Chrome",
            "facebook" to "Facebook",
            "instagram" to "Instagram",
            "settings" to "Settings",
            "calculator" to "Calculator",
            "clock" to "Clock",
            "calendar" to "Calendar",
            "maps" to "Google Maps",
            "spotify" to "Spotify",
            "gmail" to "Gmail"
        )

        for ((keyword, appName) in appKeywords) {
            if (lower.contains(keyword)) {
                Log.d(tag, "FastPath matched App: $appName for keyword '$keyword'")
                return AuraCommand(action = "open_app", target = appName, speechResponse = "Opening $appName, Boss.")
            }
        }

        // 7. Open Web URL
        if (lower.startsWith("open website") || lower.startsWith("go to website") || lower.startsWith("go to ")) {
            val url = lower.removePrefix("open website").removePrefix("go to website").removePrefix("go to").trim()
            if (url.contains(".") || url.startsWith("http")) {
                Log.d(tag, "FastPath matched URL: $url")
                return AuraCommand(action = "open_url", target = url, speechResponse = "Opening $url, Boss.")
            }
        }

        return null
    }

    private fun parseGeminiResponseText(responseJson: String): String? {
        return try {
            val rootObject = gson.fromJson(responseJson, JsonObject::class.java) ?: return null
            val candidates = rootObject.getAsJsonArray("candidates") ?: return null
            if (candidates.size() == 0) return null

            val candidate = candidates[0].asJsonObject ?: return null
            val content = candidate.getAsJsonObject("content") ?: return null
            val parts = content.getAsJsonArray("parts") ?: return null
            if (parts.size() == 0) return null

            val firstPart = parts[0].asJsonObject ?: return null
            firstPart.get("text")?.asString
        } catch (e: Exception) {
            Log.e(tag, "Error parsing Gemini candidates JSON: ${e.localizedMessage}")
            null
        }
    }

    private fun sanitizeJsonResponse(text: String): String {
        var clean = text.trim()

        // 1. Strip markdown backtick code blocks (```json ... ``` or ``` ... ```)
        if (clean.startsWith("```json", ignoreCase = true)) {
            clean = clean.substring(7)
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3)
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length - 3)
        }
        clean = clean.trim()

        // 2. Extract JSON object substring boundaries {...}
        val firstBrace = clean.indexOf('{')
        val lastBrace = clean.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            clean = clean.substring(firstBrace, lastBrace + 1).trim()
        }

        return clean
    }
}
