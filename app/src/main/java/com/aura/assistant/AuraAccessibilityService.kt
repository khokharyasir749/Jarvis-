package com.aura.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AuraAccessibilityService : AccessibilityService() {

    private val tag = "AuraAccessibility"
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(tag, "AuraAccessibilityService connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Auto-Click WhatsApp Send Button
        if (autoSendWhatsApp && event.packageName == "com.whatsapp") {
            findAndClickSendButton(rootInActiveWindow)
        }

        // 2. Auto-Click Recents 'Clear All' Button
        if (isClosingAllApps) {
            findAndClickClearAllButton(rootInActiveWindow)
        }
    }

    /**
     * Traverses rootInActiveWindow recursively and aggregates visible on-screen text,
     * allowing Aura to understand what is currently displayed on user's screen.
     */
    fun getCurrentScreenText(): String {
        val sb = StringBuilder()
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    sb.append(text).append("\n")
                }
                val desc = node.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty() && desc != text) {
                    sb.append(desc).append("\n")
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }
        try {
            val root = rootInActiveWindow
            if (root != null) {
                traverse(root)
            } else {
                val windowList = windows
                if (windowList != null) {
                    for (w in windowList) {
                        w?.root?.let { traverse(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error reading screen text: ${e.localizedMessage}")
        }
        return sb.toString().trim()
    }

    /**
     * Automated WhatsApp search and chat opening UI traversal.
     */
    fun automateWhatsAppSearchAndSend(contactName: String, messageText: String = "") {
        Log.d(tag, "automateWhatsAppSearchAndSend initiated for contact='$contactName'")
        mainHandler.postDelayed({
            try {
                val root = rootInActiveWindow ?: return@postDelayed

                // Step 1: Find and click Search Icon in WhatsApp by contentDescription or View ID
                var searchClicked = false
                val searchViewIds = arrayOf("com.whatsapp:id/menuitem_search", "com.whatsapp:id/search_button")
                for (viewId in searchViewIds) {
                    val searchNodes = root.findAccessibilityNodeInfosByViewId(viewId)
                    if (searchNodes != null && searchNodes.isNotEmpty()) {
                        for (n in searchNodes) {
                            if (n != null && n.isEnabled) {
                                val target = if (n.isClickable) n else n.parent ?: n
                                searchClicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                if (searchClicked) break
                            }
                        }
                    }
                    if (searchClicked) break
                }

                if (!searchClicked) {
                    val searchTexts = arrayOf("Search", "Talaash", "search")
                    for (st in searchTexts) {
                        val nodes = root.findAccessibilityNodeInfosByText(st)
                        if (nodes != null && nodes.isNotEmpty()) {
                            for (n in nodes) {
                                if (n != null && (n.isClickable || n.parent?.isClickable == true)) {
                                    val target = if (n.isClickable) n else n.parent
                                    searchClicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                                    if (searchClicked) break
                                }
                            }
                        }
                        if (searchClicked) break
                    }
                }

                // Step 2: Paste contact name into Search Box
                mainHandler.postDelayed({
                    val activeRoot = rootInActiveWindow ?: return@postDelayed
                    val searchInputNodes = activeRoot.findAccessibilityNodeInfosByViewId("com.whatsapp:id/search_src_text")
                    if (searchInputNodes != null && searchInputNodes.isNotEmpty()) {
                        val inputNode = searchInputNodes[0]
                        val arguments = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contactName)
                        }
                        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        Log.d(tag, "Set search text to '$contactName'")

                        // Step 3: Wait 400ms and click top result row
                        mainHandler.postDelayed({
                            val searchResultRoot = rootInActiveWindow ?: return@postDelayed
                            val contactResultNodes = searchResultRoot.findAccessibilityNodeInfosByText(contactName)
                            if (contactResultNodes != null && contactResultNodes.isNotEmpty()) {
                                for (cn in contactResultNodes) {
                                    if (cn != null) {
                                        var target: AccessibilityNodeInfo? = cn
                                        while (target != null && !target.isClickable) {
                                            target = target.parent
                                        }
                                        val rowClicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                                        Log.d(tag, "Clicked search result row for '$contactName': $rowClicked")
                                        if (rowClicked) break
                                    }
                                }
                            }

                            // Step 4: If message is provided, set text and click send
                            if (messageText.isNotBlank()) {
                                mainHandler.postDelayed({
                                    val chatRoot = rootInActiveWindow ?: return@postDelayed
                                    val msgInputNodes = chatRoot.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
                                    if (msgInputNodes != null && msgInputNodes.isNotEmpty()) {
                                        val msgNode = msgInputNodes[0]
                                        val msgArgs = Bundle().apply {
                                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageText)
                                        }
                                        msgNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, msgArgs)
                                        Log.d(tag, "Pasted message text into WhatsApp chat")

                                        mainHandler.postDelayed({
                                            clickSendButton()
                                        }, 250)
                                    }
                                }, 500)
                            }
                        }, 400)
                    }
                }, 300)
            } catch (e: Exception) {
                Log.e(tag, "Error automating WhatsApp search and send: ${e.localizedMessage}")
            }
        }, 300)
    }

    /**
     * Automated WhatsApp Voice Call UI Traversal inside active chat.
     */
    fun automateWhatsAppVoiceCall() {
        Log.d(tag, "automateWhatsAppVoiceCall initiated. Waiting 500ms for chat window to render...")
        mainHandler.postDelayed({
            try {
                val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
                rootInActiveWindow?.let { candidateRoots.add(it) }

                val windowList = windows
                if (windowList != null && windowList.isNotEmpty()) {
                    for (w in windowList) {
                        val rootNode = w?.root
                        if (rootNode != null && !candidateRoots.contains(rootNode)) {
                            candidateRoots.add(rootNode)
                        }
                    }
                }

                var callClicked = false
                for (root in candidateRoots) {
                    // 1. Check Voice Call View ID
                    val callNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/voice_call")
                    if (callNodes != null && callNodes.isNotEmpty()) {
                        for (n in callNodes) {
                            if (n != null && n.isEnabled) {
                                val target = if (n.isClickable) n else n.parent ?: n
                                callClicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                Log.d(tag, "Clicked Voice Call via View ID: $callClicked")
                                if (callClicked) break
                            }
                        }
                    }

                    // 2. Check Content Descriptions
                    if (!callClicked) {
                        callClicked = findAndClickCallIcon(root)
                    }

                    if (callClicked) break
                }

                // Step 2: Auto-confirm call popup modal if displayed ("Start voice call?")
                mainHandler.postDelayed({
                    val activeRoot = rootInActiveWindow ?: return@postDelayed
                    val confirmButtonNodes = activeRoot.findAccessibilityNodeInfosByViewId("android:id/button1")
                    if (confirmButtonNodes != null && confirmButtonNodes.isNotEmpty()) {
                        confirmButtonNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(tag, "Clicked system call confirmation button 'android:id/button1'")
                    } else {
                        val callTextNodes = activeRoot.findAccessibilityNodeInfosByText("Call")
                        if (callTextNodes != null && callTextNodes.isNotEmpty()) {
                            for (ct in callTextNodes) {
                                if (ct != null && (ct.isClickable || ct.parent?.isClickable == true)) {
                                    val target = if (ct.isClickable) ct else ct.parent
                                    target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    Log.d(tag, "Clicked system call confirmation button via text 'Call'")
                                    break
                                }
                            }
                        }
                    }
                }, 300)
            } catch (e: Exception) {
                Log.e(tag, "Error performing WhatsApp voice call traversal: ${e.localizedMessage}")
            }
        }, 500)
    }

    private fun findAndClickCallIcon(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains("Voice call", ignoreCase = true) || desc.contains("Audio call", ignoreCase = true) || desc.contains("Call", ignoreCase = true)) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            Log.d(tag, "Clicked call icon via content description '$desc': $clicked")
            if (clicked) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && findAndClickCallIcon(child)) {
                return true
            }
        }
        return false
    }

    /**
     * Finds and clicks the WhatsApp Send button on the active window using multi-strategy fallback.
     */
    fun clickSendButton(): Boolean {
        var clicked = false
        Log.d("AuraAccess", "clickSendButton initiated. Searching active windows for com.whatsapp...")

        try {
            val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
            rootInActiveWindow?.let { candidateRoots.add(it) }

            val windowList = windows
            if (windowList != null && windowList.isNotEmpty()) {
                for (w in windowList) {
                    val rootNode = w?.root
                    if (rootNode != null && !candidateRoots.contains(rootNode)) {
                        candidateRoots.add(rootNode)
                    }
                }
            }

            for (root in candidateRoots) {
                // 1. Check multiple WhatsApp Send View IDs
                val sendViewIds = arrayOf(
                    "com.whatsapp:id/send",
                    "com.whatsapp:id/send_container",
                    "com.whatsapp:id/entry_send"
                )
                for (viewId in sendViewIds) {
                    val sendNodes = root.findAccessibilityNodeInfosByViewId(viewId)
                    if (sendNodes != null && sendNodes.isNotEmpty()) {
                        for (n in sendNodes) {
                            if (n != null && n.isEnabled) {
                                val target = if (n.isClickable) n else n.parent ?: n
                                clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                Log.d("AuraAccess", "Clicked send via ViewID '$viewId': $clicked")
                                if (clicked) break
                            }
                        }
                    }
                    if (clicked) break
                }

                // 2. Check Content Descriptions / Text
                if (!clicked) {
                    val textList = arrayOf("Send", "Bhejein", "send", "Send message")
                    for (text in textList) {
                        val textNodes = root.findAccessibilityNodeInfosByText(text)
                        if (textNodes != null && textNodes.isNotEmpty()) {
                            for (n in textNodes) {
                                if (n != null && (n.isClickable || n.parent?.isClickable == true)) {
                                    val target = if (n.isClickable) n else n.parent
                                    clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                                    Log.d("AuraAccess", "Clicked send via Text '$text': $clicked")
                                    if (clicked) break
                                }
                            }
                        }
                        if (clicked) break
                    }
                }

                // 3. Fallback: Recursive check for ImageButton / ImageView
                if (!clicked) {
                    clicked = findAndClickImageSendButton(root)
                }

                if (clicked) break
            }

            if (clicked) {
                Log.d("JarvisAction", "WhatsApp send executed via: node_click")
            } else {
                Log.w("JarvisAction", "Node click failed. Attempting Gesture Dispatch Fallback...")
                clicked = dispatchGestureSendTap()
            }
        } catch (e: Exception) {
            Log.e("AuraAccess", "Error inside clickSendButton: ${e.localizedMessage}")
        }

        Log.d("AuraAccess", "Clicked send final result: $clicked")
        return clicked
    }

    private fun dispatchGestureSendTap(): Boolean {
        return try {
            val metrics = resources.displayMetrics
            val x = metrics.widthPixels * 0.92f
            val y = metrics.heightPixels * 0.94f

            val path = android.graphics.Path().apply {
                moveTo(x, y)
            }

            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d("JarvisAction", "WhatsApp send executed via: gesture_dispatch (x=$x, y=$y)")
                }
            }, null)

            Log.d("JarvisAction", "Dispatched send gesture tap to ($x, $y): $dispatched")
            dispatched
        } catch (e: Exception) {
            Log.e("JarvisAction", "Error dispatching gesture send tap: ${e.localizedMessage}")
            false
        }
    }

    private fun findAndClickImageSendButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val className = node.className?.toString() ?: ""
        if ((className.contains("ImageButton") || className.contains("ImageView")) && node.isClickable && node.isEnabled) {
            val contentDesc = node.contentDescription?.toString() ?: ""
            if (contentDesc.contains("Send", ignoreCase = true) || contentDesc.contains("Bhejein", ignoreCase = true) || contentDesc.isBlank()) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d("AuraAccess", "Clicked send image button via class match: $className, desc: '$contentDesc': $clicked")
                return clicked
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && findAndClickImageSendButton(child)) {
                return true
            }
        }
        return false
    }

    private fun findAndClickSendButton(node: AccessibilityNodeInfo?) {
        if (node == null || !autoSendWhatsApp) return

        try {
            // Search by WhatsApp send button view ID
            val sendNodes = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            if (sendNodes != null && sendNodes.isNotEmpty()) {
                for (n in sendNodes) {
                    if (n != null && n.isEnabled) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        autoSendWhatsApp = false
                        Log.d(tag, "Successfully auto-clicked WhatsApp send button via ViewID!")
                        return
                    }
                }
            }

            // Search by text / content description "Send"
            val textNodes = node.findAccessibilityNodeInfosByText("Send")
            if (textNodes != null && textNodes.isNotEmpty()) {
                for (n in textNodes) {
                    if (n != null && (n.isClickable || n.parent?.isClickable == true)) {
                        val target = if (n.isClickable) n else n.parent
                        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        autoSendWhatsApp = false
                        Log.d(tag, "Successfully auto-clicked WhatsApp send button via Text!")
                        return
                    }
                }
            }

            // Recursive child traversal
            for (i in 0 until node.childCount) {
                if (!autoSendWhatsApp) break
                val child = node.getChild(i)
                if (child != null) {
                    findAndClickSendButton(child)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error auto-clicking WhatsApp send button: ${e.localizedMessage}")
        }
    }

    private fun findAndClickClearAllButton(node: AccessibilityNodeInfo?) {
        if (node == null || !isClosingAllApps) return

        try {
            val clearTexts = arrayOf("Clear all", "Clear All", "Close all", "Close All", "CLEAR ALL", "Clear", "Close")
            for (text in clearTexts) {
                val nodes = node.findAccessibilityNodeInfosByText(text)
                if (nodes != null && nodes.isNotEmpty()) {
                    for (n in nodes) {
                        if (n != null && (n.isClickable || n.parent?.isClickable == true)) {
                            val target = if (n.isClickable) n else n.parent
                            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            isClosingAllApps = false
                            Log.d(tag, "Successfully clicked 'Clear All' recents button via text '$text'!")
                            return
                        }
                    }
                }
            }

            // Search common launcher / systemui clear_all View IDs (e.g. Transsion / Infinix launcher)
            val idList = arrayOf(
                "com.transsion.launcher:id/clear_all",
                "com.transsion.launcher:id/clear_all_recents",
                "com.android.systemui:id/clear_all",
                "com.android.systemui:id/clear_all_recents_button",
                "com.google.android.apps.nexuslauncher:id/clear_all"
            )
            for (viewId in idList) {
                val idNodes = node.findAccessibilityNodeInfosByViewId(viewId)
                if (idNodes != null && idNodes.isNotEmpty()) {
                    for (n in idNodes) {
                        if (n != null && n.isEnabled) {
                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            isClosingAllApps = false
                            Log.d(tag, "Successfully clicked 'Clear All' via ViewID '$viewId'!")
                            return
                        }
                    }
                }
            }

            // Recursive child traversal
            for (i in 0 until node.childCount) {
                if (!isClosingAllApps) break
                val child = node.getChild(i)
                if (child != null) {
                    findAndClickClearAllButton(child)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error clicking Clear All recents button: ${e.localizedMessage}")
        }
    }

    fun triggerCloseAllApps() {
        Log.d(tag, "triggerCloseAllApps requested. Firing GLOBAL_ACTION_RECENTS...")
        isClosingAllApps = true
        performGlobalAction(GLOBAL_ACTION_RECENTS)

        // Try searching and clicking 'Clear All' after recents UI renders
        mainHandler.postDelayed({
            findAndClickClearAllButton(rootInActiveWindow)
        }, 500)

        mainHandler.postDelayed({
            if (isClosingAllApps) {
                findAndClickClearAllButton(rootInActiveWindow)
            }
        }, 1200)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        var instance: AuraAccessibilityService? = null
        var autoSendWhatsApp: Boolean = false
        var isClosingAllApps: Boolean = false

        /**
         * Checks if AuraAccessibilityService is currently enabled by the user in Settings.
         */
        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val serviceName = "${context.packageName}/${AuraAccessibilityService::class.java.canonicalName}"
            return enabledServices.contains(serviceName, ignoreCase = true)
        }

        /**
         * Opens System Accessibility Settings so user can enable Aura service.
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * Triggers Android GLOBAL_ACTION_BACK to go back.
         */
        fun goBack(context: Context): String {
            if (!isServiceEnabled(context)) {
                openAccessibilitySettings(context)
                return "Please enable Aura Accessibility Service in settings to go back."
            }

            val currentService = instance
            return if (currentService != null) {
                val success = currentService.performGlobalAction(GLOBAL_ACTION_BACK)
                if (success) "Going back, Boss." else "Failed to go back."
            } else {
                openAccessibilitySettings(context)
                "Please toggle Aura service in accessibility settings."
            }
        }

        /**
         * Triggers Android GLOBAL_ACTION_LOCK_SCREEN to instantly lock/turn off the screen.
         */
        fun lockScreen(context: Context): String {
            if (!isServiceEnabled(context)) {
                openAccessibilitySettings(context)
                return "Please enable Aura Accessibility Service in settings to allow screen locking."
            }

            val currentService = instance
            return if (currentService != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val success = currentService.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    if (success) "Locking screen." else "Failed to lock screen."
                } else {
                    "Lock screen action requires Android 9 (Pie) or higher."
                }
            } else {
                openAccessibilitySettings(context)
                "Accessibility service active, but instance not ready. Please toggle Aura service in settings."
            }
        }

        /**
         * Triggers Android GLOBAL_ACTION_RECENTS and clicks 'Clear All' button.
         */
        fun closeAllApps(context: Context): String {
            if (!isServiceEnabled(context)) {
                openAccessibilitySettings(context)
                return "Please enable Aura Accessibility Service in settings to close all apps."
            }

            val currentService = instance
            return if (currentService != null) {
                currentService.triggerCloseAllApps()
                "Closing all recent apps, Boss."
            } else {
                openAccessibilitySettings(context)
                "Please toggle Aura service in accessibility settings."
            }
        }
    }
}
