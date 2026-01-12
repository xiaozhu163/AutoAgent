package com.ai.autoagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoAccessibilityService? = null
        private const val TAG = "AutoAgent"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We might listen to window changes here if needed
    }

    override fun onInterrupt() {
        instance = null
        Log.d(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Perform a tap at (x, y)
     */
    fun performTap(x: Float, y: Float, callback: (() -> Unit)? = null) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        val gesture = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Tap at ($x, $y) completed")
                callback?.invoke()
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Tap at ($x, $y) cancelled")
            }
        }, null)
    }

    /**
     * Perform a swipe
     */
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 500, callback: (() -> Unit)? = null) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        
        val builder = GestureDescription.Builder()
        val gesture = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Swipe completed")
                callback?.invoke()
            }
        }, null)
    }

    /**
     * Input text into the currently focused field
     */
    fun performType(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }
    
    /**
     * Perform Back action (like pressing Android Back button)
     */
    fun performBack(): Boolean {
        Log.d(TAG, "Performing Back action")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
    
    /**
     * Perform Home action (like pressing Android Home button)
     */
    fun performHome(): Boolean {
        Log.d(TAG, "Performing Home action")
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }
    
    /**
     * Perform a double tap at (x, y)
     */
    fun performDoubleTap(x: Float, y: Float, callback: (() -> Unit)? = null) {
        Log.d(TAG, "Performing Double Tap at ($x, $y)")
        // First tap
        val path1 = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 50)
        
        // Second tap with small delay
        val path2 = Path().apply { moveTo(x, y) }
        val stroke2 = GestureDescription.StrokeDescription(path2, 100, 50)
        
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Double Tap completed")
                callback?.invoke()
            }
        }, null)
    }
    
    /**
     * Perform a long press at (x, y)
     */
    fun performLongPress(x: Float, y: Float, duration: Long = 1000, callback: (() -> Unit)? = null) {
        Log.d(TAG, "Performing Long Press at ($x, $y) for ${duration}ms")
        val path = Path()
        path.moveTo(x, y)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Long Press completed")
                callback?.invoke()
            }
        }, null)
    }
    
    /**
     * Get the current foreground app package name
     */
    fun getCurrentApp(): String {
        val root = rootInActiveWindow
        return root?.packageName?.toString() ?: "unknown"
    }
}

