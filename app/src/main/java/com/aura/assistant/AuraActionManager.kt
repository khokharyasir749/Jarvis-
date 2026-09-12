package com.aura.assistant

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuraActionManager(private val context: Context) {

    private val tag = "AuraAction"

    // Phonetic & Nickname Dictionary for Common Apps
    private val appPhoneticAliases = mapOf(
        "WhatsApp" to listOf("whatsapp", "wts ap", "whatsap", "whats app", "watsapp", "wat sap", "wats up", "watsap", "whatapp", "whatsapp chat", "watsapp chat"),
        "YouTube" to listOf("youtube", "u tube", "you tube", "utube", "ytube", "yutube"),
        "Chrome" to listOf("chrome", "browser", "google chrome", "web browser"),
        "Camera" to listOf("camera", "camra", "tasweer", "camer", "kmra"),
        "Instagram" to listOf("instagram", "insta", "ig"),
        "Facebook" to listOf("facebook", "fb", "face book"),
        "Spotify" to listOf("spotify", "spotifi", "spoty"),
        "Gallery" to listOf("gallery", "photos", "ai gallery", "album"),
        "Phone" to listOf("phone", "dialer", "calling"),
        "Contacts" to listOf("contacts", "contact list"),
        "Settings" to listOf("settings", "setting"),
        "Calculator" to listOf("calculator", "calc", "hisab")
    )

    private val knownPackages = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "settings" to "com.android.settings",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "spotify" to "com.spotify.music"
    )

    /**
     * Returns formatted current time and date string.
     */
    fun getCurrentTimeAndDate(): String {
        val formatted = SimpleDateFormat("hh:mm a, EEEE dd MMMM yyyy", Locale.getDefault()).format(Date())
        Log.d("JarvisMaster", "Final Action Executed: GET_TIME_DATE ($formatted)")
        return "It is $formatted, Boss."
    }

    /**
     * Triggers System AlarmClock to set an alarm for specified hour and minute.
     */
    fun setAlarm(hour: Int, minute: Int): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: SET_ALARM ($hour:$minute)")
            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(alarmIntent)
            "Setting alarm for ${String.format(Locale.US, "%02d:%02d", hour, minute)}, Boss."
        } catch (e: Exception) {
            Log.e(tag, "Set alarm error: ${e.localizedMessage}")
            "Failed to set alarm, Boss."
        }
    }

    /**
     * Opens standard SMS messenger pre-filled with recipient number and text body.
     */
    fun sendSms(target: String, messageText: String): String {
        return try {
            val number = findContactPhoneNumber(target) ?: target.replace(Regex("[^0-9+]"), "")
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", messageText)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening SMS for $target, Boss."
        } catch (e: Exception) {
            dialNumber(target)
        }
    }

    /**
     * Searches installed launchable apps by matching phonetic aliases, exact label,
     * fuzzy similarity score (> 70%), or package name, and fires launch intent.
     *
     * @param appName Spoken app name or query.
     * @return Spoken user feedback string.
     */
    fun openApp(appName: String): String {
        return try {
            val rawQuery = appName.trim().lowercase()
            var cleanQuery = rawQuery
                .replace(Regex("(?i)^the\\s+"), "")
                .replace(Regex("(?i)^my\\s+"), "")
                .replace(Regex("(?i)^please\\s+"), "")
                .replace(Regex("(?i)^zara\\s+"), "")
                .replace(Regex("(?i)\\s+app$"), "")
                .trim()

            if (cleanQuery.isEmpty()) {
                return "App name cannot be empty, Boss."
            }

            // 1. Phonetic Alias Map Lookup
            for ((canonicalName, aliases) in appPhoneticAliases) {
                if (aliases.any { it.equals(cleanQuery, ignoreCase = true) || calculateSimilarity(it, cleanQuery) >= 0.80 }) {
                    Log.d(tag, "Phonetic Alias Match: '$cleanQuery' -> resolved to '$canonicalName'")
                    cleanQuery = canonicalName.lowercase()
                    break
                }
            }

            // 2. Fast-Path Known Packages Launch
            val targetPkg = knownPackages[cleanQuery]
            if (targetPkg != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launchIntent)
                    Log.d("JarvisMaster", "Final Action Executed: FAST_PATH_OPEN_APP ($cleanQuery -> $targetPkg)")
                    return "Opening $appName, Boss."
                }
            }

            // 3. Fallback Intent Triggers for YouTube and Camera
            if (cleanQuery == "youtube") {
                try {
                    val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(ytIntent)
                    Log.d("JarvisMaster", "Final Action Executed: YOUTUBE_INTENT_FALLBACK")
                    return "Opening YouTube, Boss."
                } catch (_: Exception) {}
            }

            if (cleanQuery == "camera") {
                try {
                    val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(camIntent)
                    Log.d("JarvisMaster", "Final Action Executed: CAMERA_INTENT_FALLBACK")
                    return "Opening Camera, Boss."
                } catch (_: Exception) {}
            }

            Log.d(tag, "openApp searching installed apps for resolved query: '$cleanQuery'")
            val packageManager = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList: List<ResolveInfo> = packageManager.queryIntentActivities(mainIntent, 0)

            // 4. Exact label match (case-insensitive)
            var targetApp = resolveInfoList.find { resolveInfo ->
                val label = resolveInfo.loadLabel(packageManager).toString()
                label.equals(cleanQuery, ignoreCase = true)
            }

            // 5. Exact word boundary / token match on app label
            if (targetApp == null) {
                targetApp = resolveInfoList.find { resolveInfo ->
                    val label = resolveInfo.loadLabel(packageManager).toString().lowercase()
                    val words = label.split(Regex("\\s+"))
                    words.contains(cleanQuery)
                }
            }

            // 6. Normalized Levenshtein Fuzzy Similarity Match (> 70% threshold)
            if (targetApp == null) {
                var bestMatchScore = 0.0
                var bestMatchApp: ResolveInfo? = null

                for (resolveInfo in resolveInfoList) {
                    val label = resolveInfo.loadLabel(packageManager).toString()
                    val similarity = calculateSimilarity(cleanQuery, label)
                    if (similarity > bestMatchScore && similarity >= 0.70) {
                        bestMatchScore = similarity
                        bestMatchApp = resolveInfo
                    }
                }

                if (bestMatchApp != null) {
                    Log.d(tag, "Fuzzy Similarity Match found: '${bestMatchApp.loadLabel(packageManager)}' (score=$bestMatchScore)")
                    targetApp = bestMatchApp
                }
            }

            // 7. Fallback: Package name match
            if (targetApp == null) {
                targetApp = resolveInfoList.find { resolveInfo ->
                    resolveInfo.activityInfo.packageName.contains(cleanQuery, ignoreCase = true)
                }
            }

            if (targetApp != null) {
                val packageName = targetApp.activityInfo.packageName
                val appLabel = targetApp.loadLabel(packageManager).toString()

                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launchIntent)
                    Log.d("JarvisMaster", "Final Action Executed: OPEN_APP ($appLabel)")
                    "Opening $appLabel, Boss."
                } else {
                    Log.e(tag, "Unable to get launch intent for package: $packageName")
                    "Unable to launch $appLabel, Boss."
                }
            } else {
                Log.w(tag, "App '$appName' not found on device.")
                "App '$appName' not found on device, Boss."
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to open app '$appName': ${e.localizedMessage}")
            "Failed to open app '$appName': ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    /**
     * Toggles Bluetooth hardware state safely using BluetoothAdapter.
     *
     * @param enable True to turn on, false to turn off.
     * @return Spoken user feedback string.
     */
    @Suppress("MissingPermission")
    fun toggleBluetooth(enable: Boolean): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: TOGGLE_BLUETOOTH (enable=$enable)")
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") android.bluetooth.BluetoothAdapter.getDefaultAdapter()

            if (adapter == null) {
                return "Bluetooth hardware is unavailable on this device, Boss."
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPerm) {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return "Opening Bluetooth settings, Boss."
                }
            }

            @Suppress("DEPRECATION")
            if (enable) {
                if (!adapter.isEnabled) {
                    adapter.enable()
                    "Turning on Bluetooth, Boss."
                } else {
                    "Bluetooth is already on, Boss."
                }
            } else {
                if (adapter.isEnabled) {
                    adapter.disable()
                    "Turning off Bluetooth, Boss."
                } else {
                    "Bluetooth is already off, Boss."
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Bluetooth error: ${e.localizedMessage}")
            "Failed to toggle Bluetooth, Boss."
        }
    }

    /**
     * Adjusts system screen brightness level.
     *
     * @param direction 'up', 'down', 'increase', or 'decrease'.
     * @return Spoken user feedback string.
     */
    fun adjustBrightness(direction: String): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: ADJUST_BRIGHTNESS ($direction)")
            val hasWriteSettings = Settings.System.canWrite(context)
            if (!hasWriteSettings) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "Please grant Write Settings permission to adjust brightness, Boss."
            }

            val contentResolver = context.contentResolver
            val currentBrightness = try {
                Settings.System.getInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                )
            } catch (_: Exception) {
                128
            }

            val delta = 60
            val lowerDir = direction.lowercase()
            val isIncrease = lowerDir.contains("up") || lowerDir.contains("increase") || lowerDir.contains("raise")
            val newBrightness = if (isIncrease) {
                (currentBrightness + delta).coerceAtMost(255)
            } else {
                (currentBrightness - delta).coerceAtLeast(15)
            }

            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                newBrightness
            )

            if (isIncrease) "Brightness increased, Boss." else "Brightness decreased, Boss."
        } catch (e: Exception) {
            Log.e(tag, "Brightness adjustment error: ${e.localizedMessage}")
            "Failed to adjust brightness, Boss."
        }
    }

    /**
     * Opens system camera app or fires ACTION_IMAGE_CAPTURE intent.
     *
     * @return Spoken user feedback string.
     */
    fun openCamera(): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: OPEN_CAMERA")
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening Camera, Boss."
        } catch (_: Exception) {
            openApp("Camera")
        }
    }

    /**
     * Calculates normalized string similarity score between 0.0 and 1.0 using Levenshtein distance.
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        val str1 = s1.lowercase().trim().replace(Regex("[^a-z0-9]"), "")
        val str2 = s2.lowercase().trim().replace(Regex("[^a-z0-9]"), "")

        if (str1 == str2) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0

        val distance = levenshteinDistance(str1, str2)
        val maxLength = maxOf(str1.length, str2.length)

        return 1.0 - (distance.toDouble() / maxLength)
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length

        var distance = IntArray(lhsLen + 1) { it }
        var newDistance = IntArray(lhsLen + 1)

        for (j in 1..rhsLen) {
            newDistance[0] = j
            for (i in 1..lhsLen) {
                val match = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                val costDelete = distance[i] + 1
                val costInsert = newDistance[i - 1] + 1
                val costReplace = distance[i - 1] + match
                newDistance[i] = minOf(costDelete, minOf(costInsert, costReplace))
            }
            val swap = distance
            distance = newDistance
            newDistance = swap
        }

        return distance[lhsLen]
    }

    /**
     * Controls device flashlight (torch mode) safely using Camera2 API.
     *
     * @param enable True to turn torch on, false to turn torch off.
     * @return User feedback string.
     */
    fun toggleFlashlight(enable: Boolean): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: TOGGLE_FLASHLIGHT (enable=$enable)")
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return "Camera service unavailable on this device, Boss."

            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return "Camera service unavailable on this device, Boss."

            cameraManager.setTorchMode(cameraId, enable)
            Log.d(tag, "Flashlight setTorchMode success on camera $cameraId (enable=$enable)")
            if (enable) "Flashlight turned on, Boss." else "Flashlight turned off, Boss."
        } catch (e: Exception) {
            Log.e(tag, "Failed to toggle flashlight: ${e.localizedMessage}")
            "Failed to toggle flashlight: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    /**
     * Queries device battery level and charging status via BatteryManager.
     *
     * @return User feedback string.
     */
    fun getBatteryStatus(): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: BATTERY_STATUS")
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val isCharging = batteryManager?.isCharging ?: false

            if (level != -1) {
                if (isCharging) {
                    "Battery is at $level% and charging, Boss."
                } else {
                    "Battery is at $level%, Boss."
                }
            } else {
                "Unable to query battery status, Boss."
            }
        } catch (e: Exception) {
            Log.e(tag, "Battery status error: ${e.localizedMessage}")
            "Unable to query battery status, Boss."
        }
    }

    /**
     * Gathers hardware diagnostic metrics (Battery, Available RAM, Network state).
     *
     * @return Formatted JARVIS status report string.
     */
    @Suppress("MissingPermission")
    fun getDeviceDiagnostics(): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: DEVICE_DIAGNOSTICS")

            // 1. Battery Percentage & Charging Status
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val isCharging = batteryManager?.isCharging ?: false

            val batteryStr = if (level != -1) {
                if (isCharging) "$level% and charging" else "$level%"
            } else {
                "unknown"
            }

            // 2. Available RAM
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)

            val availRamGb = String.format(Locale.US, "%.1f", memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0))

            // 3. Network Connectivity State
            var networkType = "offline"
            val hasNetPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_NETWORK_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (hasNetPerm) {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeNet = connectivityManager?.activeNetwork
                    val caps = connectivityManager?.getNetworkCapabilities(activeNet)
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            networkType = "Wi-Fi"
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            networkType = "Mobile Data"
                        } else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            networkType = "online"
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val activeNetInfo = connectivityManager?.activeNetworkInfo
                    if (activeNetInfo != null && activeNetInfo.isConnected) {
                        networkType = activeNetInfo.typeName ?: "online"
                    }
                }
            }

            val networkStr = if (networkType != "offline") "network connection ($networkType) is solid" else "network is offline"

            "All systems operational, Boss. Battery is at $batteryStr, $availRamGb GB RAM available, and $networkStr."
        } catch (e: Exception) {
            Log.e(tag, "Device diagnostics error: ${e.localizedMessage}")
            "All systems operational, Boss. Ready for your command."
        }
    }

    /**
     * Adjusts media stream volume via AudioManager.
     *
     * @param direction 'up', 'down', or 'mute'.
     * @return User feedback string.
     */
    fun adjustVolume(direction: String): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: ADJUST_VOLUME ($direction)")
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return "Audio service unavailable, Boss."

            when (direction.lowercase()) {
                "up", "raise", "increase" -> {
                    audioManager.adjustStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_RAISE,
                        android.media.AudioManager.FLAG_SHOW_UI
                    )
                    "Volume increased, Boss."
                }
                "down", "lower", "decrease" -> {
                    audioManager.adjustStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_LOWER,
                        android.media.AudioManager.FLAG_SHOW_UI
                    )
                    "Volume decreased, Boss."
                }
                "mute" -> {
                    audioManager.adjustStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_MUTE,
                        android.media.AudioManager.FLAG_SHOW_UI
                    )
                    "Muted, Boss."
                }
                else -> "Volume adjusted, Boss."
            }
        } catch (e: Exception) {
            Log.e(tag, "Volume adjustment error: ${e.localizedMessage}")
            "Failed to adjust volume, Boss."
        }
    }

    /**
     * Dispatches media key events (play/pause, next, previous) to active media player.
     *
     * @param mediaAction 'play_pause', 'next', or 'previous'.
     * @return User feedback string.
     */
    fun controlMedia(mediaAction: String): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: CONTROL_MEDIA ($mediaAction)")
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return "Audio service unavailable, Boss."

            val keyCode = when (mediaAction.lowercase()) {
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }

            val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)

            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

            when (mediaAction.lowercase()) {
                "next" -> "Playing next track, Boss."
                "previous" -> "Playing previous track, Boss."
                else -> "Media toggled, Boss."
            }
        } catch (e: Exception) {
            Log.e(tag, "Media control error: ${e.localizedMessage}")
            "Failed to control media playback, Boss."
        }
    }

    /**
     * Opens a web URL in the default browser.
     *
     * @param url Web address to open.
     * @return User feedback string.
     */
    fun openUrl(url: String): String {
        return try {
            var formattedUrl = url.trim()
            if (formattedUrl.isEmpty()) {
                return "URL cannot be empty."
            }

            if (!formattedUrl.startsWith("http://", ignoreCase = true) &&
                !formattedUrl.startsWith("https://", ignoreCase = true)) {
                formattedUrl = "https://$formattedUrl"
            }

            Log.d("JarvisMaster", "Final Action Executed: OPEN_URL ($formattedUrl)")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "Opening $formattedUrl, Boss."
        } catch (e: Exception) {
            Log.e(tag, "Failed to open URL '$url': ${e.localizedMessage}")
            "Failed to open URL '$url': ${e.localizedMessage ?: "Invalid URL or browser unavailable"}"
        }
    }

    /**
     * Opens the system phone dialer with the specified phone number or contact query.
     * Does not require CALL_PHONE permission as it uses ACTION_DIAL.
     *
     * @param phoneNumber Target phone number or query.
     * @return User feedback string.
     */
    fun dialNumber(phoneNumber: String): String {
        return try {
            val cleanNumber = sanitizeContactQuery(phoneNumber)
            if (cleanNumber.isEmpty()) {
                Log.d("JarvisMaster", "Final Action Executed: DIAL_NUMBER (blank)")
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "Opening dialer, Boss..."
            }

            val digitsOnly = cleanNumber.replace(Regex("[^0-9+]"), "")
            val uriNumber = if (digitsOnly.isNotEmpty()) digitsOnly else cleanNumber
            Log.d("JarvisMaster", "Final Action Executed: DIAL_NUMBER ($cleanNumber)")

            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$uriNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "Opening dialer for $cleanNumber, Boss."
        } catch (e: Exception) {
            Log.e(tag, "Error opening dialer: ${e.localizedMessage}")
            "Opening dialer, Boss..."
        }
    }

    /**
     * Makes a direct phone call using CALL_PHONE permission or falls back to dialer.
     * If a contact name is provided, searches contacts via READ_CONTACTS permission.
     *
     * @param target Contact name or phone number.
     * @return User feedback string.
     */
    fun makeDirectCall(target: String): String {
        return try {
            val sanitizedQuery = sanitizeContactQuery(target)
            Log.d("JarvisMaster", "Final Action Executed: MAKE_CALL ($sanitizedQuery)")

            if (sanitizedQuery.isEmpty()) {
                return "Contact name or phone number cannot be empty, Boss."
            }

            val isNumericNumber = sanitizedQuery.matches(Regex("^[0-9+\\s()-]+$")) &&
                    sanitizedQuery.replace(Regex("[^0-9+]"), "").length >= 3

            val hasCallPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            var numberToCall: String? = if (isNumericNumber) sanitizedQuery else null
            var resolvedContactName: String? = null

            if (!isNumericNumber) {
                val foundNumber = findContactPhoneNumber(sanitizedQuery)
                if (!foundNumber.isNullOrBlank()) {
                    numberToCall = foundNumber
                    resolvedContactName = sanitizedQuery
                }
            }

            if (!numberToCall.isNullOrBlank()) {
                val formattedUriNumber = numberToCall.replace(Regex("[^0-9+]"), "")
                if (hasCallPermission) {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$formattedUriNumber")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(callIntent)
                    if (resolvedContactName != null) "Calling $resolvedContactName, Boss..." else "Calling $sanitizedQuery, Boss..."
                } else {
                    Log.w(tag, "CALL_PHONE permission not granted. Falling back to dialer...")
                    dialNumber(formattedUriNumber)
                }
            } else {
                Log.w(tag, "Contact '$sanitizedQuery' not found in contacts. Opening dialer...")
                dialNumber(sanitizedQuery)
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during makeDirectCall for '$target': ${e.localizedMessage}")
            dialNumber(target)
        }
    }

    /**
     * Initiates direct WhatsApp VoIP call or opens WhatsApp chat if VoIP item is unavailable.
     *
     * @param target Contact name or phone number.
     * @return User feedback string.
     */
    fun whatsappCall(target: String): String {
        return try {
            val cleanTarget = sanitizeWhatsAppContactName(target)
            Log.d(tag, "whatsappCall requested for target='$target', cleanTarget='$cleanTarget'")

            if (cleanTarget.isEmpty()) {
                return openApp("WhatsApp")
            }

            val searchedNumber = findContactPhoneNumber(cleanTarget)
            if (!searchedNumber.isNullOrBlank()) {
                val cleanNumber = searchedNumber.replace("+", "").replace(" ", "").trim()
                Log.d("JarvisMaster", "Final Action Executed: WHATSAPP_CALL ($cleanTarget -> $cleanNumber)")
                val chatIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(chatIntent)

                // Trigger deep UI voice call traversal
                AuraAccessibilityService.instance?.automateWhatsAppVoiceCall()
                return "Calling $cleanTarget on WhatsApp."
            }

            Log.w("AuraContact", "Contact '$cleanTarget' not found in contacts!")
            "Boss, contact book mein $cleanTarget nahi mila."
        } catch (e: Exception) {
            Log.e(tag, "Error initiating WhatsApp call for '$target': ${e.localizedMessage}")
            openApp("WhatsApp")
        }
    }

    /**
     * Opens a direct WhatsApp chat window for a contact or phone number.
     *
     * @param target Contact name or phone number.
     * @return User feedback string.
     */
    fun openWhatsAppChat(target: String): String {
        return try {
            val cleanTarget = sanitizeWhatsAppContactName(target)
            Log.d("JarvisMaster", "Final Action Executed: OPEN_WHATSAPP_CHAT ($cleanTarget)")

            if (cleanTarget.isEmpty()) {
                return openApp("WhatsApp")
            }

            val searchedNumber = findContactPhoneNumber(cleanTarget)
            if (!searchedNumber.isNullOrBlank()) {
                val cleanNumber = searchedNumber.replace("+", "").replace(" ", "").trim()
                val chatIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(chatIntent)
                return "Opening WhatsApp chat for $cleanTarget, Boss..."
            }

            // Fallback: Open WhatsApp and trigger deep UI accessibility search
            openApp("WhatsApp")
            AuraAccessibilityService.instance?.automateWhatsAppSearchAndSend(cleanTarget)
            "Opening WhatsApp chat for $cleanTarget, Boss..."
        } catch (e: Exception) {
            Log.e(tag, "Error opening WhatsApp chat: ${e.localizedMessage}")
            openApp("WhatsApp")
        }
    }

    /**
     * Fires a Universal Web Search intent via Google Search or Default Browser.
     *
     * @param query Search query string.
     * @return User feedback string.
     */
    fun webSearch(query: String): String {
        return try {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty()) {
                return "Search query cannot be empty, Boss."
            }

            Log.d("JarvisMaster", "Final Action Executed: WEB_SEARCH ($cleanQuery)")
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, cleanQuery)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(searchIntent)
            "Searching for $cleanQuery, Boss."
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch web search: ${e.localizedMessage}")
            openUrl(query)
        }
    }

    /**
     * Parses contact name and message body from voice prompt and opens WhatsApp chat pre-filled.
     *
     * @param prompt Raw voice prompt string or contact name.
     * @return User feedback string.
     */
    fun whatsappMessage(prompt: String): String {
        return try {
            val (contactName, messageBody) = parseWhatsAppMessagePrompt(prompt)
            Log.d("JarvisMaster", "Final Action Executed: WHATSAPP_MESSAGE ($contactName)")

            if (contactName.isEmpty()) {
                return openApp("WhatsApp")
            }

            val searchedNumber = findContactPhoneNumber(contactName)
            if (!searchedNumber.isNullOrBlank()) {
                val cleanNumber = searchedNumber.replace("+", "").replace(" ", "").trim()
                val encodedText = Uri.encode(messageBody)

                AuraAccessibilityService.autoSendWhatsApp = true

                val msgIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=$encodedText")
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(msgIntent)

                // Trigger auto-send button clicker in accessibility service
                AuraAccessibilityService.instance?.clickSendButton()
                return "Message bhej diya gaya hai."
            }

            // Fallback: Open WhatsApp and trigger deep UI accessibility search and send
            openApp("WhatsApp")
            AuraAccessibilityService.instance?.automateWhatsAppSearchAndSend(contactName, messageBody)
            "Sending message to $contactName, Boss..."
        } catch (e: Exception) {
            Log.e(tag, "Error sending WhatsApp message: ${e.localizedMessage}")
            openApp("WhatsApp")
        }
    }

    private fun findWhatsAppVoipDataId(contactName: String): Long? {
        val projection = arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME)
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "%$contactName%")

        var matchedDataId: Long? = null

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val dataIdIdx = it.getColumnIndexOrThrow(ContactsContract.Data._ID)
                    val nameIdx = it.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                    val dataId = it.getLong(dataIdIdx)
                    val displayName = if (nameIdx != -1) it.getString(nameIdx) else ""

                    matchedDataId = dataId
                    Log.d(tag, "Found WhatsApp VoIP dataId: $dataId for $displayName")
                } else {
                    Log.w(tag, "No WhatsApp VoIP dataId cursor matched for '$contactName'")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error querying WhatsApp VoIP Data ID: ${e.localizedMessage}")
        }
        return matchedDataId
    }

    private fun findWhatsAppProfileDataId(contactName: String): Long? {
        val projection = arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME)
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.profile", "%$contactName%")

        var matchedDataId: Long? = null

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val dataIdIdx = it.getColumnIndexOrThrow(ContactsContract.Data._ID)
                    val dataId = it.getLong(dataIdIdx)
                    matchedDataId = dataId
                    Log.d(tag, "Found WhatsApp Profile dataId: $dataId for $contactName")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error querying WhatsApp Profile Data ID: ${e.localizedMessage}")
        }
        return matchedDataId
    }

    private fun parseWhatsAppMessagePrompt(prompt: String): Pair<String, String> {
        val clean = prompt.trim()
            .replace(Regex("(?i)\\s+on\\s+whatsapp"), "")
            .replace(Regex("(?i)\\s+via\\s+whatsapp"), "")
            .replace(Regex("(?i)\\s+whatsapp"), "")
            .replace(Regex("(?i)^whatsapp\\s+"), "")
            .replace(Regex("(?i)bhai\\s*"), "")
            .replace(Regex("(?i)please\\s*"), "")
            .trim()

        val sendToRegex = Regex("(?i)^(?:send|text|message)\\s+(.+)\\s+to\\s+(.+)$")
        val sendToMatch = sendToRegex.find(clean)
        if (sendToMatch != null) {
            val messageBody = sendToMatch.groupValues[1].trim()
            val contactName = sanitizeWhatsAppContactName(sendToMatch.groupValues[2])
            return Pair(contactName, messageBody)
        }

        val messageContactRegex = Regex("(?i)^(?:send|text|message)\\s+([a-zA-Z0-9\\s]+?)\\s+(.+)$")
        val messageContactMatch = messageContactRegex.find(clean)
        if (messageContactMatch != null) {
            val contactName = sanitizeWhatsAppContactName(messageContactMatch.groupValues[1])
            val messageBody = messageContactMatch.groupValues[2].trim()
            return Pair(contactName, messageBody)
        }

        val parts = clean.split(Regex("\\s+"))
        if (parts.size >= 2) {
            val firstWord = parts.first()
            val remaining = parts.drop(1).joinToString(" ")
            return Pair(sanitizeWhatsAppContactName(firstWord), remaining)
        }

        return Pair(sanitizeWhatsAppContactName(clean), "")
    }

    private fun sanitizeWhatsAppContactName(name: String): String {
        return name.trim()
            .replace(Regex("(?i)^call\\s+"), "")
            .replace(Regex("(?i)^dial\\s+"), "")
            .replace(Regex("(?i)^message\\s+"), "")
            .replace(Regex("(?i)^text\\s+"), "")
            .replace(Regex("(?i)^send\\s+"), "")
            .replace(Regex("(?i)^open\\s+"), "")
            .replace(Regex("(?i)^show\\s+"), "")
            .replace(Regex("(?i)^chat\\s+"), "")
            .replace(Regex("(?i)chat\\s*$"), "")
            .replace(Regex("(?i)kholo\\s*$"), "")
            .replace(Regex("(?i)milao\\s*$"), "")
            .replace(Regex("(?i)karo\\s*$"), "")
            .replace(Regex("(?i)^of\\s+"), "")
            .replace(Regex("(?i)^to\\s+"), "")
            .replace(Regex("(?i)^the\\s+"), "")
            .replace(Regex("(?i)^my\\s+"), "")
            .replace(Regex("(?i)bhai"), "")
            .replace(Regex("(?i)please"), "")
            .replace(Regex("(?i)on\\s+whatsapp"), "")
            .replace(Regex("(?i)via\\s+whatsapp"), "")
            .replace(Regex("(?i)whatsapp"), "")
            .trim()
    }

    private fun formatInternationalPhoneNumber(number: String): String {
        var clean = number.replace(Regex("[^0-9+]"), "")
        if (clean.startsWith("03") && clean.length == 11) {
            clean = "923" + clean.substring(2)
        } else if (clean.startsWith("00")) {
            clean = clean.substring(2)
        } else if (clean.startsWith("+")) {
            clean = clean.replace("+", "")
        } else if (clean.startsWith("0") && clean.length == 11) {
            clean = "92" + clean.substring(1)
        } else if (!clean.startsWith("92") && clean.length == 10) {
            clean = "1$clean"
        }
        return clean
    }

    private fun sanitizeContactQuery(query: String): String {
        val clean = query.trim()
            .replace(Regex("(?i)^call\\s+"), "")
            .replace(Regex("(?i)^dial\\s+"), "")
            .replace(Regex("(?i)^phone\\s+"), "")
            .replace(Regex("(?i)^to\\s+"), "")
            .replace(Regex("(?i)^the\\s+"), "")
            .replace(Regex("(?i)^my\\s+"), "")
            .replace(Regex("(?i)^please\\s+"), "")
            .trim()
        return clean
    }

    private fun findContactPhoneNumber(nameQuery: String): String? {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasReadPermission) {
            Log.w(tag, "READ_CONTACTS permission is NOT granted!")
            return null
        }

        val trimmedName = nameQuery.trim()
        if (trimmedName.isEmpty()) return null

        Log.d(tag, "Searching contacts address book for query: '$trimmedName'")

        return try {
            val contentResolver = context.contentResolver
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$trimmedName%"),
                null
            )

            var exactMatchNumber: String? = null
            var partialMatchNumber: String? = null

            cursor?.use {
                val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                while (it.moveToNext()) {
                    if (numberIdx != -1) {
                        val number = it.getString(numberIdx)
                        val displayName = if (nameIdx != -1) it.getString(nameIdx) else ""

                        if (!number.isNullOrBlank()) {
                            Log.d(tag, "Contact matched: name='$displayName', number='$number'")
                            if (displayName.equals(trimmedName, ignoreCase = true)) {
                                exactMatchNumber = number
                                break
                            } else if (partialMatchNumber == null && displayName.contains(trimmedName, ignoreCase = true)) {
                                partialMatchNumber = number
                            }
                        }
                    }
                }
            }

            val finalNumber = exactMatchNumber ?: partialMatchNumber
            if (finalNumber != null) {
                val clean = formatInternationalPhoneNumber(finalNumber)
                Log.d("AuraContact", "Searched: $trimmedName, Found: $clean")
                clean
            } else {
                Log.w("AuraContact", "No contact match found in address book for query '$trimmedName'")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "Error querying contacts: ${e.localizedMessage}")
            null
        }
    }

    fun goHome(): String {
        return try {
            Log.d("JarvisMaster", "Final Action Executed: GO_HOME")
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            "Going home, Boss."
        } catch (e: Exception) {
            Log.e(tag, "Failed to navigate home: ${e.localizedMessage}")
            "Failed to navigate home, Boss."
        }
    }

    fun goBack(): String {
        Log.d("JarvisMaster", "Final Action Executed: GO_BACK")
        return AuraAccessibilityService.goBack(context)
    }

    fun lockScreen(): String {
        Log.d("JarvisMaster", "Final Action Executed: LOCK_SCREEN")
        return AuraAccessibilityService.lockScreen(context)
    }

    fun closeAllApps(): String {
        Log.d("JarvisMaster", "Final Action Executed: CLOSE_ALL_APPS")
        return AuraAccessibilityService.closeAllApps(context)
    }

    fun sendCurrentMessage(): String {
        Log.d("JarvisMaster", "Final Action Executed: SEND_CURRENT_MESSAGE")
        val service = AuraAccessibilityService.instance
        return if (service != null) {
            val success = service.clickSendButton()
            if (success) "Sent, Boss." else "Could not find Send button, Boss."
        } else {
            "Please enable Aura Accessibility Service to click Send, Boss."
        }
    }
}
