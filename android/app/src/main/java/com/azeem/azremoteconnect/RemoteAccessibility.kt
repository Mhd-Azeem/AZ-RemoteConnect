package com.azeem.azremoteconnect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: RemoteAccessibility? = null
    }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
    // Only invoked after explicit remote-touch consent and host approval.
    // Does not inspect or store the current field's text; sends a new value to the focused input.
    fun replaceFocusedText(value: String): Boolean {
        if (value.isEmpty() || value.length > 200) return false
        val target = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!target.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
    fun click(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 85)).build()
        dispatchGesture(gesture, null, null)
    }
    fun swipe(x: Float, y: Float, endX: Float, endY: Float) {
        val path = Path().apply { moveTo(x, y); lineTo(endX, endY) }
        dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 240)).build(), null, null)
    }
}
