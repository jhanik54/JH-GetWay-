package com.example.gateway

import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class GatewayRunStatus { RUNNING, STOPPED, OFFLINE }

data class GatewayRuntimeState(
    val status: GatewayRunStatus = GatewayRunStatus.STOPPED,
    val boundAddress: String = "http://127.0.0.1:8787",
    val activeRequests: Int = 0,
    val lastError: String? = null
)

class LocalGatewayHttpServer(
    private val db: JhGetWayDatabase,
    private val router: SmartRouterEngine,
    private val preferences: AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val activeCount = AtomicInteger(0)
    private val rateWindow = ConcurrentHashMap<String, MutableList<Long>>()

    private val _runtimeState = MutableStateFlow(GatewayRuntimeState())
    val runtimeState: StateFlow<GatewayRuntimeState> = _runtimeState.asStateFlow()

    suspend fun start(settings: GatewaySettingsState) {
        if (_runtimeState.value.status == GatewayRunStatus.RUNNING) return
        if (settings.gatewayMode == GatewayMode.REMOTE || settings.gatewayMode == GatewayMode.EXISTING) {
            val remote = settings.gatewayRemoteUrl.ifBlank { "https://gateway.example.invalid" }
            _runtimeState.value = GatewayRuntimeState(
                status = if (remote.startsWith("https://")) GatewayRunStatus.RUNNING else GatewayRunStatus.OFFLINE,
                boundAddress = remote,
                activeRequests = 0,
                lastError = if (remote.startsWith("https://")) null else "Remote Gateway requires HTTPS URL"
            )
            db.metricAndLogDao().insertLog(
                ActivityLogEntity(
                    category = ActivityCategory.SYSTEM_EVENT,
                    relatedEntity = "Gateway",
                    status = "REMOTE_READY",
                    safeMessage = "Configured remote gateway target: $remote"
                )
            )
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val bindAddr = InetAddress.getByName(settings.gatewayHost.ifBlank { "127.0.0.1" })
                val socket = ServerSocket(settings.gatewayPort, 32, bindAddr)
                serverSocket = socket
                val addrStr = "http://${bindAddr.hostAddress}:${socket.localPort}"
                _runtimeState.value = GatewayRuntimeState(
                    status = GatewayRunStatus.RUNNING,
                    boundAddress = addrStr,
                    activeRequests = 0,
                    lastError = null
                )
                db.metricAndLogDao().insertLog(
                    ActivityLogEntity(
                        category = ActivityCategory.SYSTEM_EVENT,
                        relatedEntity = settings.gatewayName,
                        status = "STARTED",
                        safeMessage = "Local Gateway HTTP server listening on $addrStr"
                    )
                )
                acceptJob = scope.launch {
                    while (isActive && !socket.isClosed) {
                        try {
                            val client = socket.accept()
                            launch { handleClient(client) }
                        } catch (_: Exception) {
                            if (socket.isClosed) break
                        }
                    }
                }
            } catch (e: Exception) {
                _runtimeState.value = GatewayRuntimeState(
                    status = GatewayRunStatus.OFFLINE,
                    boundAddress = "http://${settings.gatewayHost}:${settings.gatewayPort}",
                    lastError = e.message
                )
            }
        }
    }

    suspend fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _runtimeState.update { it.copy(status = GatewayRunStatus.STOPPED, activeRequests = 0) }
        db.metricAndLogDao().insertLog(
            ActivityLogEntity(
                category = ActivityCategory.SYSTEM_EVENT,
                relatedEntity = "Gateway",
                status = "STOPPED",
                safeMessage = "Local Gateway server stopped"
            )
        )
    }

    private suspend fun handleClient(socket: Socket) {
        val currentActive = activeCount.incrementAndGet()
        _runtimeState.update { it.copy(activeRequests = currentActive) }
        try {
            socket.use { sock ->
                sock.soTimeout = 60_000
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                val settings = preferences.settingsFlow.first()

                val parsed = readHttpRequest(input, settings.gatewayMaxRequestKb * 1024) ?: run {
                    writeJsonResponse(out, 400, """{"error":{"message":"Malformed or oversized HTTP request"}}""")
                    return
                }

                val path = parsed.path.substringBefore("?")
                if (parsed.method == "GET" && path == "/health") {
                    writeJsonResponse(out, 200, JSONObject().put("status", "ok").put("gateway", settings.gatewayName).toString())
                    return
                }

                // Auth check
                val authClientName = authenticateRequest(parsed.headers["authorization"], settings)
                if (settings.gatewayAuthRequired && authClientName == null) {
                    db.metricAndLogDao().insertLog(
                        ActivityLogEntity(
                            category = ActivityCategory.AUTH_EVENT,
                            relatedEntity = path,
                            status = "UNAUTHORIZED",
                            safeMessage = "Rejected request without valid Gateway Bearer token"
                        )
                    )
                    writeJsonResponse(out, 401, """{"error":{"message":"Invalid or missing Gateway Bearer token","type":"authentication_error"}}""")
                    return
                }
                val clientLabel = authClientName ?: "Local Client"

                // Rate limit check
                if (!checkRateLimit(clientLabel, settings.gatewayRateLimitPerMin)) {
                    writeJsonResponse(out, 429, """{"error":{"message":"Gateway rate limit exceeded","type":"rate_limit_error"}}""")
                    return
                }

                when {
                    parsed.method == "GET" && path == "/v1/models" -> handleListModels(out)
                    parsed.method == "POST" && path == "/v1/chat/completions" -> handleOpenAiChat(parsed.body, clientLabel, settings, out)
                    parsed.method == "POST" && path == "/v1/responses" -> handleOpenAiResponses(parsed.body, clientLabel, settings, out)
                    parsed.method == "POST" && path == "/v1/messages" -> handleAnthropicMessages(parsed.body, clientLabel, settings, out)
                    parsed.method == "POST" && (path == "/v1/embeddings" || path == "/v1/images/generations" || path == "/v1/audio/transcriptions" || path == "/v1/audio/speech") -> {
                        writeJsonResponse(
                            out,
                            501,
                            JSONObject().put("error", JSONObject().put("message", "Endpoint $path requires a configured compatible backend adapter or model capability.")).toString()
                        )
                    }
                    else -> writeJsonResponse(out, 404, """{"error":{"message":"Unknown endpoint $path"}}""")
                }
            }
        } catch (_: Exception) {
        } finally {
            val left = activeCount.decrementAndGet().coerceAtLeast(0)
            _runtimeState.update { it.copy(activeRequests = left) }
        }
    }

    private suspend fun authenticateRequest(authHeader: String?, settings: GatewaySettingsState): String? {
        val token = authHeader?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim().orEmpty()
        if (token.isEmpty()) return if (settings.gatewayAuthRequired) null else "Anonymous Local"

        // Check registered devices
        val devices = db.deviceDao().getAllOnce()
        devices.firstOrNull { !it.revoked && it.enabled && CryptoManager.decrypt(it.encryptedToken) == token }?.let { dev ->
            db.deviceDao().upsert(dev.copy(lastActiveAt = System.currentTimeMillis(), requestCount = dev.requestCount + 1))
            return dev.name
        }

        // Check coding agents
        val agents = db.codingAgentDao().getAllOnce()
        agents.firstOrNull { it.enabled && CryptoManager.decrypt(it.encryptedToken) == token }?.let { ag ->
            if (ag.dailyTokenLimit != null && ag.tokensUsedToday >= ag.dailyTokenLimit) return null
            db.codingAgentDao().upsert(ag.copy(lastActiveAt = System.currentTimeMillis(), requestCount = ag.requestCount + 1))
            return "Agent: ${ag.name}"
        }
        return null
    }

    private fun checkRateLimit(clientKey: String, maxPerMin: Int): Boolean {
        val now = System.currentTimeMillis()
        val list = rateWindow.getOrPut(clientKey) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > 60_000L }
            if (list.size >= maxPerMin.coerceAtLeast(1)) return false
            list.add(now)
            return true
        }
    }

    private suspend fun handleListModels(out: OutputStream) {
        val models = db.modelDao().getAllOnce().filter { it.enabled }
        val data = JSONArray()
        models.forEach { m ->
            data.put(JSONObject().apply {
                put("id", m.alias.ifBlank { m.modelId })
                put("object", "model")
                put("owned_by", m.providerName)
            })
        }
        writeJsonResponse(out, 200, JSONObject().put("object", "list").put("data", data).toString())
    }

    private suspend fun handleOpenAiChat(
        body: String,
        clientLabel: String,
        settings: GatewaySettingsState,
        out: OutputStream
    ) {
        val req = runCatching { JSONObject(body) }.getOrNull() ?: run {
            writeJsonResponse(out, 400, """{"error":{"message":"Invalid JSON body"}}""")
            return
        }
        val model = req.optString("model")
        val stream = req.optBoolean("stream", false) && settings.gatewayStreamingEnabled
        val msgsArr = req.optJSONArray("messages") ?: JSONArray()
        val messages = mutableListOf<ChatMessagePayload>()
        for (i in 0 until msgsArr.length()) {
            val m = msgsArr.optJSONObject(i) ?: continue
            messages += ChatMessagePayload(m.optString("role", "user"), m.optString("content"))
        }
        val temp = if (req.has("temperature")) req.optDouble("temperature").toFloat() else null
        val topP = if (req.has("top_p")) req.optDouble("top_p").toFloat() else null
        val maxTok = if (req.has("max_tokens")) req.optInt("max_tokens") else null

        if (!stream) {
            val result = router.executeRoutedChat(
                requestedModelOrAlias = model,
                messages = messages,
                settings = settings,
                deviceName = clientLabel,
                endpoint = "/v1/chat/completions",
                temperature = temp,
                topP = topP,
                maxTokens = maxTok
            )
            if (!result.execution.success) {
                writeJsonResponse(
                    out,
                    result.execution.statusCode.coerceIn(400, 599),
                    JSONObject().put("error", JSONObject().put("message", result.execution.safeErrorMessage ?: "Gateway error")).toString()
                )
            } else {
                val respJson = JSONObject().apply {
                    put("id", "chatcmpl-${result.decision.requestId}")
                    put("object", "chat.completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", result.decision.selectedModel)
                    put("choices", JSONArray().put(JSONObject().apply {
                        put("index", 0)
                        put("message", JSONObject().put("role", "assistant").put("content", result.execution.content))
                        put("finish_reason", result.execution.finishReason)
                    }))
                    put("usage", JSONObject().apply {
                        put("prompt_tokens", result.execution.inputTokens)
                        put("completion_tokens", result.execution.outputTokens)
                        put("total_tokens", result.execution.inputTokens + result.execution.outputTokens)
                    })
                }
                writeJsonResponse(out, 200, respJson.toString())
            }
        } else {
            // Real SSE streaming over Socket
            val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.flush()
            val cid = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
            router.streamRoutedChat(
                requestedModelOrAlias = model,
                messages = messages,
                settings = settings,
                deviceName = clientLabel,
                temperature = temp,
                topP = topP,
                maxTokens = maxTok
            ).collect { (ev, _) ->
                when (ev) {
                    is StreamEvent.Delta -> {
                        val chunk = JSONObject().apply {
                            put("id", cid)
                            put("object", "chat.completion.chunk")
                            put("model", model)
                            put("choices", JSONArray().put(JSONObject().apply {
                                put("index", 0)
                                put("delta", JSONObject().put("content", ev.text))
                            }))
                        }
                        out.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    is StreamEvent.Completed -> {
                        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    is StreamEvent.Failed -> {
                        val errObj = JSONObject().put("error", JSONObject().put("message", ev.error.safeErrorMessage))
                        out.write("data: $errObj\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
        }
    }

    private suspend fun handleOpenAiResponses(
        body: String,
        clientLabel: String,
        settings: GatewaySettingsState,
        out: OutputStream
    ) {
        val req = runCatching { JSONObject(body) }.getOrNull() ?: run {
            writeJsonResponse(out, 400, """{"error":{"message":"Invalid JSON body"}}""")
            return
        }
        val model = req.optString("model")
        val inputStr = req.optString("input")
        val result = router.executeRoutedChat(
            requestedModelOrAlias = model,
            messages = listOf(ChatMessagePayload("user", inputStr)),
            settings = settings,
            deviceName = clientLabel,
            endpoint = "/v1/responses"
        )
        if (!result.execution.success) {
            writeJsonResponse(out, result.execution.statusCode.coerceIn(400, 599), JSONObject().put("error", JSONObject().put("message", result.execution.safeErrorMessage)).toString())
        } else {
            val resp = JSONObject().apply {
                put("id", "resp_${result.decision.requestId}")
                put("object", "response")
                put("model", result.decision.selectedModel)
                put("output_text", result.execution.content)
                put("usage", JSONObject().put("input_tokens", result.execution.inputTokens).put("output_tokens", result.execution.outputTokens))
            }
            writeJsonResponse(out, 200, resp.toString())
        }
    }

    private suspend fun handleAnthropicMessages(
        body: String,
        clientLabel: String,
        settings: GatewaySettingsState,
        out: OutputStream
    ) {
        val req = runCatching { JSONObject(body) }.getOrNull() ?: run {
            writeJsonResponse(out, 400, """{"error":{"message":"Invalid JSON body"}}""")
            return
        }
        val model = req.optString("model")
        val msgs = mutableListOf<ChatMessagePayload>()
        val sys = req.optString("system")
        if (sys.isNotBlank()) msgs += ChatMessagePayload("system", sys)
        val arr = req.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            msgs += ChatMessagePayload(m.optString("role", "user"), m.optString("content"))
        }
        val result = router.executeRoutedChat(
            requestedModelOrAlias = model,
            messages = msgs,
            settings = settings,
            deviceName = clientLabel,
            endpoint = "/v1/messages",
            maxTokens = if (req.has("max_tokens")) req.optInt("max_tokens") else 1024
        )
        if (!result.execution.success) {
            writeJsonResponse(out, result.execution.statusCode.coerceIn(400, 599), JSONObject().put("type", "error").put("error", JSONObject().put("message", result.execution.safeErrorMessage)).toString())
        } else {
            val resp = JSONObject().apply {
                put("id", "msg_${result.decision.requestId}")
                put("type", "message")
                put("role", "assistant")
                put("model", result.decision.selectedModel)
                put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.execution.content)))
                put("usage", JSONObject().put("input_tokens", result.execution.inputTokens).put("output_tokens", result.execution.outputTokens))
            }
            writeJsonResponse(out, 200, resp.toString())
        }
    }

    private data class ParsedHttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readHttpRequest(input: BufferedInputStream, maxBytes: Int): ParsedHttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.write(b)
            if (headerBytes.size() > 32_768) return null
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
            prev3 = prev2; prev2 = prev1; prev1 = b
        }
        val headerStr = headerBytes.toString(Charsets.UTF_8.name())
        val lines = headerStr.split("\r\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val reqLine = lines[0].split(" ")
        if (reqLine.size < 2) return null
        val method = reqLine[0].uppercase()
        val path = reqLine[1]
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(":")
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > maxBytes) return null
        val bodyBuf = ByteArray(contentLength)
        var readTotal = 0
        while (readTotal < contentLength) {
            val n = input.read(bodyBuf, readTotal, contentLength - readTotal)
            if (n <= 0) break
            readTotal += n
        }
        return ParsedHttpRequest(method, path, headers, String(bodyBuf, 0, readTotal, Charsets.UTF_8))
    }

    private fun writeJsonResponse(out: OutputStream, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
