package com.example.andrea.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenActionService : AccessibilityService() {

    companion object {
        var instance: ScreenActionService? = null
    }

    private val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // 1. EYES: Convert current screen into a lightweight text list for the LLM
    fun dumpScreenUI(): String {
        val rootNode = rootInActiveWindow ?: return "Screen empty"
        nodeMap.clear()
        val sb = StringBuilder()
        var idCounter = 1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val label = text.ifEmpty { desc }

            if (node.isClickable || node.isEditable || label.isNotEmpty()) {
                nodeMap[idCounter] = node
                sb.append("[$idCounter] ")
                if (label.isNotEmpty()) sb.append("\"$label\" ")
                if (node.isClickable) sb.append("(clickable) ")
                if (node.isEditable) sb.append("(input) ")
                sb.append("\n")
                idCounter++
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(rootNode)
        return sb.toString()
    }

    // 2. HANDS: Click a specific ID chosen by the LLM
    fun clickNode(id: Int): Boolean {
        var node: AccessibilityNodeInfo? = nodeMap[id] ?: return false
        while (node != null) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            node = node.parent
        }
        return false
    }

    // Click by label search (Voice Access style)
    fun clickByLabel(labelText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetLower = labelText.lowercase()

        fun searchAndClick(node: AccessibilityNodeInfo?): Boolean {
            if (node == null || !node.isVisibleToUser) return false

            val text = node.text?.toString()?.lowercase()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""

            if (text.contains(targetLower) || desc.contains(targetLower)) {
                var curr: AccessibilityNodeInfo? = node
                while (curr != null) {
                    if (curr.isClickable) {
                        return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    curr = curr.parent
                }
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            for (i in 0 until node.childCount) {
                if (searchAndClick(node.getChild(i))) return true
            }
            return false
        }

        return searchAndClick(root)
    }

    // 3. HANDS: Type text into an input box
    fun typeText(id: Int, text: String): Boolean {
        var node: AccessibilityNodeInfo? = nodeMap[id] ?: return false
        while (node != null) {
            if (node.isEditable || node.isFocusable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            node = node.parent
        }
        return false
    }

    fun typeTextFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && (focused.isEditable || focused.isFocusable)) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        // Fallback: search for first editable node
        fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val found = findEditable(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val editable = findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // 4. NAVIGATION ACTIONS
    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun openRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val found = findScrollable(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val scrollableNode = findScrollable(root)
        if (scrollableNode != null) {
            return scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        return swipeUp()
    }

    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val found = findScrollable(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val scrollableNode = findScrollable(root)
        if (scrollableNode != null) {
            return scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        return swipeDown()
    }

    fun swipeLeft(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(width * 0.8f, height * 0.5f)
            lineTo(width * 0.2f, height * 0.5f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipeRight(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(width * 0.2f, height * 0.5f)
            lineTo(width * 0.8f, height * 0.5f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipeUp(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.8f)
            lineTo(width * 0.5f, height * 0.2f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipeDown(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.2f)
            lineTo(width * 0.5f, height * 0.8f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // 5. OPEN APPS
    fun openApp(appName: String): Boolean {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val match = resolveInfos.firstOrNull {
            val label = it.loadLabel(pm).toString()
            label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)
        }
        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
                return true
            }
        }
        return false
    }

}
