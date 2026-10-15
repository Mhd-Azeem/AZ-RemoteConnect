package com.azeem.azremoteconnect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: RemoteAccessibility? = null
    }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
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
