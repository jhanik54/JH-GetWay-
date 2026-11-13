package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JhGetWayApplication
import com.example.core.i18n.AppLanguage
import com.example.core.security.CryptoManager
import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.*
import com.example.gateway.*
import com.example.network.ChatMessagePayload
import com.example.network.ProviderPresets
import com.example.network.StreamEvent
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class CompareModelOutput(
    val modelId: String,
    val providerName: String,
    val responseText: String,
    val latencyMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double?,
    val error: String?
)

data class PendingMcpApproval(
    val server: McpServerEntity,
    val tool: McpToolEntity,
    val deferred: CompletableDeferred<Boolean>
)

class GatewayViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JhGetWayApplication
    private val db = app.database
    private val prefs = app.preferences
    private val httpClient = app.providerHttpClient
    private val mcpClient = app.mcpHttpClient
    val router = app.smartRouter
    val gatewayServer = app.gatewayServer
    val workflowEngine = app.workflowEngine
    private val backupManager = app.backupManager

    val settings: StateFlow<GatewaySettingsState> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, GatewaySettingsState())

    val gatewayRuntime: StateFlow<GatewayRuntimeState> = gatewayServer.runtimeState
    val recentRouterDecisions: StateFlow<List<RouterDecisionSnapshot>> = router.recentDecisions

    val providers: StateFlow<List<ProviderEntity>> = db.providerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val apiKeys: StateFlow<List<ApiKeyEntity>> = db.apiKeyDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val models: StateFlow<List<ModelEntity>> = db.modelDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routingRules: StateFlow<List<RoutingRuleEntity>> = db.routingRuleDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val codingAgents: StateFlow<List<CodingAgentEntity>> = db.codingAgentDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localServers: StateFlow<List<LocalAiServerEntity>> = db.localAiServerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mcpServers: StateFlow<List<McpServerEntity>> = db.mcpDao().observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mcpTools: StateFlow<List<McpToolEntity>> = db.mcpDao().observeTools()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workflows: StateFlow<List<WorkflowEntity>> = db.workflowDao().observeWorkflows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workflowRuns: StateFlow<List<WorkflowRunEntity>> = db.workflowDao().observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val devices: StateFlow<List<DeviceEntity>> = db.deviceDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val metrics: StateFlow<List<RequestMetricEntity>> = db.metricAndLogDao().observeMetrics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activityLogs: StateFlow<List<ActivityLogEntity>> = db.metricAndLogDao().observeLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playgroundSessions: StateFlow<List<PlaygroundSessionEntity>> = db.playgroundDao().observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSessionMessages: StateFlow<List<PlaygroundMessageEntity>> = _activeSessionId
        .flatMapLatest { sid ->
            if (sid == null) flowOf(emptyList()) else db.playgroundDao().observeMessages(sid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ephemeralMessages = MutableStateFlow<List<PlaygroundMessageEntity>>(emptyList())
    val ephemeralMessages: StateFlow<List<PlaygroundMessageEntity>> = _ephemeralMessages.asStateFlow()

    private val _streamingBuffer = MutableStateFlow("")
    val streamingBuffer: StateFlow<String> = _streamingBuffer.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _compareResults = MutableStateFlow<List<CompareModelOutput>>(emptyList())
    val compareResults: StateFlow<List<CompareModelOutput>> = _compareResults.asStateFlow()

    private val _isComparing = MutableStateFlow(false)
    val isComparing: StateFlow<Boolean> = _isComparing.asStateFlow()

    private val _workflowNodeProgress = MutableStateFlow<List<NodeExecutionResult>>(emptyList())
    val workflowNodeProgress: StateFlow<List<NodeExecutionResult>> = _workflowNodeProgress.asStateFlow()

    private val _isWorkflowRunning = MutableStateFlow(false)
    val isWorkflowRunning: StateFlow<Boolean> = _isWorkflowRunning.asStateFlow()

    private val _pendingMcpApproval = MutableStateFlow<PendingMcpApproval?>(null)
    val pendingMcpApproval: StateFlow<PendingMcpApproval?> = _pendingMcpApproval.asStateFlow()

    private val _uiBannerMessage = MutableStateFlow<String?>(null)
    val uiBannerMessage: StateFlow<String?> = _uiBannerMessage.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(true)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private var streamingJob: Job? = null
    private var workflowJob: Job? = null

    init {
        viewModelScope.launch {
            val s = prefs.settingsFlow.first()
            if (s.appLockEnabled && s.appLockPinHash.isNotBlank()) {
                _isAppUnlocked.value = false
            }
            if (s.logRetentionDays > 0) {
                val cutoff = System.currentTimeMillis() - s.logRetentionDays * 86_400_000L
                db.metricAndLogDao().deleteLogsOlderThan(cutoff)
            }
        }
    }

    fun clearBanner() { _uiBannerMessage.value = null }
    fun postBanner(msg: String) { _uiBannerMessage.value = msg }

    // Theme & Language & Onboarding
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setLanguage(lang: AppLanguage) = viewModelScope.launch { prefs.setLanguage(lang) }
    fun setGatewayMode(mode: GatewayMode) = viewModelScope.launch { prefs.setGatewayMode(mode) }
    fun completeOnboarding() = viewModelScope.launch { prefs.setOnboardingCompleted(true) }

    fun toggleGatewayServer() = viewModelScope.launch {
        if (gatewayRuntime.value.status == GatewayRunStatus.RUNNING) {
            gatewayServer.stop()
        } else {
            gatewayServer.start(settings.value)
        }
    }

    // Providers & Keys
    fun saveProvider(
        existingId: String?,
        name: String,
        type: ProviderType,
        presetId: String,
        baseUrl: String,
        apiFormat: ApiFormat,
        defaultModel: String,
        priority: Int,
        enabled: Boolean,
        customHeadersText: String,
        notes: String,
        initialApiKey: String
    ) = viewModelScope.launch {
        val headersJson = buildHeadersJson(customHeadersText)
        val provider = ProviderEntity(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name.trim(),
            type = type,
            presetId = presetId,
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiFormat = apiFormat,
            defaultModel = defaultModel.trim(),
            priority = priority,
            enabled = enabled,
            customHeadersJson = headersJson,
            notes = notes.trim()
        )
        db.providerDao().upsert(provider)
        if (initialApiKey.isNotBlank()) {
            addApiKey(provider.id, "${provider.name} Primary Key", initialApiKey.trim(), 1, isDefault = true, KeySelectionPolicy.PRIORITY)
        }
        if (defaultModel.isNotBlank()) {
            val existingModels = db.modelDao().getByProvider(provider.id)
            if (existingModels.none { it.modelId == defaultModel.trim() }) {
                val pricing = ProviderPresets.lookupPricing(defaultModel.trim())
                db.modelDao().upsert(
                    ModelEntity(
                        id = "${provider.id}::${defaultModel.trim()}",
                        modelId = defaultModel.trim(),
                        displayName = defaultModel.trim(),
                        providerId = provider.id,
                        providerName = provider.name,
                        modelType = ProviderPresets.inferModelType(defaultModel.trim()),
                        inputPricePer1M = pricing?.inputPer1M,
                        outputPricePer1M = pricing?.outputPer1M,
                        pricingUpdatedAt = pricing?.updatedAt
                    )
                )
            }
        }
        logEvent(ActivityCategory.PROVIDER_CONNECTION, provider.name, "SAVED", "Provider configuration saved")
    }

    fun toggleProviderEnabled(provider: ProviderEntity) = viewModelScope.launch {
        db.providerDao().upsert(
            provider.copy(
                enabled = !provider.enabled,
                status = if (!provider.enabled) ProviderStatus.UNKNOWN else ProviderStatus.DISABLED
            )
        )
    }

    fun deleteProvider(provider: ProviderEntity) = viewModelScope.launch {
        db.providerDao().deleteById(provider.id)
        db.apiKeyDao().deleteByProviderId(provider.id)
        db.modelDao().deleteByProviderId(provider.id)
        logEvent(ActivityCategory.PROVIDER_CONNECTION, provider.name, "DELETED", "Provider and linked models/keys removed")
    }

    fun testProviderConnection(provider: ProviderEntity) = viewModelScope.launch {
        db.providerDao().upsert(provider.copy(status = ProviderStatus.CHECKING))
        val keys = db.apiKeyDao().getForProvider(provider.id).filter { it.enabled }
        val activeKey = keys.firstOrNull { it.isDefault } ?: keys.firstOrNull()
        val plainKey = activeKey?.let { CryptoManager.decrypt(it.encryptedKey) }.orEmpty()

        val result = httpClient.testProviderConnection(
            provider = provider,
            apiKey = plainKey,
            allowLocalHttp = settings.value.localNetworkAccessAllowed,
            timeoutSec = settings.value.gatewayTimeoutSec
        )
        db.providerDao().upsert(
            provider.copy(
                status = result.status,
                lastLatencyMs = result.latencyMs,
                lastError = if (result.status == ProviderStatus.CONNECTED) null else result.message,
                lastCheckedAt = System.currentTimeMillis()
            )
        )
        if (activeKey != null) {
            val kStatus = when (result.status) {
                ProviderStatus.CONNECTED -> KeyStatus.ACTIVE
                ProviderStatus.RATE_LIMITED -> KeyStatus.RATE_LIMITED
                ProviderStatus.ERROR -> if (result.httpCode == 401 || result.httpCode == 403) KeyStatus.INVALID else KeyStatus.UNKNOWN
                else -> activeKey.status
            }
            db.apiKeyDao().upsert(activeKey.copy(status = kStatus, lastTestedAt = System.currentTimeMillis()))
        }
        logEvent(ActivityCategory.PROVIDER_CONNECTION, provider.name, result.status.name, result.message)
        postBanner("${provider.name}: ${result.message}")
    }

    fun fetchProviderModels(provider: ProviderEntity) = viewModelScope.launch {
        val keys = db.apiKeyDao().getForProvider(provider.id).filter { it.enabled }
        val plainKey = keys.firstOrNull()?.let { CryptoManager.decrypt(it.encryptedKey) }.orEmpty()
        val result = httpClient.fetchProviderModels(provider, plainKey, settings.value.localNetworkAccessAllowed)
        result.fold(
            onSuccess = { fetched ->
                val existing = db.modelDao().getByProvider(provider.id).associateBy { it.modelId }
                val merged = fetched.map { m ->
                    val prev = existing[m.modelId]
                    if (prev != null) m.copy(alias = prev.alias, isDefault = prev.isDefault, enabled = prev.enabled) else m
                }
                db.modelDao().upsertAll(merged)
                postBanner("${provider.name}: Fetched ${merged.size} models")
                logEvent(ActivityCategory.PROVIDER_CONNECTION, provider.name, "MODELS_FETCHED", "Discovered ${merged.size} models")
            },
            onFailure = { err ->
                postBanner("${provider.name}: ${err.message}")
                logEvent(ActivityCategory.PROVIDER_ERROR, provider.name, "FETCH_FAILED", err.message ?: "Failed to fetch models")
            }
        )
    }

    // API Key Vault
    fun addApiKey(
        providerId: String,
        label: String,
        plainKey: String,
        priority: Int,
        isDefault: Boolean,
        policy: KeySelectionPolicy
    ) = viewModelScope.launch {
        val existing = db.apiKeyDao().getForProvider(providerId)
        if (isDefault) {
            existing.filter { it.isDefault }.forEach { db.apiKeyDao().upsert(it.copy(isDefault = false)) }
        }
        val entity = ApiKeyEntity(
            providerId = providerId,
            label = label.trim().ifBlank { "Key #${existing.size + 1}" },
            encryptedKey = CryptoManager.encrypt(plainKey.trim()),
            maskedPreview = MaskingUtils.maskSecret(plainKey.trim()),
            priority = priority,
            isDefault = isDefault || existing.isEmpty(),
            enabled = true,
            status = KeyStatus.UNKNOWN,
            selectionPolicy = policy
        )
        db.apiKeyDao().upsert(entity)
        logEvent(ActivityCategory.KEY_UPDATE, entity.label, "ADDED", "Encrypted key stored in Vault")
    }

    fun rotateApiKey(keyEntity: ApiKeyEntity, newPlainKey: String) = viewModelScope.launch {
        db.apiKeyDao().upsert(
            keyEntity.copy(
                encryptedKey = CryptoManager.encrypt(newPlainKey.trim()),
                maskedPreview = MaskingUtils.maskSecret(newPlainKey.trim()),
                status = KeyStatus.UNKNOWN
            )
        )
        logEvent(ActivityCategory.KEY_UPDATE, keyEntity.label, "ROTATED", "API key rotated securely")
    }

    fun testApiKey(keyEntity: ApiKeyEntity) = viewModelScope.launch {
        val provider = db.providerDao().getById(keyEntity.providerId) ?: return@launch
        val plain = CryptoManager.decrypt(keyEntity.encryptedKey)
        val res = httpClient.testProviderConnection(provider, plain, settings.value.localNetworkAccessAllowed)
        val status = when (res.status) {
            ProviderStatus.CONNECTED -> KeyStatus.ACTIVE
            ProviderStatus.RATE_LIMITED -> KeyStatus.RATE_LIMITED
            ProviderStatus.ERROR -> if (res.httpCode == 401 || res.httpCode == 403) KeyStatus.INVALID else KeyStatus.UNKNOWN
            else -> KeyStatus.UNKNOWN
        }
        db.apiKeyDao().upsert(keyEntity.copy(status = status, lastTestedAt = System.currentTimeMillis()))
        logEvent(ActivityCategory.KEY_UPDATE, keyEntity.label, status.name, res.message)
        postBanner("${keyEntity.label}: ${res.message}")
    }

    fun setDefaultApiKey(keyEntity: ApiKeyEntity) = viewModelScope.launch {
        db.apiKeyDao().getForProvider(keyEntity.providerId).forEach {
            db.apiKeyDao().upsert(it.copy(isDefault = it.id == keyEntity.id))
        }
    }

    fun toggleApiKeyEnabled(keyEntity: ApiKeyEntity) = viewModelScope.launch {
        db.apiKeyDao().upsert(
            keyEntity.copy(
                enabled = !keyEntity.enabled,
                status = if (!keyEntity.enabled) KeyStatus.UNKNOWN else KeyStatus.DISABLED
            )
        )
    }

    fun deleteApiKey(keyEntity: ApiKeyEntity) = viewModelScope.launch {
        db.apiKeyDao().deleteById(keyEntity.id)
        logEvent(ActivityCategory.KEY_UPDATE, keyEntity.label, "DELETED", "Key removed from Vault")
    }

    // Models
    fun toggleModelEnabled(model: ModelEntity) = viewModelScope.launch {
        db.modelDao().upsert(model.copy(enabled = !model.enabled))
    }

    fun setDefaultModel(model: ModelEntity) = viewModelScope.launch {
        db.modelDao().clearDefaultFlag()
        db.modelDao().upsert(model.copy(isDefault = true, enabled = true))
        prefs.setDefaultModelAndProvider(model.modelId, model.providerId)
    }

    fun setModelAlias(model: ModelEntity, alias: String) = viewModelScope.launch {
        db.modelDao().upsert(model.copy(alias = alias.trim()))
    }

    // Smart Router & Rules
    fun updateRoutingSettings(mode: RoutingMode, fallbackEnabled: Boolean, maxRetries: Int) = viewModelScope.launch {
        prefs.updateRoutingSettings(mode, fallbackEnabled, maxRetries)
    }

    fun saveRoutingRule(rule: RoutingRuleEntity) = viewModelScope.launch {
        db.routingRuleDao().upsert(rule)
    }

    fun deleteRoutingRule(rule: RoutingRuleEntity) = viewModelScope.launch {
        db.routingRuleDao().deleteById(rule.id)
    }

    // Coding Agents
    fun saveCodingAgent(
        existingId: String?,
        name: String,
        type: CodingAgentType,
        modelAlias: String,
        streaming: Boolean,
        toolCalling: Boolean,
        dailyTokenLimit: Long?
    ) = viewModelScope.launch {
        val rawToken = CryptoManager.generateGatewayToken("jhgw_ag")
        val existing = existingId?.let { id -> codingAgents.value.firstOrNull { it.id == id } }
        val agent = CodingAgentEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            agentType = type,
            modelAlias = modelAlias.trim().ifBlank { "jh-coder" },
            streaming = streaming,
            toolCalling = toolCalling,
            dailyTokenLimit = dailyTokenLimit,
            tokensUsedToday = existing?.tokensUsedToday ?: 0,
            requestCount = existing?.requestCount ?: 0,
            enabled = existing?.enabled ?: true,
            encryptedToken = existing?.encryptedToken ?: CryptoManager.encrypt(rawToken),
            tokenPreview = existing?.tokenPreview ?: MaskingUtils.maskSecret(rawToken)
        )
        db.codingAgentDao().upsert(agent)
    }

    fun decryptAgentToken(agent: CodingAgentEntity): String = CryptoManager.decrypt(agent.encryptedToken)
    fun toggleAgentEnabled(agent: CodingAgentEntity) = viewModelScope.launch { db.codingAgentDao().upsert(agent.copy(enabled = !agent.enabled)) }
    fun deleteAgent(agent: CodingAgentEntity) = viewModelScope.launch { db.codingAgentDao().deleteById(agent.id) }

    fun testCodingAgentGateway(agent: CodingAgentEntity) = viewModelScope.launch {
        val res = router.executeRoutedChat(
            requestedModelOrAlias = agent.modelAlias,
            messages = listOf(ChatMessagePayload("user", "Reply with OK if coding agent gateway route is working.")),
            settings = settings.value,
            deviceName = "Agent: ${agent.name}"
        )
        postBanner(if (res.execution.success) "${agent.name}: Connected via ${res.decision.selectedProvider} (${res.execution.latencyMs}ms)" else "${agent.name}: ${res.execution.safeErrorMessage}")
    }

    // Local AI Bridge
    fun saveLocalServer(
        name: String,
        engineType: LocalEngineType,
        serverUrl: String,
        authToken: String,
        timeoutSec: Int,
        defaultModel: String
    ) = viewModelScope.launch {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        val isPrivate = NetworkAddressClassifier.isPrivateOrLoopbackHost(cleanUrl)
        val server = LocalAiServerEntity(
            name = name.trim(),
            engineType = engineType,
            serverUrl = cleanUrl,
            encryptedAuthToken = if (authToken.isNotBlank()) CryptoManager.encrypt(authToken.trim()) else "",
            hasAuth = authToken.isNotBlank(),
            timeoutSec = timeoutSec,
            defaultModel = defaultModel.trim(),
            isPrivateNetwork = isPrivate
        )
        db.localAiServerDao().upsert(server)
    }

    fun testLocalServer(server: LocalAiServerEntity) = viewModelScope.launch {
        db.localAiServerDao().upsert(server.copy(status = ProviderStatus.CHECKING))
        val token = if (server.hasAuth) CryptoManager.decrypt(server.encryptedAuthToken) else ""
        val res = httpClient.testLocalServer(server, token, settings.value.localNetworkAccessAllowed)
        db.localAiServerDao().upsert(
            server.copy(status = res.status, lastLatencyMs = res.latencyMs, lastError = if (res.status == ProviderStatus.CONNECTED) null else res.message)
        )
        postBanner("${server.name}: ${res.message}")
    }

    fun discoverLocalModels(server: LocalAiServerEntity) = viewModelScope.launch {
        val token = if (server.hasAuth) CryptoManager.decrypt(server.encryptedAuthToken) else ""
        httpClient.discoverLocalModels(server, token, settings.value.localNetworkAccessAllowed).fold(
            onSuccess = { list ->
                db.modelDao().upsertAll(list)
                postBanner("${server.name}: Discovered ${list.size} local models")
            },
            onFailure = { err -> postBanner("${server.name}: ${err.message}") }
        )
    }

    fun toggleLocalServer(server: LocalAiServerEntity) = viewModelScope.launch { db.localAiServerDao().upsert(server.copy(enabled = !server.enabled)) }
    fun deleteLocalServer(server: LocalAiServerEntity) = viewModelScope.launch {
        db.localAiServerDao().deleteById(server.id)
        db.modelDao().deleteByProviderId("local:${server.id}")
    }
    fun updateLocalAiSettings(policy: LocalRoutingPolicy, allowCloud: Boolean, allowLocalNet: Boolean) = viewModelScope.launch {
        prefs.updateLocalAiSettings(policy, allowCloud, allowLocalNet)
    }

    // MCP Servers
    fun saveMcpServer(
        name: String,
        transport: McpTransportType,
        endpointOrCommand: String,
        arguments: String,
        envText: String,
        authToken: String,
        timeoutSec: Int
    ) = viewModelScope.launch {
        val envJson = buildEnvJson(envText)
        val server = McpServerEntity(
            name = name.trim(),
            transportType = transport,
            endpointOrCommand = endpointOrCommand.trim(),
            arguments = arguments.trim(),
            encryptedEnvJson = if (envJson.isNotBlank()) CryptoManager.encrypt(envJson) else "",
            encryptedAuthToken = if (authToken.isNotBlank()) CryptoManager.encrypt(authToken.trim()) else "",
            timeoutSec = timeoutSec
        )
        db.mcpDao().upsertServer(server)
    }

    fun connectAndDiscoverMcp(server: McpServerEntity) = viewModelScope.launch {
        db.mcpDao().upsertServer(server.copy(status = ProviderStatus.CHECKING))
        val res = mcpClient.connectAndDiscover(
            server = server,
            authToken = CryptoManager.decrypt(server.encryptedAuthToken),
            envVarsJson = CryptoManager.decrypt(server.encryptedEnvJson),
            allowLocalHttp = settings.value.localNetworkAccessAllowed
        )
        if (res.success) {
            db.mcpDao().upsertServer(
                server.copy(
                    status = ProviderStatus.CONNECTED,
                    lastConnectedAt = System.currentTimeMillis(),
                    lastError = null,
                    resourcesJson = res.resourcesJson,
                    promptsJson = res.promptsJson
                )
            )
            db.mcpDao().deleteToolsForServer(server.id)
            db.mcpDao().upsertTools(res.tools)
            postBanner("${server.name}: Discovered ${res.tools.size} tools")
        } else {
            db.mcpDao().upsertServer(server.copy(status = ProviderStatus.ERROR, lastError = res.errorMessage))
            postBanner("${server.name}: ${res.errorMessage}")
        }
    }

    fun disconnectMcpServer(server: McpServerEntity) = viewModelScope.launch {
        db.mcpDao().upsertServer(server.copy(status = ProviderStatus.DISCONNECTED))
    }

    fun updateToolPermission(tool: McpToolEntity, permission: ToolPermission) = viewModelScope.launch {
        db.mcpDao().upsertTool(tool.copy(permission = permission))
    }

    fun executeMcpTool(server: McpServerEntity, tool: McpToolEntity, argsJson: String) = viewModelScope.launch {
        if (tool.permission == ToolPermission.DENIED) {
            postBanner("Permission denied for ${tool.toolName}")
            return@launch
        }
        if (tool.permission == ToolPermission.ASK_BEFORE_RUNNING) {
            val approved = requestMcpApprovalFromUi(server, tool)
            if (!approved) return@launch
        }
        val res = mcpClient.executeTool(
            server = server,
            toolName = tool.toolName,
            argumentsJson = argsJson,
            authToken = CryptoManager.decrypt(server.encryptedAuthToken),
            envVarsJson = CryptoManager.decrypt(server.encryptedEnvJson),
            allowLocalHttp = settings.value.localNetworkAccessAllowed
        )
        val preview = if (res.success) res.output else "ERROR: ${res.errorMessage}"
        db.mcpDao().upsertTool(tool.copy(lastResultPreview = preview, lastExecutedAt = System.currentTimeMillis()))
        logEvent(ActivityCategory.MCP_TOOL, "${server.name}/${tool.toolName}", if (res.success) "SUCCESS" else "ERROR", preview.take(180))
        postBanner(if (res.success) "${tool.toolName} executed" else "Tool error: ${res.errorMessage}")
    }

    fun deleteMcpServer(server: McpServerEntity) = viewModelScope.launch {
        db.mcpDao().deleteServerById(server.id)
        db.mcpDao().deleteToolsForServer(server.id)
    }

    private suspend fun requestMcpApprovalFromUi(server: McpServerEntity, tool: McpToolEntity): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        _pendingMcpApproval.value = PendingMcpApproval(server, tool, deferred)
        val result = deferred.await()
        _pendingMcpApproval.value = null
        return result
    }

    fun resolveMcpApproval(approved: Boolean) {
        _pendingMcpApproval.value?.deferred?.complete(approved)
    }

    // Workflows
    fun saveWorkflow(workflow: WorkflowEntity) = viewModelScope.launch {
        db.workflowDao().upsertWorkflow(workflow.copy(updatedAt = System.currentTimeMillis()))
    }

    fun duplicateWorkflow(workflow: WorkflowEntity) = viewModelScope.launch {
        db.workflowDao().upsertWorkflow(
            workflow.copy(
                id = UUID.randomUUID().toString(),
                name = "${workflow.name} (Copy)",
                isTemplate = false,
                lastRunStatus = null,
                lastRunAt = null
            )
        )
    }

    fun deleteWorkflow(workflow: WorkflowEntity) = viewModelScope.launch {
        db.workflowDao().deleteWorkflow(workflow.id)
    }

    fun runWorkflow(workflow: WorkflowEntity, inputText: String) {
        workflowJob?.cancel()
        workflowJob = viewModelScope.launch {
            _isWorkflowRunning.value = true
            try {
                val run = workflowEngine.executeWorkflow(
                    workflow = workflow,
                    inputText = inputText,
                    settings = settings.value,
                    onProgress = { _workflowNodeProgress.value = it },
                    onRequestMcpApproval = { srv, tool -> requestMcpApprovalFromUi(srv, tool) }
                )
                postBanner("Workflow '${workflow.name}': ${run.status}")
            } catch (_: CancellationException) {
                postBanner("Workflow stopped")
            } finally {
                _isWorkflowRunning.value = false
            }
        }
    }

    fun stopWorkflow() {
        workflowJob?.cancel()
        _isWorkflowRunning.value = false
    }

    // Playground
    fun selectOrCreateSession(sessionId: String?) = viewModelScope.launch {
        if (sessionId != null) {
            _activeSessionId.value = sessionId
        } else {
            val s = PlaygroundSessionEntity(title = "Session ${playgroundSessions.value.size + 1}")
            if (settings.value.saveChatHistoryAllowed) {
                db.playgroundDao().upsertSession(s)
            }
            _activeSessionId.value = s.id
            _ephemeralMessages.value = emptyList()
        }
    }

    fun clearActiveChat() = viewModelScope.launch {
        _activeSessionId.value?.let { sid ->
            db.playgroundDao().clearMessagesForSession(sid)
        }
        _ephemeralMessages.value = emptyList()
    }

    fun deleteSession(session: PlaygroundSessionEntity) = viewModelScope.launch {
        db.playgroundDao().deleteSession(session.id)
        db.playgroundDao().clearMessagesForSession(session.id)
        if (_activeSessionId.value == session.id) _activeSessionId.value = null
    }

    fun sendPlaygroundPrompt(
        prompt: String,
        systemPrompt: String,
        modelIdOrAlias: String,
        explicitProviderId: String?,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stopSequences: List<String>,
        jsonOutput: Boolean,
        streaming: Boolean
    ) {
        if (prompt.isBlank()) return
        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            val saveHistory = settings.value.saveChatHistoryAllowed
            var sid = _activeSessionId.value
            if (sid == null) {
                val newSession = PlaygroundSessionEntity(title = prompt.take(28), modelId = modelIdOrAlias, systemPrompt = systemPrompt)
                sid = newSession.id
                _activeSessionId.value = sid
                if (saveHistory) db.playgroundDao().upsertSession(newSession)
            }

            val userMsg = PlaygroundMessageEntity(sessionId = sid, role = "user", content = prompt)
            if (saveHistory) db.playgroundDao().insertMessage(userMsg)
            else _ephemeralMessages.update { it + userMsg }

            val history = if (saveHistory) activeSessionMessages.value else _ephemeralMessages.value
            val payloadMsgs = buildList {
                if (systemPrompt.isNotBlank()) add(ChatMessagePayload("system", systemPrompt))
                history.takeLast(12).forEach { add(ChatMessagePayload(it.role, it.content)) }
                if (history.lastOrNull()?.content != prompt) add(ChatMessagePayload("user", prompt))
            }

            _isStreaming.value = true
            _streamingBuffer.value = ""

            if (streaming) {
                router.streamRoutedChat(
                    requestedModelOrAlias = modelIdOrAlias,
                    messages = payloadMsgs,
                    settings = settings.value,
                    explicitProviderId = explicitProviderId,
                    deviceName = "Playground",
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens,
                    stopSequences = stopSequences,
                    jsonOutput = jsonOutput
                ).collect { (event, decision) ->
                    when (event) {
                        is StreamEvent.Delta -> _streamingBuffer.update { it + event.text }
                        is StreamEvent.Completed -> {
                            val pricing = ProviderPresets.lookupPricing(decision?.selectedModel.orEmpty())
                            val estCost = pricing?.let {
                                (event.result.inputTokens / 1_000_000.0) * it.inputPer1M +
                                    (event.result.outputTokens / 1_000_000.0) * it.outputPer1M
                            }
                            val assistantMsg = PlaygroundMessageEntity(
                                sessionId = sid,
                                role = "assistant",
                                content = event.result.content,
                                modelId = decision?.selectedModel ?: modelIdOrAlias,
                                providerName = decision?.selectedProvider.orEmpty(),
                                latencyMs = event.result.latencyMs,
                                inputTokens = event.result.inputTokens,
                                outputTokens = event.result.outputTokens,
                                estimatedCostUsd = estCost
                            )
                            if (saveHistory) db.playgroundDao().insertMessage(assistantMsg)
                            else _ephemeralMessages.update { it + assistantMsg }
                            _streamingBuffer.value = ""
                        }
                        is StreamEvent.Failed -> {
                            val errMsg = PlaygroundMessageEntity(
                                sessionId = sid,
                                role = "assistant",
                                content = event.error.safeErrorMessage ?: "Request failed",
                                modelId = decision?.selectedModel ?: modelIdOrAlias,
                                providerName = decision?.selectedProvider.orEmpty(),
                                latencyMs = event.error.latencyMs,
                                isError = true
                            )
                            if (saveHistory) db.playgroundDao().insertMessage(errMsg)
                            else _ephemeralMessages.update { it + errMsg }
                            _streamingBuffer.value = ""
                        }
                    }
                }
            } else {
                val res = router.executeRoutedChat(
                    requestedModelOrAlias = modelIdOrAlias,
                    messages = payloadMsgs,
                    settings = settings.value,
                    explicitProviderId = explicitProviderId,
                    deviceName = "Playground",
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens,
                    stopSequences = stopSequences,
                    jsonOutput = jsonOutput
                )
                val pricing = ProviderPresets.lookupPricing(res.decision.selectedModel)
                val estCost = pricing?.let {
                    (res.execution.inputTokens / 1_000_000.0) * it.inputPer1M +
                        (res.execution.outputTokens / 1_000_000.0) * it.outputPer1M
                }
                val msg = PlaygroundMessageEntity(
                    sessionId = sid,
                    role = "assistant",
                    content = if (res.execution.success) res.execution.content else (res.execution.safeErrorMessage ?: "Error"),
                    modelId = res.decision.selectedModel,
                    providerName = res.decision.selectedProvider,
                    latencyMs = res.execution.latencyMs,
                    inputTokens = res.execution.inputTokens,
                    outputTokens = res.execution.outputTokens,
                    estimatedCostUsd = estCost,
                    isError = !res.execution.success
                )
                if (saveHistory) db.playgroundDao().insertMessage(msg)
                else _ephemeralMessages.update { it + msg }
            }
            _isStreaming.value = false
        }
    }

    fun runModelComparison(prompt: String, systemPrompt: String, selectedModels: List<ModelEntity>) {
        if (prompt.isBlank() || selectedModels.isEmpty()) return
        viewModelScope.launch {
            _isComparing.value = true
            _compareResults.value = emptyList()
            val msgs = buildList {
                if (systemPrompt.isNotBlank()) add(ChatMessagePayload("system", systemPrompt))
                add(ChatMessagePayload("user", prompt))
            }
            val results = coroutineScope {
                selectedModels.map { m ->
                    async {
                        val routed = router.executeRoutedChat(
                            requestedModelOrAlias = m.modelId,
                            messages = msgs,
                            settings = settings.value,
                            explicitProviderId = m.providerId,
                            deviceName = "Playground Compare"
                        )
                        val pricing = ProviderPresets.lookupPricing(m.modelId)
                        val cost = pricing?.let {
                            (routed.execution.inputTokens / 1_000_000.0) * it.inputPer1M +
                                (routed.execution.outputTokens / 1_000_000.0) * it.outputPer1M
                        }
                        CompareModelOutput(
                            modelId = m.modelId,
                            providerName = m.providerName,
                            responseText = routed.execution.content,
                            latencyMs = routed.execution.latencyMs,
                            inputTokens = routed.execution.inputTokens,
                            outputTokens = routed.execution.outputTokens,
                            estimatedCostUsd = cost,
                            error = if (routed.execution.success) null else routed.execution.safeErrorMessage
                        )
                    }
                }.awaitAll()
            }
            _compareResults.value = results
            _isComparing.value = false
        }
    }

    // Devices
    fun addDevice(name: String, type: String, connectionType: DeviceConnectionType) = viewModelScope.launch {
        val raw = CryptoManager.generateGatewayToken("jhgw_dev")
        val dev = DeviceEntity(
            name = name.trim(),
            deviceType = type.trim(),
            deviceCode = "DEV-" + UUID.randomUUID().toString().take(6).uppercase(),
            encryptedToken = CryptoManager.encrypt(raw),
            tokenPreview = MaskingUtils.maskSecret(raw),
            connectionType = connectionType
        )
        db.deviceDao().upsert(dev)
        logEvent(ActivityCategory.DEVICE_CONNECTION, dev.name, "REGISTERED", "Device token provisioned")
    }

    fun decryptDeviceToken(device: DeviceEntity): String = CryptoManager.decrypt(device.encryptedToken)
    fun regenerateDeviceToken(device: DeviceEntity) = viewModelScope.launch {
        val raw = CryptoManager.generateGatewayToken("jhgw_dev")
        db.deviceDao().upsert(device.copy(encryptedToken = CryptoManager.encrypt(raw), tokenPreview = MaskingUtils.maskSecret(raw), revoked = false))
        logEvent(ActivityCategory.DEVICE_CONNECTION, device.name, "TOKEN_ROTATED", "Device token regenerated")
    }
    fun revokeDevice(device: DeviceEntity) = viewModelScope.launch {
        db.deviceDao().upsert(device.copy(revoked = true, enabled = false))
        logEvent(ActivityCategory.AUTH_EVENT, device.name, "REVOKED", "Device access revoked")
    }
    fun toggleDeviceEnabled(device: DeviceEntity) = viewModelScope.launch {
        if (!device.revoked) db.deviceDao().upsert(device.copy(enabled = !device.enabled))
    }
    fun renameDevice(device: DeviceEntity, newName: String) = viewModelScope.launch {
        db.deviceDao().upsert(device.copy(name = newName.trim()))
    }

    // Live Gateway Endpoint Test (actually calls running LocalGatewayHttpServer via loopback HTTP!)
    fun testLiveGatewayEndpoint(endpointPath: String, modelId: String, prompt: String, onResult: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val s = settings.value
        val base = if (s.gatewayMode == GatewayMode.LOCAL) gatewayRuntime.value.boundAddress else s.gatewayRemoteUrl
        if (gatewayRuntime.value.status != GatewayRunStatus.RUNNING) {
            withContext(Dispatchers.Main) { onResult("Gateway is stopped. Start Gateway first.") }
            return@launch
        }
        val devToken = devices.value.firstOrNull { !it.revoked && it.enabled }?.let { CryptoManager.decrypt(it.encryptedToken) }.orEmpty()
        val client = OkHttpClient()
        val reqBuilder = Request.Builder().url("${base.trimEnd('/')}$endpointPath")
        if (devToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $devToken")

        if (endpointPath == "/v1/models" || endpointPath == "/health") {
            reqBuilder.get()
        } else {
            val payload = when (endpointPath) {
                "/v1/messages" -> JSONObject()
                    .put("model", modelId)
                    .put("max_tokens", 256)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                "/v1/responses" -> JSONObject()
                    .put("model", modelId)
                    .put("input", prompt)
                else -> JSONObject()
                    .put("model", modelId)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            }
            reqBuilder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        val outText = runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                "HTTP ${resp.code}\n${resp.body?.string().orEmpty()}"
            }
        }.getOrElse { "Request failed: ${it.message}" }
        withContext(Dispatchers.Main) { onResult(outText) }
    }

    // Settings, Security, Backup & Logs
    fun updateGatewayConfig(
        name: String,
        mode: GatewayMode,
        host: String,
        port: Int,
        remoteUrl: String,
        authRequired: Boolean,
        timeoutSec: Int,
        maxReqKb: Int,
        streaming: Boolean,
        rateLimit: Int,
        loggingLevel: LoggingLevel
    ) = viewModelScope.launch {
        prefs.updateGatewaySettings(name, mode, host, port, remoteUrl, authRequired, timeoutSec, maxReqKb, streaming, rateLimit, loggingLevel)
    }

    fun setSaveChatHistory(allowed: Boolean) = viewModelScope.launch { prefs.setSaveChatHistory(allowed) }
    fun setCurrency(currency: String) = viewModelScope.launch { prefs.setCurrency(currency) }
    fun setLogRetentionDays(days: Int) = viewModelScope.launch { prefs.setLogRetentionDays(days) }
    fun updateNotificationPrefs(pf: Boolean, gd: Boolean, rl: Boolean, wd: Boolean, sec: Boolean) = viewModelScope.launch {
        prefs.updateNotificationPrefs(pf, gd, rl, wd, sec)
    }

    fun setAppLockPin(enabled: Boolean, rawPin: String) = viewModelScope.launch {
        val hash = if (enabled && rawPin.isNotBlank()) sha256(rawPin) else ""
        prefs.setAppLock(enabled && hash.isNotBlank(), hash)
        _isAppUnlocked.value = true
    }

    fun verifyUnlockPin(rawPin: String): Boolean {
        val ok = sha256(rawPin) == settings.value.appLockPinHash
        if (ok) _isAppUnlocked.value = true
        return ok
    }

    suspend fun exportEncryptedBackup(passphrase: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { backupManager.exportEncryptedBackup(passphrase) }
    }

    suspend fun restoreEncryptedBackup(payload: String, passphrase: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { backupManager.restoreEncryptedBackup(payload, passphrase) }
    }

    fun clearLogs() = viewModelScope.launch { db.metricAndLogDao().clearLogs() }

    fun clearAllLocalData() = viewModelScope.launch {
        gatewayServer.stop()
        db.providerDao().clearAll()
        db.apiKeyDao().clearAll()
        db.modelDao().clearAll()
        db.routingRuleDao().clearAll()
        db.codingAgentDao().clearAll()
        db.localAiServerDao().clearAll()
        db.mcpDao().clearServers()
        db.mcpDao().clearTools()
        db.workflowDao().clearRuns()
        db.workflowDao().clearWorkflows()
        db.deviceDao().clearAll()
        db.metricAndLogDao().clearMetrics()
        db.metricAndLogDao().clearLogs()
        db.playgroundDao().clearMessages()
        db.playgroundDao().clearSessions()
        workflowEngine.builtInTemplates().forEach { db.workflowDao().upsertWorkflow(it) }
        postBanner("Local data cleared")
    }

    private suspend fun logEvent(category: ActivityCategory, related: String, status: String, message: String) {
        db.metricAndLogDao().insertLog(
            ActivityLogEntity(category = category, relatedEntity = related, status = status, safeMessage = message)
        )
    }

    private fun buildHeadersJson(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.contains(":") }
        if (lines.isEmpty()) return ""
        val obj = JSONObject()
        lines.forEach { line ->
            val idx = line.indexOf(":")
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.isNotBlank()) obj.put(k, v)
        }
        return obj.toString()
    }

    private fun buildEnvJson(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.contains("=") }
        if (lines.isEmpty()) return ""
        val obj = JSONObject()
        lines.forEach { line ->
            val idx = line.indexOf("=")
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.isNotBlank()) obj.put(k, v)
        }
        return obj.toString()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
