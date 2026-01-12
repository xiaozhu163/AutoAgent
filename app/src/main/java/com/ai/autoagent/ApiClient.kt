package com.ai.autoagent

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val max_tokens: Int = 3000,
    val temperature: Double = 0.0,
    val top_p: Double = 0.85
)

data class Message(
    val role: String,
    val content: Any // Can be String or List<ContentItem>
)

data class ContentItem(
    val type: String,
    val text: String? = null,
    val image_url: ImageUrl? = null
)

data class ImageUrl(
    val url: String
)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: MessageBody
)

data class MessageBody(
    val content: String
)

class ApiClient(val apiKey: String, val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4") {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
        
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Synchronous chat call - returns response content or error message
     */
    fun chatSync(messages: List<Message>, model: String = "autoglm-phone"): Pair<String?, String?> {
        val requestBodyData = ChatRequest(
            model = model,
            messages = messages
        )
        
        val json = gson.toJson(requestBodyData)
        val body = json.toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
            
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Pair(null, "Error: ${response.code} $errorBody")
            } else {
                val respString = response.body?.string()
                val chatResp = gson.fromJson(respString, ChatResponse::class.java)
                val content = chatResp.choices.firstOrNull()?.message?.content
                Pair(content, null)
            }
        } catch (e: IOException) {
            Pair(null, "Network Error: ${e.message}")
        } catch (e: Exception) {
            Pair(null, "Parse Error: ${e.message}")
        }
    }

    fun chat(messages: List<Message>, model: String = "autoglm-phone", callback: (String?, String?) -> Unit) {
        val requestBodyData = ChatRequest(
            model = model,
            messages = messages
        )
        
        val json = gson.toJson(requestBodyData)
        val body = json.toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
            
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    callback(null, "Error: ${response.code} $errorBody")
                    return
                }
                
                try {
                    val respString = response.body?.string()
                    val chatResp = gson.fromJson(respString, ChatResponse::class.java)
                    val content = chatResp.choices.firstOrNull()?.message?.content
                    callback(content, null)
                } catch (e: Exception) {
                    callback(null, "Parse Error: ${e.message}")
                }
            }
        })
    }
}

