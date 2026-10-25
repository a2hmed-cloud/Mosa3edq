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

data class FormattedUiElement(
    val id: Int,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val bounds: Rect
)

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

    private val activeElementsMap = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _isConnected.value = false
        activeElementsMap.clear()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active window observation
    }

    override fun onInterrupt() {
        activeElementsMap.clear()
    }

    /**
     * Inspects active window hierarchy, assigns unique IDs to interactable/meaningful elements,
     * and filters out sensitive password fields for privacy.
     */
    @Synchronized
    fun inspectScreenHierarchy(): Pair<List<FormattedUiElement>, String> {
        val rootNode = rootInActiveWindow ?: return Pair(emptyList(), "لا توجد نافذة نشطة أو الشاشة مقفلة.")
        activeElementsMap.clear()

        val elements = mutableListOf<FormattedUiElement>()
        var counter = 1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            // Sensitive password security check
            val isPassword = node.isPassword
            val rawText = if (isPassword) "[محمي - كلمة مرور]" else (node.text?.toString() ?: "")
            val rawDesc = if (isPassword) "" else (node.contentDescription?.toString() ?: "")
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString()?.substringAfterLast('.') ?: ""

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable

            val isMeaningful = rawText.isNotBlank() || rawDesc.isNotBlank() || isClickable || isEditable || isScrollable

            if (isMeaningful && (bounds.width() > 0 && bounds.height() > 0)) {
                val elementId = counter++
                activeElementsMap[elementId] = node

                elements.add(
                    FormattedUiElement(
                        id = elementId,
                        text = rawText.trim(),
                        contentDescription = rawDesc.trim(),
                        viewId = viewId.substringAfterLast('/'),
                        className = className,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        isScrollable = isScrollable,
                        bounds = bounds
                    )
                )
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(rootNode)

        val formattedText = if (elements.isEmpty()) {
            "الشاشة الحالية لا تحتوي على عناصر تفاعلية ظاهرة."
        } else {
            elements.take(30).joinToString("\n") { el ->
                val flags = buildList {
                    if (el.isClickable) add("قابل للنقر")
                    if (el.isEditable) add("حقل إدخال")
                    if (el.isScrollable) add("قابل للتمرير")
                }.joinToString(",")

                val desc = if (el.contentDescription.isNotBlank() && el.contentDescription != el.text) {
                    " (وصف: \"${el.contentDescription}\")"
                } else ""

                val idStr = if (el.viewId.isNotBlank()) " [id: ${el.viewId}]" else ""
                "[#${el.id}] ${el.className} \"${el.text}\"$desc$idStr | ($flags) [موقع: x=${el.bounds.centerX()}, y=${el.bounds.centerY()}]"
            }
        }

        return Pair(elements, formattedText)
    }

    /**
     * Backward-compatible snapshot method for existing callers.
     */
    fun captureScreenContentSnapshot(): List<UiElementInfo> {
        val (elements, _) = inspectScreenHierarchy()
        return elements.map {
            UiElementInfo(
                text = it.text.ifBlank { it.contentDescription },
                viewId = it.viewId,
                isClickable = it.isClickable,
                isEditable = it.isEditable,
                bounds = it.bounds
            )
        }
    }

    /**
     * Clicks an element by its indexed ID from the latest snapshot.
     */
    @Synchronized
    fun clickElementById(id: Int): Boolean {
        val node = activeElementsMap[id] ?: return false
        return performClickOnNodeOrParent(node)
    }

    /**
     * Clicks by matching visible text or content description.
     */
    fun clickByTextOrDescription(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val cleanTarget = normalizeArabicText(targetText)

        // 1. Direct matching
        val matchingNodes = rootNode.findAccessibilityNodeInfosByText(targetText)
        if (!matchingNodes.isNullOrEmpty()) {
            for (node in matchingNodes) {
                if (performClickOnNodeOrParent(node)) return true
            }
        }

        // 2. Traversal matching
        val foundNode = findNodeMatchingText(rootNode, cleanTarget)
        if (foundNode != null) {
            return performClickOnNodeOrParent(foundNode)
        }

        return false
    }

    private fun findNodeMatchingText(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null || target.isBlank()) return null

        val nodeText = normalizeArabicText(node.text?.toString() ?: "")
        val descText = normalizeArabicText(node.contentDescription?.toString() ?: "")
        val viewId = (node.viewIdResourceName ?: "").lowercase()

        if (nodeText.contains(target) || descText.contains(target) || viewId.contains(target)) {
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
                if (performClickOnNodeOrParent(node)) return true
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
        // If not directly clickable, click via coordinates if available
        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                return tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }
        return false
    }

    /**
     * Types text into a specific element by ID or the currently focused/first editable element.
     */
    @Synchronized
    fun enterText(text: String, viewIdResource: String? = null, targetElementId: Int? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        var targetNode: AccessibilityNodeInfo? = null

        if (targetElementId != null) {
            targetNode = activeElementsMap[targetElementId]
        }

        if (targetNode == null && !viewIdResource.isNullOrBlank()) {
            val list = rootNode.findAccessibilityNodeInfosByViewId(viewIdResource)
            targetNode = list?.firstOrNull { it.isEditable || it.isFocused }
        }

        if (targetNode == null) {
            targetNode = findFirstEditableNode(rootNode)
        }

        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
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
        val path = Path().apply { moveTo(x, y) }
        val gestureBuilder = GestureDescription.Builder().apply {
            addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        }
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun performGlobalBackAction(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performGlobalHomeAction(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun readAllVisibleTexts(): String {
        val (elements, _) = inspectScreenHierarchy()
        val texts = elements
            .map { it.text.ifBlank { it.contentDescription } }
            .filter { it.isNotBlank() && !it.contains("[محمي") }
            .distinct()

        return if (texts.isEmpty()) {
            "لا توجد نصوص مقروءة على الشاشة الحالية."
        } else {
            texts.joinToString("\n• ")
        }
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
