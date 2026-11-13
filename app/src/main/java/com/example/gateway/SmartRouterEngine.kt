package com.example.gateway

import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.network.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class RouterDecisionSnapshot(
    val requestId: String,
    val selectedProvider: String,
    val selectedModel: String,
    val routingReason: String,
    val fallbackUsed: Boolean,
    val attemptCount: Int,
    val latencyMs: Long,
    val finalStatus: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class RouteCandidate(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiFormat: ApiFormat,
    val apiKey: String,
    val keyEntityId: String?,
    val customHeadersJson: String,
    val modelId: String,
    val isLocal: Boolean,
    val priority: Int,
    val lastLatencyMs: Long,
    val estimatedCostPer1M: Double?
)

data class RoutedChatResult(
    val execution: ChatExecutionResult,
    val decision: RouterDecisionSnapshot
)

class SmartRouterEngine(
    private val db: JhGetWayDatabase,
    private val httpClient: ProviderHttpClient
) {
    private val roundRobinCounter = AtomicInteger(0)
    private val keyRoundRobinCounter = AtomicInteger(0)

    private val _recentDecisions = MutableStateFlow<List<RouterDecisionSnapshot>>(emptyList())
    val recentDecisions: StateFlow<List<RouterDecisionSnapshot>> = _recentDecisions.asStateFlow()

    suspend fun executeRoutedChat(
        requestedModelOrAlias: String,
        messages: List<ChatMessagePayload>,
        settings: GatewaySettingsState,
        routingModeOverride: RoutingMode? = null,
        explicitProviderId: String? = null,
        deviceName: String = "Playground",
        endpoint: String = "/v1/chat/completions",
        temperature: Float? = null,
        topP: Float? = null,
        maxTokens: Int? = null,
        stopSequences: List<String> = emptyList(),
        jsonOutput: Boolean = false
    ): RoutedChatResult {
        val requestId = UUID.randomUUID().toString().take(12)
        val effectiveMode = routingModeOverride ?: settings.defaultRoutingMode
        val (candidates, reason) = resolveCandidates(
            requestedModelOrAlias = requestedModelOrAlias,
            settings = settings,
            mode = effectiveMode,
            explicitProviderId = explicitProviderId
        )

        if (candidates.isEmpty()) {
            val err = ChatExecutionResult(
                success = false,
                statusCode = 503,
                errorCode = "NO_ELIGIBLE_PROVIDER",
                safeErrorMessage = "No enabled provider or local AI server available for '$requestedModelOrAlias'."
            )
            val decision = RouterDecisionSnapshot(
                requestId = requestId,
                selectedProvider = "None",
                selectedModel = requestedModelOrAlias.ifBlank { "Unassigned" },
                routingReason = reason,
                fallbackUsed = false,
                attemptCount = 0,
                latencyMs = 0,
                finalStatus = "FAILED (NO_PROVIDER)"
            )
            recordDecision(decision, err, null, deviceName, endpoint, effectiveMode.name)
            return RoutedChatResult(err, decision)
        }

        val maxAttempts = if (settings.fallbackEnabled) (1 + settings.maxRetries).coerceAtMost(candidates.size) else 1
        var attemptCount = 0
        var lastResult = ChatExecutionResult(false, errorCode = "UNKNOWN", safeErrorMessage = "No attempts made")
        var winningCandidate = candidates.first()

        for (i in 0 until maxAttempts) {
            val candidate = candidates[i]
            winningCandidate = candidate
            attemptCount++

            val res = httpClient.executeChat(
                baseUrl = candidate.baseUrl,
                apiFormat = candidate.apiFormat,
                apiKey = candidate.apiKey,
                customHeadersJson = candidate.customHeadersJson,
                params = ChatRequestParams(
                    messages = messages,
                    modelId = candidate.modelId,
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens,
                    stopSequences = stopSequences,
                    jsonOutput = jsonOutput,
                    stream = false
                ),
                allowLocalHttp = settings.localNetworkAccessAllowed,
                timeoutSec = settings.gatewayTimeoutSec
            )
            lastResult = res

            updateProviderAndKeyHealth(candidate, res)
            if (res.success) break

            if (attemptCount > 1 || (res.retryable && i + 1 < maxAttempts)) {
                db.metricAndLogDao().insertLog(
                    ActivityLogEntity(
                        category = ActivityCategory.FALLBACK_EVENT,
                        relatedEntity = candidate.displayName,
                        status = res.errorCode ?: "ERROR",
                        safeMessage = "Fallback triggered from ${candidate.displayName} (${res.safeErrorMessage})"
                    )
                )
            }
            if (!res.retryable) break
        }

        val fallbackUsed = attemptCount > 1
        val finalStatus = if (lastResult.success) {
            if (fallbackUsed) "SUCCESS (FALLBACK)" else "SUCCESS"
        } else {
            "FAILED (${lastResult.errorCode ?: lastResult.statusCode})"
        }
        val decision = RouterDecisionSnapshot(
            requestId = requestId,
            selectedProvider = winningCandidate.displayName,
            selectedModel = winningCandidate.modelId,
            routingReason = reason,
            fallbackUsed = fallbackUsed,
            attemptCount = attemptCount,
            latencyMs = lastResult.latencyMs,
            finalStatus = finalStatus
        )
        recordDecision(decision, lastResult, winningCandidate, deviceName, endpoint, effectiveMode.name)
        return RoutedChatResult(lastResult, decision)
    }

    fun streamRoutedChat(
        requestedModelOrAlias: String,
        messages: List<ChatMessagePayload>,
        settings: GatewaySettingsState,
        routingModeOverride: RoutingMode? = null,
        explicitProviderId: String? = null,
        deviceName: String = "Playground",
        temperature: Float? = null,
        topP: Float? = null,
        maxTokens: Int? = null,
        stopSequences: List<String> = emptyList(),
        jsonOutput: Boolean = false
    ): Flow<Pair<StreamEvent, RouterDecisionSnapshot?>> = flow {
        val requestId = UUID.randomUUID().toString().take(12)
        val effectiveMode = routingModeOverride ?: settings.defaultRoutingMode
        val (candidates, reason) = resolveCandidates(
            requestedModelOrAlias = requestedModelOrAlias,
            settings = settings,
            mode = effectiveMode,
            explicitProviderId = explicitProviderId
        )

        if (candidates.isEmpty()) {
            val err = ChatExecutionResult(false, statusCode = 503, errorCode = "NO_ELIGIBLE_PROVIDER", safeErrorMessage = "No enabled provider or local AI server available.")
            val decision = RouterDecisionSnapshot(requestId, "None", requestedModelOrAlias, reason, false, 0, 0, "FAILED")
            recordDecision(decision, err, null, deviceName, "/v1/chat/completions", effectiveMode.name)
            emit(StreamEvent.Failed(err, false) to decision)
            return@flow
        }

        val maxAttempts = if (settings.fallbackEnabled) (1 + settings.maxRetries).coerceAtMost(candidates.size) else 1
        var attempt = 0

        for (i in 0 until maxAttempts) {
            val candidate = candidates[i]
            attempt++
            var finishedCleanly = false
            var canRetryNext = false
            var latestResult: ChatExecutionResult? = null

            httpClient.streamChat(
                baseUrl = candidate.baseUrl,
                apiFormat = candidate.apiFormat,
                apiKey = candidate.apiKey,
                customHeadersJson = candidate.customHeadersJson,
                params = ChatRequestParams(
                    messages = messages,
                    modelId = candidate.modelId,
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens,
                    stopSequences = stopSequences,
                    jsonOutput = jsonOutput,
                    stream = true
                ),
                allowLocalHttp = settings.localNetworkAccessAllowed,
                timeoutSec = settings.gatewayTimeoutSec
            ).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> emit(event to null)
                    is StreamEvent.Completed -> {
                        finishedCleanly = true
                        latestResult = event.result
                        updateProviderAndKeyHealth(candidate, event.result)
                        val decision = RouterDecisionSnapshot(
                            requestId = requestId,
                            selectedProvider = candidate.displayName,
                            selectedModel = candidate.modelId,
                            routingReason = reason,
                            fallbackUsed = attempt > 1,
                            attemptCount = attempt,
                            latencyMs = event.result.latencyMs,
                            finalStatus = if (attempt > 1) "SUCCESS (FALLBACK)" else "SUCCESS"
                        )
                        recordDecision(decision, event.result, candidate, deviceName, "/v1/chat/completions", effectiveMode.name)
                        emit(event to decision)
                    }
                    is StreamEvent.Failed -> {
                        latestResult = event.error
                        updateProviderAndKeyHealth(candidate, event.error)
                        // Rule 7.3: Never restart mid-stream after chunks have been emitted
                        if (!event.emittedAnyChunk && event.error.retryable && i + 1 < maxAttempts) {
                            canRetryNext = true
                            db.metricAndLogDao().insertLog(
                                ActivityLogEntity(
                                    category = ActivityCategory.FALLBACK_EVENT,
                                    relatedEntity = candidate.displayName,
                                    status = event.error.errorCode ?: "STREAM_FAIL",
                                    safeMessage = "Pre-stream fallback from ${candidate.displayName}: ${event.error.safeErrorMessage}"
                                )
                            )
                        } else {
                            val decision = RouterDecisionSnapshot(
                                requestId = requestId,
                                selectedProvider = candidate.displayName,
                                selectedModel = candidate.modelId,
                                routingReason = reason,
                                fallbackUsed = attempt > 1,
                                attemptCount = attempt,
                                latencyMs = event.error.latencyMs,
                                finalStatus = "FAILED (${event.error.errorCode ?: event.error.statusCode})"
                            )
                            recordDecision(decision, event.error, candidate, deviceName, "/v1/chat/completions", effectiveMode.name)
                            emit(event to decision)
                        }
                    }
                }
            }

            if (finishedCleanly || !canRetryNext) break
            if (latestResult != null && !latestResult!!.retryable) break
        }
    }

    suspend fun resolveCandidates(
        requestedModelOrAlias: String,
        settings: GatewaySettingsState,
        mode: RoutingMode,
        explicitProviderId: String? = null
    ): Pair<List<RouteCandidate>, String> {
        val providers = db.providerDao().getAllOnce().filter { it.enabled }
        val localServers = db.localAiServerDao().getAllOnce().filter { it.enabled }
        val allModels = db.modelDao().getAllOnce().filter { it.enabled }
        val rules = db.routingRuleDao().getAllOnce().filter { it.enabled }
        val allKeys = db.apiKeyDao().getAllOnce().filter { it.enabled }

        val targetAliasOrModel = requestedModelOrAlias.ifBlank { settings.defaultModelId }
        val matchedRule = rules.firstOrNull { it.modelAlias.equals(targetAliasOrModel, ignoreCase = true) }
        val aliasedModels = allModels.filter {
            it.alias.equals(targetAliasOrModel, ignoreCase = true) ||
                it.modelId.equals(targetAliasOrModel, ignoreCase = true)
        }

        val candidates = mutableListOf<RouteCandidate>()

        // Cloud providers
        for (provider in providers) {
            if (explicitProviderId != null && provider.id != explicitProviderId) continue
            if (matchedRule != null && matchedRule.allowedProviderIdsCsv.isNotBlank()) {
                val allowed = matchedRule.allowedProviderIdsCsv.split(",").map { it.trim() }
                if (provider.id !in allowed && provider.name !in allowed) continue
            }

            val selectedKeys = selectKeysForProvider(allKeys.filter { it.providerId == provider.id })
            val providerModels = allModels.filter { it.providerId == provider.id }
            val resolvedModel = aliasedModels.firstOrNull { it.providerId == provider.id }
                ?: providerModels.firstOrNull { it.isDefault }
                ?: providerModels.firstOrNull()
            val modelId = resolvedModel?.modelId?.ifBlank { null }
                ?: targetAliasOrModel.takeIf { it.isNotBlank() && !it.startsWith("jh-") }
                ?: provider.defaultModel

            if (modelId.isBlank()) continue
            val pricing = ProviderPresets.lookupPricing(modelId)
            val estCost = pricing?.let { (it.inputPer1M + it.outputPer1M) / 2.0 }

            if (selectedKeys.isEmpty()) {
                // Allow keyless only if private local HTTP or user didn't add separate vault key yet
                continue
            }

            for (key in selectedKeys) {
                val decrypted = CryptoManager.decrypt(key.encryptedKey)
                if (decrypted.isBlank()) continue
                candidates += RouteCandidate(
                    id = provider.id,
                    displayName = provider.name,
                    baseUrl = provider.baseUrl,
                    apiFormat = provider.apiFormat,
                    apiKey = decrypted,
                    keyEntityId = key.id,
                    customHeadersJson = provider.customHeadersJson,
                    modelId = modelId,
                    isLocal = false,
                    priority = provider.priority,
                    lastLatencyMs = provider.lastLatencyMs ?: 5000L,
                    estimatedCostPer1M = estCost
                )
            }
        }

        // Local AI servers
        val allowCloud = settings.localRoutingPolicy != LocalRoutingPolicy.LOCAL_ONLY &&
            (settings.localRoutingPolicy != LocalRoutingPolicy.LOCAL_FIRST || settings.allowCloudFallbackForLocal || mode != RoutingMode.LOCAL_FIRST)

        val localCandidates = mutableListOf<RouteCandidate>()
        for (srv in localServers) {
            val localKey = "local:${srv.id}"
            if (explicitProviderId != null && explicitProviderId != localKey) continue
            val srvModels = allModels.filter { it.providerId == localKey }
            val resolvedModel = aliasedModels.firstOrNull { it.providerId == localKey }
                ?: srvModels.firstOrNull { it.isDefault }
                ?: srvModels.firstOrNull()
            val modelId = resolvedModel?.modelId?.ifBlank { null } ?: srv.defaultModel
            if (modelId.isBlank()) continue
            val authToken = if (srv.hasAuth) CryptoManager.decrypt(srv.encryptedAuthToken) else ""
            val format = if (srv.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI

            localCandidates += RouteCandidate(
                id = localKey,
                displayName = srv.name,
                baseUrl = srv.serverUrl,
                apiFormat = format,
                apiKey = authToken,
                keyEntityId = null,
                customHeadersJson = "",
                modelId = modelId,
                isLocal = true,
                priority = 0,
                lastLatencyMs = srv.lastLatencyMs ?: 1000L,
                estimatedCostPer1M = 0.0
            )
        }

        val combined = if (settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_ONLY) {
            localCandidates
        } else if (!allowCloud && localCandidates.isNotEmpty()) {
            localCandidates
        } else {
            localCandidates + candidates
        }

        val sorted = when (mode) {
            RoutingMode.MANUAL -> combined
            RoutingMode.PRIORITY -> combined.sortedBy { it.priority }
            RoutingMode.LOWEST_LATENCY -> combined.sortedBy { it.lastLatencyMs }
            RoutingMode.LOWEST_COST -> combined.sortedBy { it.estimatedCostPer1M ?: Double.MAX_VALUE }
            RoutingMode.LOCAL_FIRST -> combined.sortedWith(compareByDescending<RouteCandidate> { it.isLocal }.thenBy { it.priority })
            RoutingMode.CLOUD_FIRST -> combined.sortedWith(compareBy<RouteCandidate> { it.isLocal }.thenBy { it.priority })
            RoutingMode.ROUND_ROBIN -> {
                if (combined.size <= 1) combined else {
                    val idx = roundRobinCounter.getAndIncrement() % combined.size
                    combined.drop(idx) + combined.take(idx)
                }
            }
            RoutingMode.AUTOMATIC -> {
                if (matchedRule != null) {
                    combined.sortedWith(
                        compareByDescending<RouteCandidate> {
                            it.id == matchedRule.preferredProviderId || it.displayName == matchedRule.preferredProviderId
                        }.thenBy { it.priority }.thenBy { it.lastLatencyMs }
                    )
                } else {
                    combined.sortedWith(compareBy<RouteCandidate> { it.priority }.thenBy { it.lastLatencyMs })
                }
            }
        }

        val reason = when {
            explicitProviderId != null -> "Manual Provider Selection"
            matchedRule != null -> "Rule '${matchedRule.name}' (${mode.name})"
            else -> "Routing Mode: ${mode.name}"
        }
        return sorted to reason
    }

    private fun selectKeysForProvider(keys: List<ApiKeyEntity>): List<ApiKeyEntity> {
        val eligible = keys.filter { it.enabled && it.status != KeyStatus.DISABLED && it.status != KeyStatus.INVALID && it.status != KeyStatus.EXPIRED }
        if (eligible.isEmpty()) return emptyList()
        val policy = eligible.first().selectionPolicy
        return when (policy) {
            KeySelectionPolicy.MANUAL -> eligible.filter { it.isDefault }.ifEmpty { listOf(eligible.first()) }
            KeySelectionPolicy.PRIORITY -> eligible.sortedWith(compareByDescending<ApiKeyEntity> { it.isDefault }.thenBy { it.priority })
            KeySelectionPolicy.ROUND_ROBIN -> {
                val idx = keyRoundRobinCounter.getAndIncrement() % eligible.size
                eligible.drop(idx) + eligible.take(idx)
            }
            KeySelectionPolicy.FAILOVER -> eligible.sortedBy { it.priority }
        }
    }

    private suspend fun updateProviderAndKeyHealth(candidate: RouteCandidate, res: ChatExecutionResult) {
        if (!candidate.isLocal) {
            db.providerDao().getById(candidate.id)?.let { p ->
                val newStatus = when {
                    res.success -> ProviderStatus.CONNECTED
                    res.statusCode == 429 -> ProviderStatus.RATE_LIMITED
                    else -> ProviderStatus.ERROR
                }
                db.providerDao().upsert(
                    p.copy(
                        status = newStatus,
                        lastLatencyMs = res.latencyMs,
                        lastError = if (res.success) null else res.safeErrorMessage,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                )
            }
            candidate.keyEntityId?.let { kid ->
                db.apiKeyDao().getAllOnce().firstOrNull { it.id == kid }?.let { k ->
                    val kStatus = when {
                        res.success -> KeyStatus.ACTIVE
                        res.statusCode == 429 -> KeyStatus.RATE_LIMITED
                        res.statusCode == 401 || res.statusCode == 403 -> KeyStatus.INVALID
                        else -> k.status
                    }
                    db.apiKeyDao().upsert(
                        k.copy(
                            status = kStatus,
                            usageCount = k.usageCount + 1,
                            lastUsedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        } else {
            val srvId = candidate.id.removePrefix("local:")
            db.localAiServerDao().getAllOnce().firstOrNull { it.id == srvId }?.let { srv ->
                db.localAiServerDao().upsert(
                    srv.copy(
                        status = if (res.success) ProviderStatus.CONNECTED else ProviderStatus.ERROR,
                        lastLatencyMs = res.latencyMs,
                        lastError = if (res.success) null else res.safeErrorMessage
                    )
                )
            }
        }
    }

    private suspend fun recordDecision(
        decision: RouterDecisionSnapshot,
        result: ChatExecutionResult,
        candidate: RouteCandidate?,
        deviceName: String,
        endpoint: String,
        routingModeName: String
    ) {
        _recentDecisions.update { (listOf(decision) + it).take(30) }

        val pricing = if (candidate != null && !candidate.isLocal) ProviderPresets.lookupPricing(candidate.modelId) else null
        val estCost = pricing?.let {
            (result.inputTokens / 1_000_000.0) * it.inputPer1M +
                (result.outputTokens / 1_000_000.0) * it.outputPer1M
        }

        db.metricAndLogDao().insertMetric(
            RequestMetricEntity(
                requestId = decision.requestId,
                providerId = candidate?.id ?: "none",
                providerName = decision.selectedProvider,
                modelId = decision.selectedModel,
                deviceName = deviceName,
                endpoint = endpoint,
                statusCode = result.statusCode,
                success = result.success,
                latencyMs = result.latencyMs,
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
                estimatedCostUsd = estCost,
                fallbackAttempts = (decision.attemptCount - 1).coerceAtLeast(0),
                routingMode = routingModeName,
                routingReason = decision.routingReason,
                errorCode = result.errorCode,
                safeErrorMessage = result.safeErrorMessage
            )
        )

        db.metricAndLogDao().insertLog(
            ActivityLogEntity(
                category = if (result.success) ActivityCategory.GATEWAY_REQUEST else ActivityCategory.PROVIDER_ERROR,
                relatedEntity = "${decision.selectedProvider} (${decision.selectedModel})",
                status = decision.finalStatus,
                safeMessage = if (result.success) {
                    "$endpoint completed in ${result.latencyMs}ms (${result.inputTokens}+${result.outputTokens} tok)"
                } else {
                    "$endpoint failed: ${result.safeErrorMessage ?: result.errorCode}"
                }
            )
        )
    }
}
