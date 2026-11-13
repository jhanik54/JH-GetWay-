package com.example.core.i18n

import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage {
    ENGLISH,
    BENGALI,
    SYSTEM
}

interface AppStrings {
    val appName: String
    val tagline: String
    val orgName: String

    // Navigation
    val navHome: String
    val navProviders: String
    val navPlayground: String
    val navAnalytics: String
    val navSettings: String
    val navMore: String
    val navModels: String
    val navGateway: String
    val navSmartRouter: String
    val navCodingAgents: String
    val navLocalAi: String
    val navMcpServers: String
    val navWorkflows: String
    val navDevices: String
    val navVault: String
    val navActivityLogs: String

    // Common Actions & States
    val add: String
    val edit: String
    val delete: String
    val save: String
    val cancel: String
    val close: String
    val confirm: String
    val skip: String
    val next: String
    val back: String
    val finish: String
    val test: String
    val refresh: String
    val search: String
    val filter: String
    val clear: String
    val exportLabel: String
    val importLabel: String
    val copy: String
    val copied: String
    val retry: String
    val enable: String
    val disable: String
    val enabled: String
    val disabled: String
    val loading: String
    val empty: String
    val error: String
    val success: String
    val offline: String
    val unknown: String
    val connected: String
    val disconnected: String
    val checking: String
    val rateLimited: String
    val running: String
    val stopped: String
    val start: String
    val stop: String
    val details: String
    val actions: String
    val notes: String
    val optional: String
    val requiredField: String
    val deleteConfirmTitle: String
    val deleteConfirmMessage: String

    // Dashboard
    val dashboardTitle: String
    val gatewayStatus: String
    val gatewayAddress: String
    val connectedDevices: String
    val activeRequests: String
    val startGateway: String
    val stopGateway: String
    val providerOverview: String
    val totalProviders: String
    val activeProviders: String
    val failedProviders: String
    val rateLimitedProviders: String
    val modelOverview: String
    val totalModels: String
    val availableModels: String
    val localModels: String
    val cloudModels: String
    val usageOverview: String
    val todayRequests: String
    val totalRequests: String
    val inputTokens: String
    val outputTokens: String
    val estimatedCost: String
    val performanceOverview: String
    val avgLatency: String
    val successRate: String
    val errorCount: String
    val fallbackCount: String
    val recentActivity: String
    val noActivityYet: String
    val quickAccess: String

    // Onboarding
    val onboardingWelcomeTitle: String
    val onboardingWelcomeSubtitle: String
    val selectLanguage: String
    val selectTheme: String
    val onboardingGatewayTitle: String
    val localGatewayMode: String
    val remoteGatewayMode: String
    val existingGatewayMode: String
    val onboardingProviderTitle: String
    val onboardingProviderDesc: String
    val onboardingKeyTitle: String
    val onboardingKeyDesc: String
    val onboardingTestTitle: String
    val runConnectionTest: String
    val onboardingSummaryTitle: String
    val goToDashboard: String

    // Providers
    val providersTitle: String
    val addProvider: String
    val editProvider: String
    val noProvidersTitle: String
    val noProvidersMessage: String
    val providerName: String
    val providerType: String
    val apiBaseUrl: String
    val apiKeyLabel: String
    val apiFormat: String
    val defaultModel: String
    val priorityLabel: String
    val customHeaders: String
    val testConnection: String
    val fetchModels: String
    val viewUsage: String
    val viewErrors: String
    val providerDeleteImpactWarning: String

    // Models
    val modelsTitle: String
    val noModelsTitle: String
    val noModelsMessage: String
    val modelName: String
    val modelId: String
    val modelType: String
    val contextWindow: String
    val toolCalling: String
    val streamingLabel: String
    val setDefault: String
    val setAlias: String
    val compareModel: String
    val testModel: String
    val aliasHint: String

    // Gateway
    val gatewayTitle: String
    val unifiedEndpoints: String
    val gatewaySettings: String
    val gatewayName: String
    val portLabel: String
    val requestTimeoutSec: String
    val maxRequestSizeKb: String
    val authRequired: String
    val rateLimitPerMin: String
    val loggingLevel: String
    val testEndpoint: String

    // Smart Router
    val smartRouterTitle: String
    val routingMode: String
    val routingRules: String
    val addRule: String
    val noRulesMessage: String
    val fallbackSystem: String
    val enableFallback: String
    val maxRetries: String
    val routerMonitoring: String
    val selectedProvider: String
    val selectedModel: String
    val routingReason: String
    val fallbackUsed: String
    val attemptCount: String

    // Coding Agents
    val codingAgentsTitle: String
    val addAgent: String
    val noAgentsMessage: String
    val agentName: String
    val agentType: String
    val dailyTokenLimit: String
    val copyConfigSnippet: String

    // Local AI
    val localAiTitle: String
    val addLocalServer: String
    val noLocalServersMessage: String
    val serverName: String
    val serverUrl: String
    val engineType: String
    val discoverModels: String
    val localRoutingPolicy: String
    val allowCloudFallback: String
    val privateNetworkBadge: String
    val publicNetworkBadge: String
    val localNetworkConsentLabel: String

    // MCP Servers
    val mcpTitle: String
    val addMcpServer: String
    val noMcpServersMessage: String
    val transportType: String
    val commandOrUrl: String
    val argumentsLabel: String
    val envVarsLabel: String
    val discoverTools: String
    val executeTool: String
    val toolPermission: String
    val approvalRequiredTitle: String
    val approvalRequiredMessage: String
    val approveAndRun: String

    // Workflows
    val workflowsTitle: String
    val createWorkflow: String
    val templatesLabel: String
    val runWorkflow: String
    val stopWorkflow: String
    val addNode: String
    val connectNodes: String
    val executionHistory: String
    val workflowInputLabel: String
    val retryFailedNode: String
    val cycleDetectedError: String
    val missingStartEndError: String

    // Playground
    val playgroundTitle: String
    val singleChatMode: String
    val multiModelCompareMode: String
    val systemPromptLabel: String
    val messageInputHint: String
    val send: String
    val parameters: String
    val temperature: String
    val topP: String
    val maxOutputTokens: String
    val stopSequences: String
    val jsonOutput: String
    val unsupportedParam: String
    val newChat: String
    val clearChat: String
    val exportChat: String
    val regenerate: String
    val compareModelsAction: String

    // Analytics
    val analyticsTitle: String
    val today: String
    val last7Days: String
    val last30Days: String
    val allTime: String
    val requestsOverTime: String
    val providerUsage: String
    val latencyMsLabel: String
    val p95Latency: String
    val requestDetails: String
    val noAnalyticsData: String
    val currencyLabel: String

    // Devices
    val devicesTitle: String
    val addDevice: String
    val noDevicesMessage: String
    val deviceName: String
    val deviceType: String
    val regenerateToken: String
    val revokeAccess: String
    val revokedBadge: String

    // API Key Vault
    val vaultTitle: String
    val addKey: String
    val noKeysMessage: String
    val keyLabel: String
    val keyPolicy: String
    val rotateKey: String
    val setDefaultKey: String
    val policyComplianceNote: String

    // Activity & Logs
    val activityTitle: String
    val clearLogs: String
    val exportLogs: String
    val searchLogsHint: String

    // Settings
    val settingsTitle: String
    val generalSection: String
    val gatewaySection: String
    val routingSection: String
    val securitySection: String
    val notificationsSection: String
    val dataBackupSection: String
    val aboutSection: String
    val themeLabel: String
    val themeLight: String
    val themeDark: String
    val themeSystem: String
    val languageLabel: String
    val langEnglish: String
    val langBengali: String
    val langSystem: String
    val appLockLabel: String
    val saveChatHistoryLabel: String
    val exportEncryptedBackup: String
    val importEncryptedBackup: String
    val clearLocalData: String
    val clearLocalDataWarning: String
    val passphraseLabel: String
    val versionLabel: String
    val copyrightLabel: String
    val privacyPolicyTitle: String
    val termsTitle: String
    val openSourceLicensesTitle: String
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { EnglishStrings }
