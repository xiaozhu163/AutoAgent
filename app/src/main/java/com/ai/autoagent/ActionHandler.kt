package com.ai.autoagent

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Action handler that parses and executes AutoGLM actions.
 * Matches the Python implementation in phone_agent/actions/handler.py
 */
class ActionHandler(
    private val context: Context,
    private val accessibilityService: AutoAccessibilityService
) {
    companion object {
        private const val TAG = "AutoAgent"
    }
    
    data class ActionResult(
        val success: Boolean,
        val shouldFinish: Boolean,
        val message: String? = null
    )
    
    data class ParsedAction(
        val type: String, // "do" or "finish"
        val action: String?, // action name like "Tap", "Swipe", etc.
        val params: Map<String, Any>
    )
    
    /**
     * Parse action from model response.
     * Handles formats like:
     * - do(action="Tap", element=[500, 500])
     * - finish(message="Task completed")
     */
    fun parseAction(response: String): ParsedAction {
        val trimmed = response.trim()
        Log.d(TAG, "Parsing action: $trimmed")
        
        return when {
            trimmed.startsWith("finish(") -> {
                val message = extractStringParam(trimmed, "message") ?: ""
                ParsedAction("finish", null, mapOf("message" to message))
            }
            trimmed.startsWith("do(") -> {
                val action = extractStringParam(trimmed, "action") ?: "Unknown"
                val params = mutableMapOf<String, Any>("action" to action)
                
                // Parse element=[x,y]
                extractArrayParam(trimmed, "element")?.let { params["element"] = it }
                // Parse start=[x,y] and end=[x,y] for swipe
                extractArrayParam(trimmed, "start")?.let { params["start"] = it }
                extractArrayParam(trimmed, "end")?.let { params["end"] = it }
                // Parse text="xxx" for type
                extractStringParam(trimmed, "text")?.let { params["text"] = it }
                // Parse app="xxx" for launch
                extractStringParam(trimmed, "app")?.let { params["app"] = it }
                // Parse duration="x seconds" for wait
                extractStringParam(trimmed, "duration")?.let { params["duration"] = it }
                // Parse message for sensitive operations
                extractStringParam(trimmed, "message")?.let { params["message"] = it }
                
                ParsedAction("do", action, params)
            }
            else -> {
                // Fallback: treat as finish with message
                ParsedAction("finish", null, mapOf("message" to trimmed))
            }
        }
    }
    
    private fun extractStringParam(text: String, paramName: String): String? {
        // Match paramName="value" or paramName='value'
        val regex = Regex("""$paramName=["']([^"']*)["']""")
        return regex.find(text)?.groupValues?.get(1)
    }
    
    private fun extractArrayParam(text: String, paramName: String): List<Int>? {
        // Match paramName=[x, y] or paramName=[x,y]
        val regex = Regex("""$paramName=\[(\d+)\s*,\s*(\d+)\]""")
        val match = regex.find(text) ?: return null
        return listOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt()
        )
    }
    
    /**
     * Execute a parsed action.
     */
    suspend fun execute(action: ParsedAction, screenWidth: Int, screenHeight: Int): ActionResult {
        if (action.type == "finish") {
            return ActionResult(
                success = true,
                shouldFinish = true,
                message = action.params["message"] as? String
            )
        }
        
        return when (action.action) {
            "Tap" -> handleTap(action.params, screenWidth, screenHeight)
            "Swipe" -> handleSwipe(action.params, screenWidth, screenHeight)
            "Type", "Type_Name" -> handleType(action.params)
            "Launch" -> handleLaunch(action.params)
            "Back" -> handleBack()
            "Home" -> handleHome()
            "Double Tap" -> handleDoubleTap(action.params, screenWidth, screenHeight)
            "Long Press" -> handleLongPress(action.params, screenWidth, screenHeight)
            "Wait" -> handleWait(action.params)
            "Take_over" -> handleTakeover(action.params)
            "Note" -> ActionResult(true, false, "Note recorded")
            "Call_API" -> ActionResult(true, false, "API called")
            "Interact" -> ActionResult(true, false, "User interaction required")
            else -> ActionResult(false, false, "Unknown action: ${action.action}")
        }
    }
    
    private fun convertRelativeToAbsolute(element: List<Int>, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        // Coordinates are in 0-1000 range, convert to actual pixels
        val x = (element[0] / 1000f * screenWidth)
        val y = (element[1] / 1000f * screenHeight)
        return Pair(x, y)
    }
    
    private suspend fun handleTap(params: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = params["element"] as? List<Int>
            ?: return ActionResult(false, false, "No element coordinates")
        
        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        Log.d(TAG, "Tap at ($x, $y) - from relative (${element[0]}, ${element[1]})")
        
        return suspendCancellableCoroutine { cont ->
            accessibilityService.performTap(x, y) {
                cont.resume(ActionResult(true, false))
            }
        }
    }
    
    private suspend fun handleSwipe(params: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val start = params["start"] as? List<Int>
            ?: return ActionResult(false, false, "Missing start coordinates")
        @Suppress("UNCHECKED_CAST")
        val end = params["end"] as? List<Int>
            ?: return ActionResult(false, false, "Missing end coordinates")
        
        val (x1, y1) = convertRelativeToAbsolute(start, screenWidth, screenHeight)
        val (x2, y2) = convertRelativeToAbsolute(end, screenWidth, screenHeight)
        Log.d(TAG, "Swipe from ($x1, $y1) to ($x2, $y2)")
        
        return suspendCancellableCoroutine { cont ->
            accessibilityService.performSwipe(x1, y1, x2, y2) {
                cont.resume(ActionResult(true, false))
            }
        }
    }
    
    private fun handleType(params: Map<String, Any>): ActionResult {
        val text = params["text"] as? String ?: ""
        Log.d(TAG, "Typing: $text")
        
        val success = accessibilityService.performType(text)
        return ActionResult(success, false, if (success) "Text typed" else "No focused input field")
    }
    
    private fun handleLaunch(params: Map<String, Any>): ActionResult {
        val appName = params["app"] as? String
            ?: return ActionResult(false, false, "No app name specified")
        
        Log.d(TAG, "Launching app: $appName")
        
        // Try to find and launch the app by name
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(appName)
        
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ActionResult(true, false, "Launched $appName")
        }
        
        // Try searching by app label
        val packages = pm.getInstalledApplications(0)
        for (appInfo in packages) {
            val label = pm.getApplicationLabel(appInfo).toString()
            if (label.contains(appName, ignoreCase = true)) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return ActionResult(true, false, "Launched $label")
                }
            }
        }
        
        return ActionResult(false, false, "App not found: $appName")
    }
    
    private fun handleBack(): ActionResult {
        val success = accessibilityService.performBack()
        return ActionResult(success, false)
    }
    
    private fun handleHome(): ActionResult {
        val success = accessibilityService.performHome()
        return ActionResult(success, false)
    }
    
    private suspend fun handleDoubleTap(params: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = params["element"] as? List<Int>
            ?: return ActionResult(false, false, "No element coordinates")
        
        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        Log.d(TAG, "Double Tap at ($x, $y)")
        
        return suspendCancellableCoroutine { cont ->
            accessibilityService.performDoubleTap(x, y) {
                cont.resume(ActionResult(true, false))
            }
        }
    }
    
    private suspend fun handleLongPress(params: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = params["element"] as? List<Int>
            ?: return ActionResult(false, false, "No element coordinates")
        
        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        Log.d(TAG, "Long Press at ($x, $y)")
        
        return suspendCancellableCoroutine { cont ->
            accessibilityService.performLongPress(x, y) {
                cont.resume(ActionResult(true, false))
            }
        }
    }
    
    private suspend fun handleWait(params: Map<String, Any>): ActionResult {
        val durationStr = params["duration"] as? String ?: "1 seconds"
        val seconds = durationStr.replace("seconds", "").replace("second", "").trim().toFloatOrNull() ?: 1f
        Log.d(TAG, "Waiting for $seconds seconds")
        delay((seconds * 1000).toLong())
        return ActionResult(true, false)
    }
    
    private fun handleTakeover(params: Map<String, Any>): ActionResult {
        val message = params["message"] as? String ?: "User intervention required"
        Log.d(TAG, "Takeover requested: $message")
        // In a real implementation, this would show a dialog and wait for user
        return ActionResult(true, false, message)
    }
    
    /**
     * Parse response from model to extract thinking and action parts.
     */
    fun parseResponse(content: String): Pair<String, String> {
        // Rule 1: Check for finish(message=
        if ("finish(message=" in content) {
            val parts = content.split("finish(message=", limit = 2)
            val thinking = parts[0].trim()
            val action = "finish(message=" + parts[1]
            return Pair(thinking, action)
        }
        
        // Rule 2: Check for do(action=
        if ("do(action=" in content) {
            val parts = content.split("do(action=", limit = 2)
            val thinking = parts[0].trim()
            val action = "do(action=" + parts[1]
            return Pair(thinking, action)
        }
        
        // Rule 3: Fallback to legacy XML tag parsing
        if ("<answer>" in content) {
            val parts = content.split("<answer>", limit = 2)
            val thinking = parts[0].replace("<think>", "").replace("</think>", "").trim()
            val action = parts[1].replace("</answer>", "").trim()
            return Pair(thinking, action)
        }
        
        // Rule 4: No markers found, return content as action
        return Pair("", content)
    }
}
