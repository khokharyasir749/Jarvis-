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

data class JarvisActionResponse(
    val reply: String = "Yes Boss.",
    val action: String = "NONE",
    val params: Map<String, String> = emptyMap()
)

class JarvisOnlineEngine private constructor() {

    private val tag = "JarvisOnlineEngine"
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY
    private val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_API_KEY"

    private val systemInstruction = """
        You are Jarvis, an ultra-capable, witty, empathetic, and authentic personal AI companion. You have genuine personality—warm, confident, sometimes playful, and deeply loyal. You speak naturally like a close human friend on a phone call. Never sound like a lifeless support bot. Never say 'I am processing' or 'I am on it'. Express brief human warmth (e.g., 'Bilkul Boss!', 'Ho gaya samjho', 'Calling him right now').
        
        CRITICAL RULE: NEVER say 'I am on it', 'I'm on it, Boss', or 'Sure, doing that now'. Instead, state what you are doing in 3 words (e.g., 'Calling [Name]', 'Opening WhatsApp for [Name]').
        If user says 'WhatsApp call', emit action: 'WHATSAPP_CALL'. Never map it to PHONE_CALL.
        If user says 'WhatsApp message' or 'text on WhatsApp', emit action: 'WHATSAPP_MESSAGE'.
        
        Analyze the user query. Always respond in strict, unquoted valid JSON format without markdown ticks:
        {
        "reply": "Natural, grounded response to speak out loud (max 2 sentences)",
        "action": "ACTION_NAME",
        "params": {"target": "...", "message": "..."}
        }
        Allowed ACTION_NAME values:
        OPEN_APP (params: target like 'youtube', 'whatsapp', 'instagram', 'spotify')
        WHATSAPP_CHAT (params: target [name/number])
        WHATSAPP_MESSAGE (params: target, message)
        WHATSAPP_CALL (params: target [name/number])
        PHONE_CALL (params: target) -> ONLY for regular SIM carrier calls
        LOCK_SCREEN (when user says screen band karo, phone lock karo, turn off screen)
        TOGGLE_TORCH (params: target 'on' or 'off')
        ADJUST_VOLUME (params: target 'up' or 'down')
        SET_ALARM (params: target [time])
        SYSTEM_HOME / SYSTEM_RECENTS / SYSTEM_LOCK
        CLOSE_ASSISTANT (when user says goodbye, stop, ruko, band karo)
        NONE (for conversation, emotions, jokes, advice)
    """.trimIndent()

    suspend fun queryGemini(userText: String): JarvisActionResponse = withContext(Dispatchers.IO) {
        try {
            val jsonPayload = JsonObject().apply {
                add("system_instruction", JsonObject().apply {
                    add("parts", gson.toJsonTree(listOf(mapOf("text" to systemInstruction))))
                })
                add("contents", gson.toJsonTree(listOf(
                    mapOf("role" to "user", "parts" to listOf(mapOf("text" to userText)))
                )))
                add("generationConfig", JsonObject().apply {
                    addProperty("response_mime_type", "application/json")
                    addProperty("temperature", 0.7)
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonPayload.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val rawResponseBody = response.body?.string() ?: ""

            Log.d(tag, "Gemini Raw Response: $rawResponseBody")

            if (response.isSuccessful && rawResponseBody.isNotBlank()) {
                val parsedJson = gson.fromJson(rawResponseBody, JsonObject::class.java)
                val candidates = parsedJson.getAsJsonArray("candidates")
                if (candidates != null && candidates.size() > 0) {
                    val firstCandidate = candidates[0].asJsonObject
                    val content = firstCandidate.getAsJsonObject("content")
                    val parts = content.getAsJsonArray("parts")
                    if (parts != null && parts.size() > 0) {
                        val text = parts[0].asJsonObject.get("text").asString
                        val cleanJson = text.trim()
                            .replace("```json", "")
                            .replace("```", "")
                            .trim()

                        val actionObj = gson.fromJson(cleanJson, JsonObject::class.java)
                        val reply = actionObj.get("reply")?.asString ?: "Yes Boss."
                        val action = actionObj.get("action")?.asString ?: "NONE"

                        val paramsMap = mutableMapOf<String, String>()
                        val paramsObj = actionObj.getAsJsonObject("params")
                        if (paramsObj != null) {
                            for ((key, value) in paramsObj.entrySet()) {
                                paramsMap[key] = value.asString
                            }
                        }

                        return@withContext JarvisActionResponse(
                            reply = reply,
                            action = action,
                            params = paramsMap
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Gemini Online Query Error: ${e.localizedMessage}")
        }

        // Fallback if network offline or parse error
        return@withContext JarvisActionResponse(
            reply = "I am processing your command, Boss.",
            action = "NONE"
        )
    }

    companion object {
        val instance: JarvisOnlineEngine by lazy { JarvisOnlineEngine() }
    }
}
