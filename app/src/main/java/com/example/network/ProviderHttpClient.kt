package com.example.network

import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.ApiFormat
import com.example.data.local.LocalAiServerEntity
import com.example.data.local.LocalEngineType
import com.example.data.local.ModelEntity
import com.example.data.local.ProviderEntity
import com.example.data.local.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class ConnectionTestResult(
    val status: ProviderStatus,
    val latencyMs: Long,
    val httpCode: Int? = null,
    val message: String
)

data class ChatMessagePayload(
    val role: String,
    val content: String
)

data class ChatRequestParams(
    val messages: List<ChatMessagePayload>,
    val modelId: String,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val jsonOutput: Boolean = false,
    val stream: Boolean = false,
    val toolsJson: JSONArray? = null
)

data class ChatExecutionResult(
    val success: Boolean,
    val content: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val latencyMs: Long = 0,
    val statusCode: Int = 200,
    val finishReason: String = "stop",
    val errorCode: String? = null,
    val safeErrorMessage: String? = null,
    val retryable: Boolean = false
)

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Completed(val result: ChatExecutionResult) : StreamEvent
    data class Failed(val error: ChatExecutionResult, val emittedAnyChunk: Boolean) : StreamEvent
}

class ProviderHttpClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun buildClient(timeoutSec: Int): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong().coerceIn(5, 120), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong().coerceIn(10, 300), TimeUnit.SECONDS)
        .writeTimeout(timeoutSec.toLong().coerceIn(10, 120), TimeUnit.SECONDS)
        .build()

    suspend fun testProviderConnection(
        provider: ProviderEntity,
        apiKey: String,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 20
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(provider.baseUrl, allowLocalHttp)) {
            return@withContext ConnectionTestResult(
                status = ProviderStatus.ERROR,
                latencyMs = 0,
                message = "HTTPS is required for remote endpoints (or enable Local Network Access for private hosts)."
            )
        }
        if (apiKey.isBlank() && !NetworkAddressClassifier.isPrivateOrLoopbackHost(provider.baseUrl)) {
            return@withContext ConnectionTestResult(
                status = ProviderStatus.ERROR,
                latencyMs = 0,
                message = "No active API key configured for this provider."
            )
        }

        val start = System.nanoTime()
        try {
            val url = buildModelsUrl(provider.baseUrl, provider.apiFormat)
            val request = applyAuthAndHeaders(Request.Builder().url(url).get(), provider, apiKey).build()
            buildClient(timeoutSec).newCall(request).execute().use { resp ->
                val latency = (System.nanoTime() - start) / 1_000_000L
                val bodyStr = resp.body?.string().orEmpty()
                return@withContext when {
                    resp.isSuccessful -> ConnectionTestResult(
                        status = ProviderStatus.CONNECTED,
                        latencyMs = latency,
                        httpCode = resp.code,
                        message = "Connected (${resp.code}) in ${latency}ms"
                    )
                    resp.code == 429 -> ConnectionTestResult(
                        status = ProviderStatus.RATE_LIMITED,
                        latencyMs = latency,
                        httpCode = resp.code,
                        message = "Rate limited (HTTP 429)"
                    )
                    resp.code == 401 || resp.code == 403 -> ConnectionTestResult(
                        status = ProviderStatus.ERROR,
                        latencyMs = latency,
                        httpCode = resp.code,
                        message = "Authentication failed (HTTP ${resp.code}). Verify API key."
                    )
                    else -> ConnectionTestResult(
                        status = ProviderStatus.ERROR,
                        latencyMs = latency,
                        httpCode = resp.code,
                        message = "HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(bodyStr, listOf(apiKey))}"
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ConnectionTestResult(ProviderStatus.DISCONNECTED, latency, message = "Connection timed out after ${latency}ms")
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ConnectionTestResult(
                status = ProviderStatus.DISCONNECTED,
                latencyMs = latency,
                message = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey))
            )
        }
    }

    suspend fun fetchProviderModels(
        provider: ProviderEntity,
        apiKey: String,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 25
    ): Result<List<ModelEntity>> = withContext(Dispatchers.IO) {
        if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(provider.baseUrl, allowLocalHttp)) {
            return@withContext Result.failure(IllegalArgumentException("Insecure URL blocked by HTTPS/Local policy"))
        }
        try {
            val url = buildModelsUrl(provider.baseUrl, provider.apiFormat)
            val request = applyAuthAndHeaders(Request.Builder().url(url).get(), provider, apiKey).build()
            buildClient(timeoutSec).newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(bodyStr, listOf(apiKey))}")
                    )
                }
                val models = parseModelsResponse(bodyStr, provider.id, provider.name, provider.apiFormat, isLocal = false)
                Result.success(models)
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey))))
        }
    }

    suspend fun testLocalServer(
        server: LocalAiServerEntity,
        authToken: String,
        allowLocalHttp: Boolean
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(server.serverUrl, allowLocalHttp)) {
            return@withContext ConnectionTestResult(
                status = ProviderStatus.ERROR,
                latencyMs = 0,
                message = "Local Network Access must be allowed for HTTP private servers, or use HTTPS for remote hosts."
            )
        }
        val start = System.nanoTime()
        try {
            val url = buildLocalModelsUrl(server.serverUrl, server.engineType)
            val reqBuilder = Request.Builder().url(url).get()
            if (authToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $authToken")
            buildClient(server.timeoutSec).newCall(reqBuilder.build()).execute().use { resp ->
                val latency = (System.nanoTime() - start) / 1_000_000L
                return@withContext if (resp.isSuccessful) {
                    ConnectionTestResult(ProviderStatus.CONNECTED, latency, resp.code, "Local server online (${latency}ms)")
                } else {
                    ConnectionTestResult(ProviderStatus.ERROR, latency, resp.code, "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ConnectionTestResult(
                ProviderStatus.DISCONNECTED,
                latency,
                message = MaskingUtils.sanitizeErrorMessage(e.message, listOf(authToken))
            )
        }
    }

    suspend fun discoverLocalModels(
        server: LocalAiServerEntity,
        authToken: String,
        allowLocalHttp: Boolean
    ): Result<List<ModelEntity>> = withContext(Dispatchers.IO) {
        if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(server.serverUrl, allowLocalHttp)) {
            return@withContext Result.failure(IllegalStateException("Local network access disabled or insecure remote URL"))
        }
        try {
            val url = buildLocalModelsUrl(server.serverUrl, server.engineType)
            val reqBuilder = Request.Builder().url(url).get()
            if (authToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $authToken")
            buildClient(server.timeoutSec).newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(body, listOf(authToken))}"))
                }
                val format = if (server.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
                Result.success(parseModelsResponse(body, "local:${server.id}", server.name, format, isLocal = true))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(MaskingUtils.sanitizeErrorMessage(e.message, listOf(authToken))))
        }
    }

    suspend fun executeChat(
        baseUrl: String,
        apiFormat: ApiFormat,
        apiKey: String,
        customHeadersJson: String,
        params: ChatRequestParams,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 60
    ): ChatExecutionResult = withContext(Dispatchers.IO) {
        if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(baseUrl, allowLocalHttp)) {
            return@withContext ChatExecutionResult(
                success = false,
                statusCode = 400,
                errorCode = "INSECURE_URL",
                safeErrorMessage = "Blocked non-HTTPS remote URL or unauthorized local HTTP request.",
                retryable = false
            )
        }
        val start = System.nanoTime()
        try {
            val request = buildChatHttpRequest(baseUrl, apiFormat, apiKey, customHeadersJson, params, stream = false)
            buildClient(timeoutSec).newCall(request).execute().use { resp ->
                val latency = (System.nanoTime() - start) / 1_000_000L
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext mapHttpFailure(resp.code, bodyStr, latency, apiKey)
                }
                parseChatCompletionResponse(bodyStr, apiFormat, latency, resp.code)
            }
        } catch (e: SocketTimeoutException) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ChatExecutionResult(false, latencyMs = latency, statusCode = 504, errorCode = "TIMEOUT", safeErrorMessage = "Request timed out", retryable = true)
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ChatExecutionResult(
                success = false,
                latencyMs = latency,
                statusCode = 502,
                errorCode = "CONNECTION_ERROR",
                safeErrorMessage = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)),
                retryable = true
            )
        }
    }

    fun streamChat(
        baseUrl: String,
        apiFormat: ApiFormat,
        apiKey: String,
        customHeadersJson: String,
        params: ChatRequestParams,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 90
    ): Flow<StreamEvent> = callbackFlow {
        if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(baseUrl, allowLocalHttp)) {
            trySend(
                StreamEvent.Failed(
                    ChatExecutionResult(false, statusCode = 400, errorCode = "INSECURE_URL", safeErrorMessage = "Blocked insecure endpoint", retryable = false),
                    emittedAnyChunk = false
                )
            )
            close()
            return@callbackFlow
        }

        val start = System.nanoTime()
        val request = buildChatHttpRequest(baseUrl, apiFormat, apiKey, customHeadersJson, params, stream = true)
        val call = buildClient(timeoutSec).newCall(request)
        var emittedAny = false

        try {
            call.execute().use { resp ->
                val latency = (System.nanoTime() - start) / 1_000_000L
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    trySend(StreamEvent.Failed(mapHttpFailure(resp.code, errBody, latency, apiKey), emittedAnyChunk = false))
                    close()
                    return@callbackFlow
                }

                val stream = resp.body?.byteStream()
                if (stream == null) {
                    trySend(StreamEvent.Failed(ChatExecutionResult(false, latencyMs = latency, statusCode = 502, errorCode = "EMPTY_STREAM", safeErrorMessage = "Empty response stream", retryable = true), false))
                    close()
                    return@callbackFlow
                }

                val accumulated = StringBuilder()
                var inTokens = 0
                var outTokens = 0
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim().orEmpty()
                    if (trimmed.isEmpty()) continue
                    if (trimmed == "data: [DONE]" || trimmed == "data:[DONE]") break

                    val jsonText = when {
                        trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                        apiFormat == ApiFormat.OLLAMA -> trimmed
                        else -> continue
                    }
                    if (jsonText.isEmpty() || jsonText == "[DONE]") continue

                    runCatching {
                        val json = JSONObject(jsonText)
                        val delta = extractStreamDelta(json, apiFormat)
                        if (delta.isNotEmpty()) {
                            emittedAny = true
                            accumulated.append(delta)
                            trySend(StreamEvent.Delta(delta))
                        }
                        val usage = extractTokenUsage(json, apiFormat)
                        if (usage.first > 0) inTokens = usage.first
                        if (usage.second > 0) outTokens = usage.second
                    }
                }
                val totalLatency = (System.nanoTime() - start) / 1_000_000L
                trySend(
                    StreamEvent.Completed(
                        ChatExecutionResult(
                            success = true,
                            content = accumulated.toString(),
                            inputTokens = inTokens,
                            outputTokens = outTokens,
                            latencyMs = totalLatency,
                            statusCode = resp.code
                        )
                    )
                )
            }
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            trySend(
                StreamEvent.Failed(
                    ChatExecutionResult(
                        success = false,
                        latencyMs = latency,
                        statusCode = 502,
                        errorCode = "STREAM_ERROR",
                        safeErrorMessage = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)),
                        retryable = !emittedAny
                    ),
                    emittedAnyChunk = emittedAny
                )
            )
        }
        close()
        awaitClose { call.cancel() }
    }

    suspend fun executeRawGatewayForward(
        url: String,
        apiKey: String,
        apiFormat: ApiFormat,
        customHeadersJson: String,
        bodyJson: String,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 60
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(url, allowLocalHttp)) {
            return@withContext 400 to """{"error":{"message":"Blocked insecure endpoint"}}"""
        }
        try {
            val builder = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMedia))
            applyFormatAuth(builder, apiFormat, apiKey, customHeadersJson)
            buildClient(timeoutSec).newCall(builder.build()).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            502 to """{"error":{"message":"${MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)).replace("\"", "'")}"}}"""
        }
    }

    private fun buildModelsUrl(baseUrl: String, format: ApiFormat): String {
        val clean = baseUrl.trimEnd('/')
        return when (format) {
            ApiFormat.GEMINI -> "$clean/models"
            ApiFormat.OLLAMA -> if (clean.endsWith("/v1")) clean.removeSuffix("/v1") + "/api/tags" else "$clean/api/tags"
            else -> "$clean/models"
        }
    }

    private fun buildLocalModelsUrl(serverUrl: String, engineType: LocalEngineType): String {
        val clean = serverUrl.trimEnd('/')
        return when (engineType) {
            LocalEngineType.OLLAMA -> if (clean.endsWith("/v1")) clean.removeSuffix("/v1") + "/api/tags" else "$clean/api/tags"
            else -> if (clean.endsWith("/v1")) "$clean/models" else "$clean/v1/models"
        }
    }

    private fun applyAuthAndHeaders(
        builder: Request.Builder,
        provider: ProviderEntity,
        apiKey: String
    ): Request.Builder = applyFormatAuth(builder, provider.apiFormat, apiKey, provider.customHeadersJson)

    private fun applyFormatAuth(
        builder: Request.Builder,
        format: ApiFormat,
        apiKey: String,
        customHeadersJson: String
    ): Request.Builder {
        if (apiKey.isNotBlank()) {
            when (format) {
                ApiFormat.GEMINI -> builder.header("x-goog-api-key", apiKey)
                ApiFormat.ANTHROPIC -> {
                    builder.header("x-api-key", apiKey)
                    builder.header("anthropic-version", "2023-06-01")
                }
                else -> builder.header("Authorization", "Bearer $apiKey")
            }
        } else if (format == ApiFormat.ANTHROPIC) {
            builder.header("anthropic-version", "2023-06-01")
        }
        if (customHeadersJson.isNotBlank()) {
            runCatching {
                val obj = JSONObject(customHeadersJson)
                obj.keys().forEach { k -> builder.header(k, obj.optString(k)) }
            }
        }
        return builder
    }

    private fun buildChatHttpRequest(
        baseUrl: String,
        format: ApiFormat,
        apiKey: String,
        customHeadersJson: String,
        params: ChatRequestParams,
        stream: Boolean
    ): Request {
        val clean = baseUrl.trimEnd('/')
        val (url, payload) = when (format) {
            ApiFormat.GEMINI -> {
                val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
                val modelPath = if (params.modelId.startsWith("models/")) params.modelId else "models/${params.modelId}"
                val sysMessages = params.messages.filter { it.role == "system" }.joinToString("\n") { it.content }
                val contents = JSONArray().apply {
                    params.messages.filter { it.role != "system" }.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", if (msg.role == "assistant") "model" else "user")
                            put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                        })
                    }
                }
                val root = JSONObject().apply {
                    put("contents", contents)
                    if (sysMessages.isNotBlank()) {
                        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysMessages))))
                    }
                    val genCfg = JSONObject()
                    params.temperature?.let { genCfg.put("temperature", it.toDouble()) }
                    params.topP?.let { genCfg.put("topP", it.toDouble()) }
                    params.maxTokens?.let { genCfg.put("maxOutputTokens", it) }
                    if (params.stopSequences.isNotEmpty()) genCfg.put("stopSequences", JSONArray(params.stopSequences))
                    if (params.jsonOutput) genCfg.put("responseMimeType", "application/json")
                    if (genCfg.length() > 0) put("generationConfig", genCfg)
                }
                "$clean/$modelPath:$action" to root
            }

            ApiFormat.ANTHROPIC -> {
                val sysMessages = params.messages.filter { it.role == "system" }.joinToString("\n") { it.content }
                val msgs = JSONArray().apply {
                    params.messages.filter { it.role != "system" }.forEach { msg ->
                        put(JSONObject().put("role", msg.role).put("content", msg.content))
                    }
                }
                val root = JSONObject().apply {
                    put("model", params.modelId)
                    put("messages", msgs)
                    put("max_tokens", params.maxTokens ?: 1024)
                    if (sysMessages.isNotBlank()) put("system", sysMessages)
                    params.temperature?.let { put("temperature", it.toDouble()) }
                    params.topP?.let { put("top_p", it.toDouble()) }
                    if (params.stopSequences.isNotEmpty()) put("stop_sequences", JSONArray(params.stopSequences))
                    put("stream", stream)
                }
                "$clean/messages" to root
            }

            ApiFormat.COHERE -> {
                val msgs = JSONArray().apply {
                    params.messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                }
                val root = JSONObject().apply {
                    put("model", params.modelId)
                    put("messages", msgs)
                    params.temperature?.let { put("temperature", it.toDouble()) }
                    params.maxTokens?.let { put("max_tokens", it) }
                    put("stream", stream)
                }
                "$clean/chat" to root
            }

            ApiFormat.OLLAMA -> {
                val ollamaBase = if (clean.endsWith("/v1")) clean.removeSuffix("/v1") else clean
                val msgs = JSONArray().apply {
                    params.messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                }
                val root = JSONObject().apply {
                    put("model", params.modelId)
                    put("messages", msgs)
                    put("stream", stream)
                    val opts = JSONObject()
                    params.temperature?.let { opts.put("temperature", it.toDouble()) }
                    params.topP?.let { opts.put("top_p", it.toDouble()) }
                    params.maxTokens?.let { opts.put("num_predict", it) }
                    if (opts.length() > 0) put("options", opts)
                }
                "$ollamaBase/api/chat" to root
            }

            else -> {
                val msgs = JSONArray().apply {
                    params.messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                }
                val root = JSONObject().apply {
                    put("model", params.modelId)
                    put("messages", msgs)
                    params.temperature?.let { put("temperature", it.toDouble()) }
                    params.topP?.let { put("top_p", it.toDouble()) }
                    params.maxTokens?.let { put("max_tokens", it) }
                    if (params.stopSequences.isNotEmpty()) put("stop", JSONArray(params.stopSequences))
                    if (params.jsonOutput) put("response_format", JSONObject().put("type", "json_object"))
                    if (params.toolsJson != null && params.toolsJson.length() > 0) put("tools", params.toolsJson)
                    put("stream", stream)
                    if (stream) put("stream_options", JSONObject().put("include_usage", true))
                }
                val endpoint = if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
                endpoint to root
            }
        }

        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMedia))
        return applyFormatAuth(builder, format, apiKey, customHeadersJson).build()
    }

    private fun parseModelsResponse(
        bodyStr: String,
        providerId: String,
        providerName: String,
        format: ApiFormat,
        isLocal: Boolean
    ): List<ModelEntity> {
        val root = JSONObject(bodyStr)
        val result = mutableListOf<ModelEntity>()
        when (format) {
            ApiFormat.GEMINI -> {
                val arr = root.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawName = obj.optString("name").removePrefix("models/")
                    if (rawName.isBlank()) continue
                    val display = obj.optString("displayName").ifBlank { rawName }
                    val ctx = obj.optInt("inputTokenLimit", 0).takeIf { it > 0 }
                    val pricing = ProviderPresets.lookupPricing(rawName)
                    result += ModelEntity(
                        id = "$providerId::$rawName",
                        modelId = rawName,
                        displayName = display,
                        providerId = providerId,
                        providerName = providerName,
                        modelType = ProviderPresets.inferModelType(rawName),
                        capabilitiesCsv = "CHAT,STREAMING,TOOLS,VISION",
                        contextWindow = ctx,
                        supportsTools = true,
                        supportsStreaming = true,
                        isLocal = isLocal,
                        inputPricePer1M = pricing?.inputPer1M,
                        outputPricePer1M = pricing?.outputPer1M,
                        pricingUpdatedAt = pricing?.updatedAt
                    )
                }
            }
            ApiFormat.OLLAMA -> {
                val arr = root.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name")
                    if (name.isBlank()) continue
                    result += ModelEntity(
                        id = "$providerId::$name",
                        modelId = name,
                        displayName = name,
                        providerId = providerId,
                        providerName = providerName,
                        modelType = ProviderPresets.inferModelType(name),
                        capabilitiesCsv = "CHAT,STREAMING,LOCAL",
                        contextWindow = null,
                        supportsTools = name.contains("qwen", true) || name.contains("llama3", true),
                        supportsStreaming = true,
                        isLocal = true
                    )
                }
            }
            else -> {
                val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { obj.optString("name") }
                    if (id.isBlank()) continue
                    val display = obj.optString("display_name").ifBlank { id }
                    val ctx = obj.optInt("context_length", obj.optInt("context_window", 0)).takeIf { it > 0 }
                    val pricing = if (isLocal) null else ProviderPresets.lookupPricing(id)
                    result += ModelEntity(
                        id = "$providerId::$id",
                        modelId = id,
                        displayName = display,
                        providerId = providerId,
                        providerName = providerName,
                        modelType = ProviderPresets.inferModelType(id),
                        capabilitiesCsv = if (isLocal) "CHAT,STREAMING,LOCAL" else "CHAT,STREAMING,TOOLS",
                        contextWindow = ctx,
                        supportsTools = !id.contains("embed", true),
                        supportsStreaming = !id.contains("embed", true),
                        isLocal = isLocal,
                        inputPricePer1M = pricing?.inputPer1M,
                        outputPricePer1M = pricing?.outputPer1M,
                        pricingUpdatedAt = pricing?.updatedAt
                    )
                }
            }
        }
        return result
    }

    private fun parseChatCompletionResponse(
        bodyStr: String,
        format: ApiFormat,
        latencyMs: Long,
        statusCode: Int
    ): ChatExecutionResult {
        val root = JSONObject(bodyStr)
        return when (format) {
            ApiFormat.GEMINI -> {
                val candidates = root.optJSONArray("candidates")
                val first = candidates?.optJSONObject(0)
                val parts = first?.optJSONObject("content")?.optJSONArray("parts")
                val text = buildString {
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            append(parts.optJSONObject(i)?.optString("text").orEmpty())
                        }
                    }
                }
                val usage = root.optJSONObject("usageMetadata")
                ChatExecutionResult(
                    success = true,
                    content = text,
                    inputTokens = usage?.optInt("promptTokenCount", 0) ?: 0,
                    outputTokens = usage?.optInt("candidatesTokenCount", 0) ?: 0,
                    latencyMs = latencyMs,
                    statusCode = statusCode,
                    finishReason = first?.optString("finishReason", "STOP") ?: "STOP"
                )
            }
            ApiFormat.ANTHROPIC -> {
                val contentArr = root.optJSONArray("content")
                val text = buildString {
                    if (contentArr != null) {
                        for (i in 0 until contentArr.length()) {
                            val block = contentArr.optJSONObject(i)
                            if (block?.optString("type") == "text") append(block.optString("text"))
                        }
                    }
                }
                val usage = root.optJSONObject("usage")
                ChatExecutionResult(
                    success = true,
                    content = text,
                    inputTokens = usage?.optInt("input_tokens", 0) ?: 0,
                    outputTokens = usage?.optInt("output_tokens", 0) ?: 0,
                    latencyMs = latencyMs,
                    statusCode = statusCode,
                    finishReason = root.optString("stop_reason", "end_turn")
                )
            }
            ApiFormat.COHERE -> {
                val contentArr = root.optJSONObject("message")?.optJSONArray("content")
                val text = contentArr?.optJSONObject(0)?.optString("text").orEmpty()
                val usage = root.optJSONObject("usage")?.optJSONObject("tokens")
                ChatExecutionResult(
                    success = true,
                    content = text,
                    inputTokens = usage?.optInt("input_tokens", 0) ?: 0,
                    outputTokens = usage?.optInt("output_tokens", 0) ?: 0,
                    latencyMs = latencyMs,
                    statusCode = statusCode,
                    finishReason = root.optString("finish_reason", "COMPLETE")
                )
            }
            ApiFormat.OLLAMA -> {
                val text = root.optJSONObject("message")?.optString("content").orEmpty()
                ChatExecutionResult(
                    success = true,
                    content = text,
                    inputTokens = root.optInt("prompt_eval_count", 0),
                    outputTokens = root.optInt("eval_count", 0),
                    latencyMs = latencyMs,
                    statusCode = statusCode
                )
            }
            else -> {
                val firstChoice = root.optJSONArray("choices")?.optJSONObject(0)
                val text = firstChoice?.optJSONObject("message")?.optString("content").orEmpty()
                val usage = root.optJSONObject("usage")
                ChatExecutionResult(
                    success = true,
                    content = text,
                    inputTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                    outputTokens = usage?.optInt("completion_tokens", 0) ?: 0,
                    latencyMs = latencyMs,
                    statusCode = statusCode,
                    finishReason = firstChoice?.optString("finish_reason", "stop") ?: "stop"
                )
            }
        }
    }

    private fun extractStreamDelta(json: JSONObject, format: ApiFormat): String {
        return when (format) {
            ApiFormat.GEMINI -> {
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                parts?.optJSONObject(0)?.optString("text").orEmpty()
            }
            ApiFormat.ANTHROPIC -> {
                if (json.optString("type") == "content_block_delta") {
                    json.optJSONObject("delta")?.optString("text").orEmpty()
                } else ""
            }
            ApiFormat.OLLAMA -> json.optJSONObject("message")?.optString("content").orEmpty()
            else -> {
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                choice?.optJSONObject("delta")?.optString("content").orEmpty()
            }
        }
    }

    private fun extractTokenUsage(json: JSONObject, format: ApiFormat): Pair<Int, Int> {
        return when (format) {
            ApiFormat.GEMINI -> {
                val u = json.optJSONObject("usageMetadata")
                (u?.optInt("promptTokenCount", 0) ?: 0) to (u?.optInt("candidatesTokenCount", 0) ?: 0)
            }
            ApiFormat.ANTHROPIC -> {
                val u = json.optJSONObject("usage") ?: json.optJSONObject("message")?.optJSONObject("usage")
                (u?.optInt("input_tokens", 0) ?: 0) to (u?.optInt("output_tokens", 0) ?: 0)
            }
            ApiFormat.OLLAMA -> json.optInt("prompt_eval_count", 0) to json.optInt("eval_count", 0)
            else -> {
                val u = json.optJSONObject("usage")
                (u?.optInt("prompt_tokens", 0) ?: 0) to (u?.optInt("completion_tokens", 0) ?: 0)
            }
        }
    }

    private fun mapHttpFailure(code: Int, bodyStr: String, latencyMs: Long, apiKey: String): ChatExecutionResult {
        val safeMsg = MaskingUtils.sanitizeErrorMessage(bodyStr.ifBlank { "HTTP $code" }, listOf(apiKey))
        val (errCode, retryable) = when (code) {
            429 -> "RATE_LIMITED" to true
            401, 403 -> "INVALID_CREDENTIAL" to true
            404 -> "MODEL_UNAVAILABLE" to true
            in 500..599 -> "PROVIDER_SERVER_ERROR" to true
            else -> "HTTP_$code" to false
        }
        return ChatExecutionResult(
            success = false,
            latencyMs = latencyMs,
            statusCode = code,
            errorCode = errCode,
            safeErrorMessage = safeMsg,
            retryable = retryable
        )
    }
}
