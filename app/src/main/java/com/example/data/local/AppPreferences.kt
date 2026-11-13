package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.i18n.AppLanguage
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jh_getway_prefs")

enum class GatewayMode { LOCAL, REMOTE, EXISTING }
enum class RoutingMode {
    MANUAL, AUTOMATIC, PRIORITY, ROUND_ROBIN, LOWEST_LATENCY, LOWEST_COST, LOCAL_FIRST, CLOUD_FIRST
}
enum class LocalRoutingPolicy { LOCAL_ONLY, LOCAL_FIRST, CLOUD_FIRST, LOCAL_CLOUD_FALLBACK }
enum class LoggingLevel { MINIMAL, METADATA_ONLY, STANDARD }

data class GatewaySettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val onboardingCompleted: Boolean = false,
    val gatewayName: String = "JH GetWay Primary",
    val gatewayMode: GatewayMode = GatewayMode.LOCAL,
    val gatewayHost: String = "127.0.0.1",
    val gatewayPort: Int = 8787,
    val gatewayRemoteUrl: String = "",
    val gatewayAuthRequired: Boolean = true,
    val gatewayTimeoutSec: Int = 60,
    val gatewayMaxRequestKb: Int = 2048,
    val gatewayStreamingEnabled: Boolean = true,
    val gatewayRateLimitPerMin: Int = 60,
    val gatewayLoggingLevel: LoggingLevel = LoggingLevel.METADATA_ONLY,
    val defaultModelId: String = "",
    val defaultProviderId: String = "",
    val defaultRoutingMode: RoutingMode = RoutingMode.AUTOMATIC,
    val fallbackEnabled: Boolean = true,
    val maxRetries: Int = 2,
    val localRoutingPolicy: LocalRoutingPolicy = LocalRoutingPolicy.LOCAL_FIRST,
    val allowCloudFallbackForLocal: Boolean = false,
    val localNetworkAccessAllowed: Boolean = false,
    val saveChatHistoryAllowed: Boolean = true,
    val appLockEnabled: Boolean = false,
    val appLockPinHash: String = "",
    val analyticsCurrency: String = "USD",
    val logRetentionDays: Int = 30,
    val notifyProviderFailure: Boolean = true,
    val notifyGatewayDisconnected: Boolean = true,
    val notifyRateLimit: Boolean = true,
    val notifyWorkflowCompleted: Boolean = true,
    val notifySecurityEvent: Boolean = true
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val GATEWAY_NAME = stringPreferencesKey("gateway_name")
        val GATEWAY_MODE = stringPreferencesKey("gateway_mode")
        val GATEWAY_HOST = stringPreferencesKey("gateway_host")
        val GATEWAY_PORT = intPreferencesKey("gateway_port")
        val GATEWAY_REMOTE_URL = stringPreferencesKey("gateway_remote_url")
        val GATEWAY_AUTH_REQUIRED = booleanPreferencesKey("gateway_auth_required")
        val GATEWAY_TIMEOUT_SEC = intPreferencesKey("gateway_timeout_sec")
        val GATEWAY_MAX_REQ_KB = intPreferencesKey("gateway_max_req_kb")
        val GATEWAY_STREAMING = booleanPreferencesKey("gateway_streaming")
        val GATEWAY_RATE_LIMIT = intPreferencesKey("gateway_rate_limit")
        val GATEWAY_LOGGING_LEVEL = stringPreferencesKey("gateway_logging_level")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val DEFAULT_PROVIDER_ID = stringPreferencesKey("default_provider_id")
        val DEFAULT_ROUTING_MODE = stringPreferencesKey("default_routing_mode")
        val FALLBACK_ENABLED = booleanPreferencesKey("fallback_enabled")
        val MAX_RETRIES = intPreferencesKey("max_retries")
        val LOCAL_ROUTING_POLICY = stringPreferencesKey("local_routing_policy")
        val ALLOW_CLOUD_FALLBACK = booleanPreferencesKey("allow_cloud_fallback")
        val LOCAL_NETWORK_ALLOWED = booleanPreferencesKey("local_network_allowed")
        val SAVE_CHAT_HISTORY = booleanPreferencesKey("save_chat_history")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val ANALYTICS_CURRENCY = stringPreferencesKey("analytics_currency")
        val LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        val NOTIFY_PROVIDER_FAILURE = booleanPreferencesKey("notify_provider_failure")
        val NOTIFY_GATEWAY_DISC = booleanPreferencesKey("notify_gateway_disc")
        val NOTIFY_RATE_LIMIT = booleanPreferencesKey("notify_rate_limit")
        val NOTIFY_WORKFLOW_DONE = booleanPreferencesKey("notify_workflow_done")
        val NOTIFY_SECURITY = booleanPreferencesKey("notify_security")
    }

    val settingsFlow: Flow<GatewaySettingsState> = context.dataStore.data.map { p ->
        GatewaySettingsState(
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            language = runCatching { AppLanguage.valueOf(p[Keys.LANGUAGE] ?: "ENGLISH") }.getOrDefault(AppLanguage.ENGLISH),
            onboardingCompleted = p[Keys.ONBOARDING_COMPLETED] ?: false,
            gatewayName = p[Keys.GATEWAY_NAME] ?: "JH GetWay Primary",
            gatewayMode = runCatching { GatewayMode.valueOf(p[Keys.GATEWAY_MODE] ?: "LOCAL") }.getOrDefault(GatewayMode.LOCAL),
            gatewayHost = p[Keys.GATEWAY_HOST] ?: "127.0.0.1",
            gatewayPort = p[Keys.GATEWAY_PORT] ?: 8787,
            gatewayRemoteUrl = p[Keys.GATEWAY_REMOTE_URL] ?: "",
            gatewayAuthRequired = p[Keys.GATEWAY_AUTH_REQUIRED] ?: true,
            gatewayTimeoutSec = p[Keys.GATEWAY_TIMEOUT_SEC] ?: 60,
            gatewayMaxRequestKb = p[Keys.GATEWAY_MAX_REQ_KB] ?: 2048,
            gatewayStreamingEnabled = p[Keys.GATEWAY_STREAMING] ?: true,
            gatewayRateLimitPerMin = p[Keys.GATEWAY_RATE_LIMIT] ?: 60,
            gatewayLoggingLevel = runCatching { LoggingLevel.valueOf(p[Keys.GATEWAY_LOGGING_LEVEL] ?: "METADATA_ONLY") }.getOrDefault(LoggingLevel.METADATA_ONLY),
            defaultModelId = p[Keys.DEFAULT_MODEL_ID] ?: "",
            defaultProviderId = p[Keys.DEFAULT_PROVIDER_ID] ?: "",
            defaultRoutingMode = runCatching { RoutingMode.valueOf(p[Keys.DEFAULT_ROUTING_MODE] ?: "AUTOMATIC") }.getOrDefault(RoutingMode.AUTOMATIC),
            fallbackEnabled = p[Keys.FALLBACK_ENABLED] ?: true,
            maxRetries = p[Keys.MAX_RETRIES] ?: 2,
            localRoutingPolicy = runCatching { LocalRoutingPolicy.valueOf(p[Keys.LOCAL_ROUTING_POLICY] ?: "LOCAL_FIRST") }.getOrDefault(LocalRoutingPolicy.LOCAL_FIRST),
            allowCloudFallbackForLocal = p[Keys.ALLOW_CLOUD_FALLBACK] ?: false,
            localNetworkAccessAllowed = p[Keys.LOCAL_NETWORK_ALLOWED] ?: false,
            saveChatHistoryAllowed = p[Keys.SAVE_CHAT_HISTORY] ?: true,
            appLockEnabled = p[Keys.APP_LOCK_ENABLED] ?: false,
            appLockPinHash = p[Keys.APP_LOCK_PIN_HASH] ?: "",
            analyticsCurrency = p[Keys.ANALYTICS_CURRENCY] ?: "USD",
            logRetentionDays = p[Keys.LOG_RETENTION_DAYS] ?: 30,
            notifyProviderFailure = p[Keys.NOTIFY_PROVIDER_FAILURE] ?: true,
            notifyGatewayDisconnected = p[Keys.NOTIFY_GATEWAY_DISC] ?: true,
            notifyRateLimit = p[Keys.NOTIFY_RATE_LIMIT] ?: true,
            notifyWorkflowCompleted = p[Keys.NOTIFY_WORKFLOW_DONE] ?: true,
            notifySecurityEvent = p[Keys.NOTIFY_SECURITY] ?: true
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setLanguage(lang: AppLanguage) = context.dataStore.edit { it[Keys.LANGUAGE] = lang.name }
    suspend fun setOnboardingCompleted(done: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = done }
    suspend fun setGatewayMode(mode: GatewayMode) = context.dataStore.edit { it[Keys.GATEWAY_MODE] = mode.name }

    suspend fun updateGatewaySettings(
        name: String,
        mode: GatewayMode,
        host: String,
        port: Int,
        remoteUrl: String,
        authRequired: Boolean,
        timeoutSec: Int,
        maxRequestKb: Int,
        streaming: Boolean,
        rateLimitPerMin: Int,
        loggingLevel: LoggingLevel
    ) = context.dataStore.edit {
        it[Keys.GATEWAY_NAME] = name
        it[Keys.GATEWAY_MODE] = mode.name
        it[Keys.GATEWAY_HOST] = host
        it[Keys.GATEWAY_PORT] = port
        it[Keys.GATEWAY_REMOTE_URL] = remoteUrl
        it[Keys.GATEWAY_AUTH_REQUIRED] = authRequired
        it[Keys.GATEWAY_TIMEOUT_SEC] = timeoutSec
        it[Keys.GATEWAY_MAX_REQ_KB] = maxRequestKb
        it[Keys.GATEWAY_STREAMING] = streaming
        it[Keys.GATEWAY_RATE_LIMIT] = rateLimitPerMin
        it[Keys.GATEWAY_LOGGING_LEVEL] = loggingLevel.name
    }

    suspend fun updateRoutingSettings(mode: RoutingMode, fallbackEnabled: Boolean, maxRetries: Int) =
        context.dataStore.edit {
            it[Keys.DEFAULT_ROUTING_MODE] = mode.name
            it[Keys.FALLBACK_ENABLED] = fallbackEnabled
            it[Keys.MAX_RETRIES] = maxRetries
        }

    suspend fun updateLocalAiSettings(
        policy: LocalRoutingPolicy,
        allowCloudFallback: Boolean,
        localNetworkAllowed: Boolean
    ) = context.dataStore.edit {
        it[Keys.LOCAL_ROUTING_POLICY] = policy.name
        it[Keys.ALLOW_CLOUD_FALLBACK] = allowCloudFallback
        it[Keys.LOCAL_NETWORK_ALLOWED] = localNetworkAllowed
    }

    suspend fun setDefaultModelAndProvider(modelId: String, providerId: String) = context.dataStore.edit {
        it[Keys.DEFAULT_MODEL_ID] = modelId
        it[Keys.DEFAULT_PROVIDER_ID] = providerId
    }

    suspend fun setSaveChatHistory(allowed: Boolean) = context.dataStore.edit { it[Keys.SAVE_CHAT_HISTORY] = allowed }
    suspend fun setAppLock(enabled: Boolean, pinHash: String) = context.dataStore.edit {
        it[Keys.APP_LOCK_ENABLED] = enabled
        it[Keys.APP_LOCK_PIN_HASH] = pinHash
    }
    suspend fun setCurrency(currency: String) = context.dataStore.edit { it[Keys.ANALYTICS_CURRENCY] = currency }
    suspend fun setLogRetentionDays(days: Int) = context.dataStore.edit { it[Keys.LOG_RETENTION_DAYS] = days }

    suspend fun updateNotificationPrefs(
        providerFailure: Boolean,
        gatewayDisc: Boolean,
        rateLimit: Boolean,
        workflowDone: Boolean,
        security: Boolean
    ) = context.dataStore.edit {
        it[Keys.NOTIFY_PROVIDER_FAILURE] = providerFailure
        it[Keys.NOTIFY_GATEWAY_DISC] = gatewayDisc
        it[Keys.NOTIFY_RATE_LIMIT] = rateLimit
        it[Keys.NOTIFY_WORKFLOW_DONE] = workflowDone
        it[Keys.NOTIFY_SECURITY] = security
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
