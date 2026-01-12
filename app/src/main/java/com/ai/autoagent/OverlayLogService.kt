package com.ai.autoagent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * Overlay service that displays real-time logs while agent is running.
 * Can be temporarily hidden during screenshot capture to avoid blocking content.
 * Also displays completion dialog when task finishes.
 */
class OverlayLogService : Service() {
    
    companion object {
        private const val TAG = "AutoAgent"
        private const val MAX_LINES = 10
        
        @Volatile
        var instance: OverlayLogService? = null
        
        fun showLog(message: String) {
            instance?.appendLog(message)
        }
        
        /**
         * Temporarily hide overlay (for screenshot capture)
         */
        fun hide() {
            instance?.hideOverlay()
        }
        
        /**
         * Show overlay again after hiding
         */
        fun show() {
            instance?.showOverlay()
        }
        
        /**
         * Show completion dialog with task result
         */
        fun showCompletion(task: String, message: String) {
            instance?.showCompletionDialog(task, message)
        }
    }
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var logTextView: TextView? = null
    private var completionView: View? = null
    private val logLines = mutableListOf<String>()
    private var isVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createOverlay()
        Log.d(TAG, "OverlayLogService created")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Create overlay view on main thread
        mainHandler.post {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_log, null)
            logTextView = overlayView?.findViewById(R.id.log_text)
            showOverlayInternal()
        }
    }
    
    private fun showOverlay() {
        mainHandler.post {
            showOverlayInternal()
        }
    }
    
    private fun showOverlayInternal() {
        if (isVisible || overlayView == null) return
        
        // Window params
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            
            // Use TYPE_APPLICATION_OVERLAY for Android O+
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            
            // Flags for non-interactive overlay
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            
            // Transparent background
            format = PixelFormat.TRANSLUCENT
            
            // Position at top
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        
        try {
            windowManager?.addView(overlayView, params)
            isVisible = true
            Log.d(TAG, "Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }
    
    private fun hideOverlay() {
        mainHandler.post {
            hideOverlayInternal()
        }
    }
    
    private fun hideOverlayInternal() {
        if (!isVisible || overlayView == null) return
        
        try {
            windowManager?.removeView(overlayView)
            isVisible = false
            Log.d(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding overlay", e)
        }
    }
    
    private fun showCompletionDialog(task: String, message: String) {
        mainHandler.post {
            showCompletionDialogInternal(task, message)
        }
    }
    
    private fun showCompletionDialogInternal(task: String, message: String) {
        if (completionView != null) {
            // Already showing, dismiss first
            dismissCompletionDialogInternal()
        }
        
        // Create completion dialog view
        completionView = LayoutInflater.from(this).inflate(R.layout.completion_dialog, null)
        
        // Set task and message
        completionView?.findViewById<TextView>(R.id.task_text)?.text = "任务: $task"
        completionView?.findViewById<TextView>(R.id.message_text)?.text = message
        
        // Setup close button
        completionView?.findViewById<Button>(R.id.close_button)?.setOnClickListener {
            dismissCompletionDialog()
        }
        
        // Window params for dialog
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            
            // Dialog should be interactive
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            
            format = PixelFormat.TRANSLUCENT
            
            // Center on screen
            gravity = Gravity.CENTER
        }
        
        try {
            windowManager?.addView(completionView, params)
            Log.d(TAG, "Completion dialog shown")
            
            // Auto dismiss after 5 seconds
            mainHandler.postDelayed({
                dismissCompletionDialog()
            }, 5000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show completion dialog", e)
        }
    }
    
    private fun dismissCompletionDialog() {
        mainHandler.post {
            dismissCompletionDialogInternal()
        }
    }
    
    private fun dismissCompletionDialogInternal() {
        if (completionView != null) {
            try {
                windowManager?.removeView(completionView)
                completionView = null
                Log.d(TAG, "Completion dialog dismissed")
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing completion dialog", e)
            }
        }
    }
    
    fun appendLog(message: String) {
        logLines.add(message)
        
        // Keep only last MAX_LINES
        while (logLines.size > MAX_LINES) {
            logLines.removeAt(0)
        }
        
        // Update UI on main thread
        mainHandler.post {
            logTextView?.text = logLines.joinToString("\n")
        }
    }
    
    fun clearLogs() {
        logLines.clear()
        mainHandler.post {
            logTextView?.text = ""
        }
    }
    
    override fun onDestroy() {
        dismissCompletionDialog()
        if (isVisible) {
            hideOverlay()
        }
        instance = null
        super.onDestroy()
    }
}

