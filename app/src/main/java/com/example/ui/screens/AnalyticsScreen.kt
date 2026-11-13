package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.RequestMetricEntity
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusError
import java.util.Calendar

enum class AnalyticsRange { TODAY, DAYS_7, DAYS_30, ALL }

@Composable
fun AnalyticsScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val metrics by viewModel.metrics.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var range by remember { mutableStateOf(AnalyticsRange.DAYS_7) }
    var statusFilter by remember { mutableStateOf("ALL") } // ALL, SUCCESS, FAILED
    var providerFilter by remember { mutableStateOf<String?>(null) }

    val filtered = remember(metrics, range, statusFilter, providerFilter) {
        val now = System.currentTimeMillis()
        val cutoff = when (range) {
            AnalyticsRange.TODAY -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            AnalyticsRange.DAYS_7 -> now - 7 * 86_400_000L
            AnalyticsRange.DAYS_30 -> now - 30 * 86_400_000L
            AnalyticsRange.ALL -> 0L
        }
        metrics.filter { m ->
            m.timestamp >= cutoff &&
                (statusFilter == "ALL" || (statusFilter == "SUCCESS" && m.success) || (statusFilter == "FAILED" && !m.success)) &&
                (providerFilter == null || m.providerName == providerFilter)
        }
    }

    val total = filtered.size
    val successCount = filtered.count { it.success }
    val failCount = total - successCount
    val inTokens = filtered.sumOf { it.inputTokens.toLong() }
    val outTokens = filtered.sumOf { it.outputTokens.toLong() }
    val knownCosts = filtered.mapNotNull { it.estimatedCostUsd }
    val estCost: Double? = if (knownCosts.isEmpty()) null else knownCosts.sum()
    val avgLatency = if (filtered.isEmpty()) 0L else filtered.map { it.latencyMs }.average().toLong()
    val p95Latency = if (filtered.isEmpty()) 0L else {
        val sorted = filtered.map { it.latencyMs }.sorted()
        sorted[((sorted.size - 1) * 0.95).toInt()]
    }
    val fallbacks = filtered.sumOf { it.fallbackAttempts }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.analyticsTitle, style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("USD", "BDT", "EUR").forEach { cur ->
                        FilterChip(
                            selected = settings.analyticsCurrency == cur,
                            onClick = { viewModel.setCurrency(cur) },
                            label = { Text(cur) }
                        )
                    }
                }
            }
        }

        // Timeframe & Status Filters
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = range == AnalyticsRange.TODAY, onClick = { range = AnalyticsRange.TODAY }, label = { Text(s.today) }) }
                item { FilterChip(selected = range == AnalyticsRange.DAYS_7, onClick = { range = AnalyticsRange.DAYS_7 }, label = { Text(s.last7Days) }) }
                item { FilterChip(selected = range == AnalyticsRange.DAYS_30, onClick = { range = AnalyticsRange.DAYS_30 }, label = { Text(s.last30Days) }) }
                item { FilterChip(selected = range == AnalyticsRange.ALL, onClick = { range = AnalyticsRange.ALL }, label = { Text(s.allTime) }) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = statusFilter == "ALL", onClick = { statusFilter = "ALL" }, label = { Text("All Status") }) }
                item { FilterChip(selected = statusFilter == "SUCCESS", onClick = { statusFilter = "SUCCESS" }, label = { Text(s.success) }) }
                item { FilterChip(selected = statusFilter == "FAILED", onClick = { statusFilter = "FAILED" }, label = { Text(s.error) }) }
                val distinctProviders = metrics.map { it.providerName }.distinct()
                items(distinctProviders) { pn ->
                    FilterChip(
                        selected = providerFilter == pn,
                        onClick = { providerFilter = if (providerFilter == pn) null else pn },
                        label = { Text(pn) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Default.Analytics,
                    title = s.analyticsTitle,
                    message = s.noAnalyticsData
                )
            }
        } else {
            item {
                GatewayCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricMiniTile(s.totalRequests, total.toString(), modifier = Modifier.weight(1f))
                        MetricMiniTile(s.success, successCount.toString(), accent = StatusConnected, modifier = Modifier.weight(1f))
                        MetricMiniTile(s.error, failCount.toString(), accent = StatusError, modifier = Modifier.weight(1f))
                        MetricMiniTile(s.fallbackCount, fallbacks.toString(), modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricMiniTile(s.inputTokens, formatNumberCompact(inTokens), modifier = Modifier.weight(1f))
                        MetricMiniTile(s.outputTokens, formatNumberCompact(outTokens), modifier = Modifier.weight(1f))
                        MetricMiniTile(s.avgLatency, "${avgLatency}ms", modifier = Modifier.weight(1f))
                        MetricMiniTile(s.p95Latency, "${p95Latency}ms", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${s.estimatedCost}: ${formatCurrency(estCost, settings.analyticsCurrency)} (Reference pricing 2026-03-01)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Requests / Latency Bar Chart using Compose Canvas
            item {
                GatewayCard {
                    Text(s.requestsOverTime, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    val barColor = MaterialTheme.colorScheme.primary
                    val errBarColor = MaterialTheme.colorScheme.error
                    val recentPoints = filtered.take(16).reversed()
                    Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
                        val maxLat = (recentPoints.maxOfOrNull { it.latencyMs } ?: 1L).coerceAtLeast(1L).toFloat()
                        val count = recentPoints.size.coerceAtLeast(1)
                        val gap = 6.dp.toPx()
                        val barW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(6f)
                        recentPoints.forEachIndexed { idx, m ->
                            val ratio = (m.latencyMs.toFloat() / maxLat).coerceIn(0.08f, 1f)
                            val h = size.height * ratio
                            val x = idx * (barW + gap)
                            drawRoundRect(
                                color = if (m.success) barColor else errBarColor,
                                topLeft = Offset(x, size.height - h),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                        }
                    }
                    Text("Recent ${recentPoints.size} requests (height = latency ms)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Provider Usage Distribution
            item {
                GatewayCard {
                    Text(s.providerUsage, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val byProvider = filtered.groupBy { it.providerName }
                    byProvider.forEach { (pName, list) ->
                        val ratio = list.size.toFloat() / total.toFloat()
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(pName, style = MaterialTheme.typography.bodyMedium)
                                Text("${list.size} (${(ratio * 100).toInt()}%)", style = MonoTextStyle)
                            }
                            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }

            // Request Inspector
            item { SectionHeader(title = s.requestDetails) }
            items(filtered.take(40), key = { it.id }) { m ->
                RequestInspectorCard(m, settings.analyticsCurrency)
            }
        }
    }
}

@Composable
private fun RequestInspectorCard(m: RequestMetricEntity, currency: String) {
    GatewayCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${m.providerName} • ${m.modelId}", style = MaterialTheme.typography.titleSmall)
                Text("ID: ${m.requestId} • ${m.deviceName} • ${m.endpoint}", style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(
                text = "${m.statusCode}",
                tone = if (m.success) StatusTone.SUCCESS else StatusTone.ERROR
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${m.latencyMs}ms • ${m.inputTokens} in / ${m.outputTokens} out • Fallbacks: ${m.fallbackAttempts} • Cost: ${formatCurrency(m.estimatedCostUsd, currency)}",
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(formatTimestamp(m.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        m.safeErrorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
