package com.ai.autoagent

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.autoagent.ui.theme.AutoAgentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf("94d6d1c4dfb0455480afc97d18edfc0c.d6rQ9fBZMArvP3i5") }
    var taskPrompt by remember { mutableStateOf("打开设置") }
    var logs by remember { mutableStateOf("Ready...\n") }
    var isRunning by remember { mutableStateOf(false) }
    
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Media Projection Result Launcher
    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            logs += "Permission granted, setting projection data...\n"
            
            // Pass projection data to Service via static variables
            val metrics = context.resources.displayMetrics
            
            // Log BEFORE setting
            logs += "Setting: code=${result.resultCode}, data=${result.data != null}, w=${metrics.widthPixels}\n"
            
            ScreenCaptureService.pendingResultCode = result.resultCode
            ScreenCaptureService.pendingProjectionData = result.data
            ScreenCaptureService.pendingWidth = metrics.widthPixels
            ScreenCaptureService.pendingHeight = metrics.heightPixels
            ScreenCaptureService.pendingDensity = metrics.densityDpi
            
            // Verify it was set
            logs += "Verified: code=${ScreenCaptureService.pendingResultCode}, data=${ScreenCaptureService.pendingProjectionData != null}\n"
            
            // Start Service (Service will create MediaProjection AFTER startForeground)
            try {
                val intent = Intent(context, ScreenCaptureService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                logs += "Service start command sent\n"
                Toast.makeText(context, "Service starting...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                logs += "ERROR: ${e.message}\n"
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            logs += "Screen Capture Permission Denied (resultCode=${result.resultCode}).\n"
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = taskPrompt,
            onValueChange = { taskPrompt = it },
            label = { Text("Task") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Overlay toggle control
        var overlayEnabled by remember { mutableStateOf(OverlayLogService.ENABLE_OVERLAY) }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("显示日志浮层:", modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = overlayEnabled,
                onCheckedChange = { enabled ->
                    overlayEnabled = enabled
                    OverlayLogService.setOverlayEnabled(enabled)
                    android.widget.Toast.makeText(
                        context,
                        if (enabled) "浮层已启用" else "浮层已禁用",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Button(onClick = {
                // Request Media Projection
                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpManager.createScreenCaptureIntent())
            }) {
                Text("Start Service")
            }
            
            Spacer(modifier = Modifier.width(8.dp))

            Button(onClick = {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }) {
                Text("Open Settings")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Status Indicators & Permission Checks
        var accessibilityConnected by remember { mutableStateOf(false) }
        var captureReady by remember { mutableStateOf(false) }
        var hasOverlayPermission by remember { mutableStateOf(false) }
        var hasNotificationPermission by remember { mutableStateOf(false) }
        var serviceInstanceExists by remember { mutableStateOf(false) }
        var hasPendingData by remember { mutableStateOf(false) }

        // Notification Permission Launcher
        val notificationLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { }

        // Poll status every 500ms for faster feedback
        LaunchedEffect(Unit) {
            while (true) {
                accessibilityConnected = AutoAccessibilityService.instance != null
                serviceInstanceExists = ScreenCaptureService.instance != null
                captureReady = ScreenCaptureService.instance?.isReady == true
                hasPendingData = ScreenCaptureService.pendingProjectionData != null
                hasOverlayPermission = android.provider.Settings.canDrawOverlays(context)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    hasNotificationPermission = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    hasNotificationPermission = true
                }
                delay(500)
            }
        }
        
        // --- Permissions UI ---
        Text("Permissions Check:", style = MaterialTheme.typography.titleMedium)
        
        // 1. Notification (Android 13+)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Notification: ${if (hasNotificationPermission) "Granted ✅" else "Missing ❌"}",
                 color = if (hasNotificationPermission) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red)
            if (!hasNotificationPermission && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Grant")
                }
            }
        }
        
        // 2. Overlay
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Overlay: ${if (hasOverlayPermission) "Granted ✅" else "Missing ❌"}",
                 color = if (hasOverlayPermission) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red)
            if (!hasOverlayPermission) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                     val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
                                       android.net.Uri.parse("package:${context.packageName}"))
                     context.startActivity(intent)
                }) {
                    Text("Grant")
                }
            }
        }

        // 3. Accessibility
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Accessibility: ${if (accessibilityConnected) "Connected ✅" else "Disconnected ❌"}",
                 color = if (accessibilityConnected) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red)
            if (!accessibilityConnected) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
             
        Text("Screen Capture Service: ${if (captureReady) "Ready ✅" else "Not Ready ❌"}",
             color = if (captureReady) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red)
        
        // Debug info
        Text("  → Service Instance: ${if (serviceInstanceExists) "Exists" else "NULL"}",
             color = androidx.compose.ui.graphics.Color.Gray,
             style = MaterialTheme.typography.bodySmall)
        Text("  → Pending Data: ${if (hasPendingData) "Has Data" else "None"}",
             color = androidx.compose.ui.graphics.Color.Gray,
             style = MaterialTheme.typography.bodySmall)
             
        Spacer(modifier = Modifier.height(16.dp))

        Row {
            Button(
                onClick = {
                    if (isRunning) {
                        isRunning = false
                        logs += "Stopped by user.\n"
                        // Stop overlay service
                        context.stopService(Intent(context, OverlayLogService::class.java))
                    } else {
                        isRunning = true
                        // Start overlay service
                        context.startService(Intent(context, OverlayLogService::class.java))
                        
                        scope.launch(Dispatchers.IO) {
                            runAgent(context, apiKey, taskPrompt) { log ->
                                scope.launch(Dispatchers.Main) {
                                    logs += "$log\n"
                                    // Also send to overlay
                                    OverlayLogService.showLog(log)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                isRunning = false
                                logs += "Task Finished.\n"
                                // Stop overlay service
                                context.stopService(Intent(context, OverlayLogService::class.java))
                            }
                        }
                    }
                },
                enabled = !isRunning || isRunning // Always enable to allow stop
            ) {
                Text(if (isRunning) "Stop" else "Run Task")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Logs:")
        Box(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(scrollState)) {
            Text(text = logs)
        }
    }
}

suspend fun runAgent(context: Context, apiKey: String, task: String, logCallback: (String) -> Unit) {
    val service = ScreenCaptureService.instance
    val accessibility = AutoAccessibilityService.instance
    
    if (service == null) {
        logCallback("Error: ScreenCaptureService is not running.")
        return
    }
    if (accessibility == null) {
        logCallback("Error: AccessibilityService is not connected. Please enable it in Settings.")
        return
    }
    
    val apiClient = ApiClient(apiKey)
    val actionHandler = ActionHandler(context, accessibility)
    val maxSteps = 100
    var step = 0
    
    // Get screen dimensions
    val metrics = context.resources.displayMetrics
    val screenWidth = metrics.widthPixels
    val screenHeight = metrics.heightPixels
    
    // Initialize conversation context with system prompt
    val messages = mutableListOf<Message>(
        Message("system", SystemPrompt.get())
    )
    
    logCallback("Task: $task")
    logCallback("Screen: ${screenWidth}x${screenHeight}")
    
    // First step: capture screen and send with task
    var isFirst = true
    
    while (step < maxSteps) {
        step++
        logCallback("Step $step...")
        
        // 1. Wait for UI to settle
        delay(1000)
        
        // 2. Capture screen
        val base64Img = withContext(Dispatchers.IO) {
            service.captureScreenBase64()
        }
        if (base64Img == null) {
            logCallback("Failed to capture screen.")
            break
        }
        
        // 3. Get current app info
        val currentApp = accessibility.getCurrentApp()
        val screenInfo = SystemPrompt.buildScreenInfo(currentApp)
        
        // 4. Build message content
        val textContent = if (isFirst) {
            "$task\n\n** Screen Info **\n$screenInfo"
        } else {
            "** Screen Info **\n$screenInfo"
        }
        isFirst = false
        
        val contentItems = listOf(
            ContentItem("image_url", image_url = ImageUrl("data:image/jpeg;base64,$base64Img")),
            ContentItem("text", text = textContent)
        )
        messages.add(Message("user", contentItems))
        
        // 5. Call API
        logCallback("Calling API...")
        val (response, error) = withContext(Dispatchers.IO) {
            apiClient.chatSync(messages)
        }
        
        if (error != null) {
            logCallback("API Error: $error")
            break
        }
        
        if (response == null) {
            logCallback("Empty response from API")
            break
        }
        
        // 6. Parse thinking and action
        val (thinking, actionStr) = actionHandler.parseResponse(response)
        if (thinking.isNotEmpty()) {
            logCallback("Thinking: ${thinking.take(100)}...")
        }
        logCallback("Action: $actionStr")
        
        // 7. Parse action
        val parsedAction = try {
            actionHandler.parseAction(actionStr)
        } catch (e: Exception) {
            logCallback("Parse Error: ${e.message}")
            break
        }
        
        // 8. Remove image from last user message to save memory
        val lastMessage = messages.lastOrNull()
        if (lastMessage?.role == "user" && lastMessage.content is List<*>) {
            @Suppress("UNCHECKED_CAST")
            val contentList = lastMessage.content as List<ContentItem>
            val textOnly = contentList.filter { it.type == "text" }
            messages[messages.lastIndex] = Message("user", textOnly)
        }
        
        // 9. Add assistant response to context
        messages.add(Message("assistant", "<think>$thinking</think><answer>$actionStr</answer>"))
        
        // 10. Execute action
        val result = actionHandler.execute(parsedAction, screenWidth, screenHeight)
        
        if (result.message != null) {
            logCallback("Result: ${result.message}")
        }
        
        // 11. Check if finished
        if (result.shouldFinish) {
            val completionMessage = result.message ?: "Done"
            logCallback("✅ Task completed: $completionMessage")
            // Show completion dialog
            OverlayLogService.showCompletion(task, completionMessage)
            break
        }
        
        // Wait for action to take effect
        delay(500)
    }
    
    if (step >= maxSteps) {
        logCallback("⚠️ Max steps reached")
        OverlayLogService.showCompletion(task, "已达到最大步数限制")
    }
    
    logCallback("Agent finished.")
}