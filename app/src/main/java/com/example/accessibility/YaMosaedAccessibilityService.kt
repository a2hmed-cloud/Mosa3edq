package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class UiElementInfo(
    val text: String,
    val viewId: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect
)

class YaMosaedAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: YaMosaedAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected

        fun getInstance(): YaMosaedAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _isConnected.value = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active window observation
    }

    override fun onInterrupt() {}

    fun captureScreenContentSnapshot(): List<UiElementInfo> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val elementsList = mutableListOf<UiElementInfo>()
        traverseNodeTree(rootNode, elementsList)
        return elementsList
    }

    private fun traverseNodeTree(node: AccessibilityNodeInfo?, list: MutableList<UiElementInfo>) {
        if (node == null) return

        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (text.isNotBlank() || node.isClickable || node.isEditable) {
            list.add(
                UiElementInfo(
                    text = text,
                    viewId = viewId,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    bounds = bounds
                )
            )
        }

        for (i in 0 until node.childCount) {
            traverseNodeTree(node.getChild(i), list)
        }
    }

    fun clickByTextOrDescription(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val cleanTarget = normalizeArabicText(targetText)

        // 1. Direct system match
        val matchingNodes = rootNode.findAccessibilityNodeInfosByText(targetText)
        if (!matchingNodes.isNullOrEmpty()) {
            for (node in matchingNodes) {
                if (performClickOnNodeOrParent(node)) {
                    return true
                }
            }
        }

        // 2. Traversal match for contentDescription or viewId or normalized text
        val foundNode = findNodeMatchingText(rootNode, cleanTarget)
        if (foundNode != null) {
            return performClickOnNodeOrParent(foundNode)
        }

        return false
    }

    private fun findNodeMatchingText(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null

        val nodeText = normalizeArabicText(node.text?.toString() ?: "")
        val descText = normalizeArabicText(node.contentDescription?.toString() ?: "")
        val viewId = (node.viewIdResourceName ?: "").lowercase()

        if ((nodeText.contains(target) || descText.contains(target) || viewId.contains(target)) && target.isNotBlank()) {
            return node
        }

        for (i in 0 until node.childCount) {
            val res = findNodeMatchingText(node.getChild(i), target)
            if (res != null) return res
        }
        return null
    }

    fun clickByViewId(viewIdResource: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val matchingNodes = rootNode.findAccessibilityNodeInfosByViewId(viewIdResource)
        if (!matchingNodes.isNullOrEmpty()) {
            for (node in matchingNodes) {
                if (performClickOnNodeOrParent(node)) {
                    return true
                }
            }
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    fun enterText(text: String, viewIdResource: String? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        var targetNode: AccessibilityNodeInfo? = null

        if (!viewIdResource.isNullOrBlank()) {
            val list = rootNode.findAccessibilityNodeInfosByViewId(viewIdResource)
            targetNode = list?.firstOrNull { it.isEditable || it.isFocused }
        }

        if (targetNode == null) {
            targetNode = findFirstEditableNode(rootNode)
        }

        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        return false
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val result = findFirstEditableNode(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    fun performScroll(direction: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(rootNode) ?: return false

        val action = if (direction.equals("up", ignoreCase = true)) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return scrollableNode.performAction(action)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node

        for (i in 0 until node.childCount) {
            val result = findScrollableNode(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performGlobalBackAction(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performGlobalHomeAction(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun normalizeArabicText(input: String): String {
        return input.lowercase()
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .trim()
    }
}
