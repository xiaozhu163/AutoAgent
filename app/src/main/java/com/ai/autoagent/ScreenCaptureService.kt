package com.ai.autoagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "AutoAgent"
        
        @Volatile
        var instance: ScreenCaptureService? = null
            private set
        
        // Projection data passed from Activity
        var pendingResultCode: Int = -1
        var pendingProjectionData: Intent? = null
        var pendingWidth: Int = 1080
        var pendingHeight: Int = 2400
        var pendingDensity: Int = 320
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 320

    val isReady: Boolean
        get() = mediaProjection != null && imageReader != null && virtualDisplay != null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, ">>> Service onCreate")
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, ">>> onStartCommand START")
        
        // 1. MUST start foreground FIRST before creating MediaProjection (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "capture_channel",
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "capture_channel")
                .setContentTitle("AutoAgent")
                .setContentText("Screen capture running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AutoAgent")
                .setContentText("Screen capture running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
            Log.d(TAG, ">>> startForeground OK")
        } catch (e: Exception) {
            Log.e(TAG, ">>> startForeground FAILED", e)
            stopSelf()
            return START_NOT_STICKY
        }
        
        // 2. Now safe to create MediaProjection (AFTER startForeground)
        val resultCode = pendingResultCode
        val projectionData = pendingProjectionData
        screenWidth = pendingWidth
        screenHeight = pendingHeight
        screenDensity = pendingDensity
        
        Log.d(TAG, ">>> Read pending: code=$resultCode, hasData=${projectionData != null}, w=$screenWidth")
        
        // Note: resultCode of -1 is RESULT_OK, which is valid!
        if (projectionData == null) {
            Log.e(TAG, ">>> No projection data")
            stopSelf()
            return START_NOT_STICKY
        }
        
        try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, projectionData)
            Log.d(TAG, ">>> MediaProjection created: ${mediaProjection != null}")
            
            if (mediaProjection != null) {
                // MUST register callback BEFORE creating VirtualDisplay (Android 14+)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, ">>> MediaProjection stopped")
                        virtualDisplay?.release()
                        imageReader?.close()
                        mediaProjection = null
                    }
                }, handler)
                Log.d(TAG, ">>> Callback registered")
                
                // Now safe to create VirtualDisplay
                setupVirtualDisplay()
                
                // Only clear pending data after successful initialization
                pendingResultCode = -1
                pendingProjectionData = null
                Log.d(TAG, ">>> Cleared pending data")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> MediaProjection FAILED", e)
        }
        
        Log.d(TAG, ">>> onStartCommand END - isReady=$isReady")
        return START_NOT_STICKY
    }
    
    private fun setupVirtualDisplay() {
        val w = ((screenWidth + 15) / 16) * 16
        val h = ((screenHeight + 15) / 16) * 16
        
        Log.d(TAG, ">>> Creating VirtualDisplay: ${w}x${h}")
        
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoAgentCapture",
            w, h, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
        
        Log.d(TAG, ">>> VirtualDisplay: ${virtualDisplay != null}")
    }

    fun captureScreenBase64(): String? {
        if (!isReady) {
            Log.e(TAG, "Not ready")
            return null
        }
        
        // Try to acquire latest image with retries
        var image: android.media.Image? = null
        for (attempt in 1..10) {
            try {
                image = imageReader?.acquireLatestImage()
                if (image != null) {
                    break
                }
                // If null, wait and retry
                Thread.sleep(50)
            } catch (e: Exception) {
                Log.w(TAG, "Acquire attempt $attempt failed: ${e.message}")
                Thread.sleep(50)
            }
        }
        
        if (image == null) {
            Log.e(TAG, "No image after retries")
            return null
        }
        
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val bmpWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, 
                minOf(screenWidth, bmpWidth), 
                minOf(screenHeight, image.height))
            
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 70, out)
            
            Log.d(TAG, "Captured OK")
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Capture error", e)
            null
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, ">>> onDestroy")
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
        super.onDestroy()
    }
}
