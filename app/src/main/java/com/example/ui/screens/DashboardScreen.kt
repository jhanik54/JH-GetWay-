package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.ProviderStatus
import com.example.gateway.GatewayRunStatus
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.navigation.Routes
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusRateLimit
import java.util.Calendar
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: GatewayViewModel,
    onNavigate: (String) -> Unit
) {
    val s = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val runtime by viewModel.gatewayRuntime.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val models by viewModel.models.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val logs by viewModel.activityLogs.collectAsState()

    val startOfDay = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val todayCount = metrics.count { it.timestamp >= startOfDay }
    val totalReq = metrics.size
    val totalIn = metrics.sumOf { it.inputTokens.toLong() }
    val totalOut = metrics.sumOf { it.outputTokens.toLong() }
    val knownCosts = metrics.mapNotNull { it.estimatedCostUsd }
    val totalCost: Double? = if (knownCosts.isEmpty()) null else knownCosts.sum()
    val avgLatency = if (metrics.isEmpty()) null else metrics.map { it.latencyMs }.average().toLong()
    val successRate = if (metrics.isEmpty()) null else (metrics.count { it.success } * 100.0 / metrics.size)
    val errorCount = metrics.count { !it.success }
    val fallbackCount = metrics.count { it.fallbackAttempts > 0 }

    val activeProviders = providers.count { it.enabled && it.status == ProviderStatus.CONNECTED }
    val failedProviders = providers.count { it.status == ProviderStatus.ERROR || it.status == ProviderStatus.DISCONNECTED }
    val rateLimitedProviders = providers.count { it.status == ProviderStatus.RATE_LIMITED }

    val (gwLabel, gwTone) = gatewayStatusLabelAndTone(runtime.status)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Gateway Status Card
        item {
            Surface(
                onClick = { onNavigate(Routes.GATEWAY) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().testTag("dashboard_gateway_card")
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(settings.gatewayName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = runtime.boundAddress,
                                style = MonoTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        StatusPill(text = gwLabel, tone = gwTone)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricMiniTile(
                            label = s.connectedDevices,
                            value = devices.count { !it.revoked && it.enabled }.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniTile(
                            label = s.activeRequests,
                            value = runtime.activeRequests.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = { viewModel.toggleGatewayServer() },
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("dashboard_gateway_toggle"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (runtime.status == GatewayRunStatus.RUNNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (runtime.status == GatewayRunStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (runtime.status == GatewayRunStatus.RUNNING) s.stopGateway else s.startGateway)
                    }
                }
            }
        }

        // Provider Overview Card
        item {
            GatewayCard(onClick = { onNavigate(Routes.PROVIDERS) }, modifier = Modifier.testTag("dashboard_provider_card")) {
                SectionHeader(title = s.providerOverview) {
                    Icon(Icons.Default.ChevronRight, contentDescription = s.providersTitle)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricMiniTile(s.totalProviders, providers.size.toString(), modifier = Modifier.weight(1f))
                    MetricMiniTile(s.activeProviders, activeProviders.toString(), accent = StatusConnected, modifier = Modifier.weight(1f))
                    MetricMiniTile(s.failedProviders, failedProviders.toString(), accent = StatusError, modifier = Modifier.weight(1f))
                    MetricMiniTile(s.rateLimitedProviders, rateLimitedProviders.toString(), accent = StatusRateLimit, modifier = Modifier.weight(1f))
                }
            }
        }

        // Model Overview Card
        item {
            GatewayCard(onClick = { onNavigate(Routes.MODELS) }, modifier = Modifier.testTag("dashboard_model_card")) {
                SectionHeader(title = s.modelOverview) {
                    Icon(Icons.Default.ChevronRight, contentDescription = s.modelsTitle)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricMiniTile(s.totalModels, models.size.toString(), modifier = Modifier.weight(1f))
                    MetricMiniTile(s.availableModels, models.count { it.enabled }.toString(), accent = StatusConnected, modifier = Modifier.weight(1f))
                    MetricMiniTile(s.localModels, models.count { it.isLocal }.toString(), modifier = Modifier.weight(1f))
                    MetricMiniTile(s.cloudModels, models.count { !it.isLocal }.toString(), modifier = Modifier.weight(1f))
                }
            }
        }

        // Usage & Performance Overview
        item {
            GatewayCard(onClick = { onNavigate(Routes.ANALYTICS) }, modifier = Modifier.testTag("dashboard_usage_card")) {
                SectionHeader(title = s.usageOverview) {
                    Icon(Icons.Default.ChevronRight, contentDescription = s.analyticsTitle)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricMiniTile(s.todayRequests, todayCount.toString(), modifier = Modifier.weight(1f))
                    MetricMiniTile(s.totalRequests, totalReq.toString(), modifier = Modifier.weight(1f))
                    MetricMiniTile(s.inputTokens, formatNumberCompact(totalIn), modifier = Modifier.weight(1f))
                    MetricMiniTile(s.outputTokens, formatNumberCompact(totalOut), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricMiniTile(s.estimatedCost, formatCurrency(totalCost, settings.analyticsCurrency), modifier = Modifier.weight(1f))
                    MetricMiniTile(s.avgLatency, avgLatency?.let { "${it}ms" } ?: "—", modifier = Modifier.weight(1f))
                    MetricMiniTile(s.successRate, successRate?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—", accent = StatusConnected, modifier = Modifier.weight(1f))
                    MetricMiniTile(s.fallbackCount, fallbackCount.toString(), modifier = Modifier.weight(1f))
                }
            }
        }

        // Infrastructure Quick Access Grid
        item {
            SectionHeader(title = s.quickAccess)
            val tiles = listOf(
                Triple(s.navSmartRouter, Icons.AutoMirrored.Filled.AltRoute, Routes.SMART_ROUTER),
                Triple(s.navCodingAgents, Icons.Default.Code, Routes.CODING_AGENTS),
                Triple(s.navLocalAi, Icons.Default.Computer, Routes.LOCAL_AI),
                Triple(s.navMcpServers, Icons.Default.Extension, Routes.MCP_SERVERS),
                Triple(s.navWorkflows, Icons.Default.AccountTree, Routes.WORKFLOWS),
                Triple(s.navVault, Icons.Default.VpnKey, Routes.VAULT),
                Triple(s.navDevices, Icons.Default.Devices, Routes.DEVICES),
                Triple(s.navActivityLogs, Icons.Default.History, Routes.ACTIVITY_LOGS)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { rowTiles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowTiles.forEach { (title, icon, route) ->
                            QuickModuleButton(
                                title = title,
                                icon = icon,
                                onClick = { onNavigate(route) },
                                modifier = Modifier.weight(1f).testTag("quick_$route")
                            )
                        }
                    }
                }
            }
        }

        // Recent Activity
        item {
            SectionHeader(title = s.recentActivity) {
                TextButton(onClick = { onNavigate(Routes.ACTIVITY_LOGS) }) {
                    Text(s.details)
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Default.History,
                    title = s.recentActivity,
                    message = s.noActivityYet,
                    actionLabel = s.addProvider,
                    onAction = { onNavigate(Routes.PROVIDERS) }
                )
            }
        } else {
            items(logs.take(5), key = { it.id }) { log ->
                GatewayCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(log.relatedEntity, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusPill(
                            text = log.status,
                            tone = if (log.status.contains("ERROR") || log.status.contains("FAIL")) StatusTone.ERROR else StatusTone.INFO
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(log.safeMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTimestamp(log.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QuickModuleButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
