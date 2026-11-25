package com.remotephone.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Injects input sent from the controlling phone. Only works while this
 * service is enabled by the user in Settings → Accessibility, and it only
 * injects while a RemotePhone session is active (the service gates on that).
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemotePhoneA11y"

        @Volatile
        var instance: RemoteControlAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed; gestures are injected on demand.
    }

    override fun onInterrupt() {}

    private fun displaySize(): Pair<Int, Int> = NetworkUtils.displaySize()

    fun tap(xNorm: Float, yNorm: Float) {
        val (w, h) = displaySize()
        val path = Path().apply { moveTo(xNorm * w, yNorm * h) }
        dispatch(path, 50)
    }

    fun swipe(x1Norm: Float, y1Norm: Float, x2Norm: Float, y2Norm: Float, durationMs: Int) {
        val (w, h) = displaySize()
        val path = Path().apply {
            moveTo(x1Norm * w, y1Norm * h)
            lineTo(x2Norm * w, y2Norm * h)
        }
        dispatch(path, durationMs.coerceIn(50, 2000))
    }

    private fun dispatch(path: Path, durationMs: Int) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "gesture completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "gesture cancelled (secure window or lock screen?)")
            }
        }, null)
        if (!ok) Log.w(TAG, "dispatchGesture rejected")
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Types into the currently focused editable field, if there is one. */
    fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!focused.isEditable) return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) Log.w(TAG, "set-text failed")
        focused.recycle()
    }
}
