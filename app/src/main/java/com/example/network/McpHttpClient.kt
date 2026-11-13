package com.example.network

import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.McpServerEntity
import com.example.data.local.McpToolEntity
import com.example.data.local.McpTransportType
import com.example.data.local.ToolPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

data class McpDiscoveryResult(
    val success: Boolean,
    val tools: List<McpToolEntity> = emptyList(),
    val resourcesJson: String = "[]",
    val promptsJson: String = "[]",
    val errorMessage: String? = null
)

data class McpExecutionResult(
    val success: Boolean,
    val output: String,
    val errorMessage: String? = null
)

class McpHttpClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val sessionIds = mutableMapOf<String, String>()

    private fun client(timeoutSec: Int) = OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong().coerceIn(5, 60), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong().coerceIn(10, 120), TimeUnit.SECONDS)
        .build()

    suspend fun connectAndDiscover(
        server: McpServerEntity,
        authToken: String,
        envVarsJson: String,
        allowLocalHttp: Boolean
    ): McpDiscoveryResult = withContext(Dispatchers.IO) {
        when (server.transportType) {
            McpTransportType.STREAMABLE_HTTP -> discoverViaHttp(server, authToken, allowLocalHttp)
            McpTransportType.STDIO -> discoverViaStdio(server, envVarsJson)
        }
    }

    suspend fun executeTool(
        server: McpServerEntity,
        toolName: String,
        argumentsJson: String,
        authToken: String,
        envVarsJson: String,
        allowLocalHttp: Boolean
    ): McpExecutionResult = withContext(Dispatchers.IO) {
        val argsObj = runCatching {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        }.getOrElse {
            return@withContext McpExecutionResult(false, "", "Invalid JSON arguments: ${it.message}")
        }

        val rpc = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", argsObj)
            })
        }

        when (server.transportType) {
            McpTransportType.STREAMABLE_HTTP -> {
                if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(server.endpointOrCommand, allowLocalHttp)) {
                    return@withContext McpExecutionResult(false, "", "HTTPS required or Local Network Access disabled.")
                }
                val respResult = postJsonRpc(server, rpc, authToken)
                respResult.fold(
                    onSuccess = { json ->
                        val err = json.optJSONObject("error")
                        if (err != null) {
                            McpExecutionResult(false, "", err.optString("message", "Tool execution error"))
                        } else {
                            val resultObj = json.optJSONObject("result")
                            val contentArr = resultObj?.optJSONArray("content")
                            val text = buildString {
                                if (contentArr != null) {
                                    for (i in 0 until contentArr.length()) {
                                        val item = contentArr.optJSONObject(i)
                                        append(item?.optString("text") ?: item?.toString().orEmpty())
                                        if (i < contentArr.length() - 1) append("\n")
                                    }
                                } else {
                                    append(resultObj?.toString(2) ?: json.toString(2))
                                }
                            }.take(8192)
                            McpExecutionResult(true, text)
                        }
                    },
                    onFailure = { McpExecutionResult(false, "", MaskingUtils.sanitizeErrorMessage(it.message, listOf(authToken))) }
                )
            }
            McpTransportType.STDIO -> runStdioRpc(server, rpc, envVarsJson).fold(
                onSuccess = { json ->
                    val err = json.optJSONObject("error")
                    if (err != null) McpExecutionResult(false, "", err.optString("message"))
                    else McpExecutionResult(true, json.optJSONObject("result")?.toString(2).orEmpty().take(8192))
                },
                onFailure = { McpExecutionResult(false, "", it.message ?: "STDIO execution failed") }
            )
        }
    }

    private fun discoverViaHttp(
        server: McpServerEntity,
        authToken: String,
        allowLocalHttp: Boolean
    ): McpDiscoveryResult {
        if (!NetworkAddressClassifier.isSecureOrAllowedLocalUrl(server.endpointOrCommand, allowLocalHttp)) {
            return McpDiscoveryResult(false, errorMessage = "HTTPS required for remote MCP server, or enable Local Network Access for local server.")
        }
        val initReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "init-1")
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().put("name", "JH GetWay").put("version", "1.0.0"))
            })
        }
        val initResp = postJsonRpc(server, initReq, authToken)
        if (initResp.isFailure) {
            return McpDiscoveryResult(false, errorMessage = MaskingUtils.sanitizeErrorMessage(initResp.exceptionOrNull()?.message, listOf(authToken)))
        }

        val toolsReq = JSONObject().put("jsonrpc", "2.0").put("id", "tools-1").put("method", "tools/list")
        val toolsResp = postJsonRpc(server, toolsReq, authToken)
        val tools = mutableListOf<McpToolEntity>()
        toolsResp.getOrNull()?.optJSONObject("result")?.optJSONArray("tools")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                tools += McpToolEntity(
                    id = "${server.id}::$name",
                    serverId = server.id,
                    toolName = name,
                    description = obj.optString("description"),
                    inputSchemaJson = obj.optJSONObject("inputSchema")?.toString() ?: "{}",
                    permission = ToolPermission.ASK_BEFORE_RUNNING
                )
            }
        }

        val resReq = JSONObject().put("jsonrpc", "2.0").put("id", "res-1").put("method", "resources/list")
        val resourcesArr = postJsonRpc(server, resReq, authToken).getOrNull()
            ?.optJSONObject("result")?.optJSONArray("resources") ?: JSONArray()

        val promptsReq = JSONObject().put("jsonrpc", "2.0").put("id", "prm-1").put("method", "prompts/list")
        val promptsArr = postJsonRpc(server, promptsReq, authToken).getOrNull()
            ?.optJSONObject("result")?.optJSONArray("prompts") ?: JSONArray()

        return McpDiscoveryResult(
            success = true,
            tools = tools,
            resourcesJson = resourcesArr.toString(),
            promptsJson = promptsArr.toString()
        )
    }

    private fun postJsonRpc(server: McpServerEntity, payload: JSONObject, authToken: String): Result<JSONObject> {
        return try {
            val builder = Request.Builder()
                .url(server.endpointOrCommand.trim())
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMedia))
            if (authToken.isNotBlank()) builder.header("Authorization", "Bearer $authToken")
            sessionIds[server.id]?.let { builder.header("Mcp-Session-Id", it) }

            client(server.timeoutSec).newCall(builder.build()).execute().use { resp ->
                resp.header("Mcp-Session-Id")?.let { sessionIds[server.id] = it }
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(raw, listOf(authToken))}"))
                }
                val parsed = parseSseOrJson(raw)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSseOrJson(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        val dataLines = trimmed.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
        val lastJson = dataLines.lastOrNull { it.startsWith("{") } ?: "{}"
        return JSONObject(lastJson)
    }

    private fun discoverViaStdio(server: McpServerEntity, envVarsJson: String): McpDiscoveryResult {
        val toolsReq = JSONObject().put("jsonrpc", "2.0").put("id", "tools-1").put("method", "tools/list")
        return runStdioRpc(server, toolsReq, envVarsJson).fold(
            onSuccess = { json ->
                val tools = mutableListOf<McpToolEntity>()
                json.optJSONObject("result")?.optJSONArray("tools")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val name = obj.optString("name")
                        if (name.isBlank()) continue
                        tools += McpToolEntity(
                            id = "${server.id}::$name",
                            serverId = server.id,
                            toolName = name,
                            description = obj.optString("description"),
                            inputSchemaJson = obj.optJSONObject("inputSchema")?.toString() ?: "{}",
                            permission = ToolPermission.ASK_BEFORE_RUNNING
                        )
                    }
                }
                McpDiscoveryResult(true, tools = tools)
            },
            onFailure = { e ->
                McpDiscoveryResult(
                    success = false,
                    errorMessage = "STDIO execution unavailable in Android sandbox: ${e.message}. Use Streamable HTTP for remote/local bridge servers."
                )
            }
        )
    }

    private fun runStdioRpc(server: McpServerEntity, rpc: JSONObject, envVarsJson: String): Result<JSONObject> {
        return try {
            val cmdList = mutableListOf(server.endpointOrCommand.trim())
            if (server.arguments.isNotBlank()) {
                cmdList.addAll(server.arguments.trim().split(Regex("\\s+")))
            }
            val pb = ProcessBuilder(cmdList)
            if (envVarsJson.isNotBlank()) {
                runCatching {
                    val obj = JSONObject(envVarsJson)
                    obj.keys().forEach { k -> pb.environment()[k] = obj.optString(k) }
                }
            }
            val proc = pb.start()
            OutputStreamWriter(proc.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(rpc.toString() + "\n")
                writer.flush()
            }
            val finished = proc.waitFor(server.timeoutSec.toLong().coerceIn(3, 30), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return Result.failure(IllegalStateException("STDIO process timed out"))
            }
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readLine().orEmpty()
            if (out.isBlank()) {
                val err = BufferedReader(InputStreamReader(proc.errorStream)).readText().take(300)
                return Result.failure(IllegalStateException(err.ifBlank { "No JSON-RPC output" }))
            }
            Result.success(JSONObject(out))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
