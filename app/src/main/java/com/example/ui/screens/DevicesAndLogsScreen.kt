@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.*
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.theme.MonoTextStyle

@Composable
fun DevicesScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val devices by viewModel.devices.collectAsState()
    val clipboard = LocalClipboardManager.current

    var showAdd by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DeviceEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = s.addDevice) },
                text = { Text(s.addDevice) },
                modifier = Modifier.testTag("add_device_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(s.devicesTitle, style = MaterialTheme.typography.headlineSmall) }

            if (devices.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.Devices,
                        title = s.devicesTitle,
                        message = s.noDevicesMessage,
                        actionLabel = s.addDevice,
                        onAction = { showAdd = true }
                    )
                }
            } else {
                items(devices, key = { it.id }) { dev ->
                    GatewayCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.name, style = MaterialTheme.typography.titleMedium)
                                Text("${dev.deviceType} • ${dev.deviceCode} • ${dev.connectionType.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill(
                                text = when {
                                    dev.revoked -> s.revokedBadge
                                    dev.enabled -> s.enabled
                                    else -> s.disabled
                                },
                                tone = when {
                                    dev.revoked -> StatusTone.ERROR
                                    dev.enabled -> StatusTone.SUCCESS
                                    else -> StatusTone.NEUTRAL
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Token: ${dev.tokenPreview} • Requests: ${dev.requestCount}", style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                        Text("Last Active: ${formatTimestamp(dev.lastActiveAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!dev.revoked) {
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(viewModel.decryptDeviceToken(dev)))
                                    viewModel.postBanner(s.copied)
                                }) { Text("${s.copy} Token") }
                            }
                            OutlinedButton(onClick = { viewModel.regenerateDeviceToken(dev) }) { Text(s.regenerateToken) }
                            TextButton(onClick = { renameTarget = dev }) { Text("Rename") }
                            if (!dev.revoked) {
                                TextButton(
                                    onClick = { viewModel.revokeDevice(dev) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { Text(s.revokeAccess) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("JH One AI") }
        var connType by remember { mutableStateOf(DeviceConnectionType.LOCAL_NETWORK) }
        val presets = listOf("JH GetWay Android", "JH One AI", "JUNIOR AI", "OpenCode", "Codex", "Development Computer")

        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(s.addDevice) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(presets) { p ->
                            FilterChip(selected = type == p, onClick = { type = p; if (name.isBlank()) name = p }, label = { Text(p) })
                        }
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.deviceName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text(s.deviceType) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(DeviceConnectionType.entries) { ct ->
                            FilterChip(selected = connType == ct, onClick = { connType = ct }, label = { Text(ct.name) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.addDevice(name, type, connType)
                        showAdd = false
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(s.cancel) } }
        )
    }

    renameTarget?.let { d ->
        var newName by remember { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Device") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(onClick = { if (newName.isNotBlank()) { viewModel.renameDevice(d, newName); renameTarget = null } }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(s.cancel) } }
        )
    }
}

@Composable
fun ActivityLogsScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val logs by viewModel.activityLogs.collectAsState()
    val clipboard = LocalClipboardManager.current

    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ActivityCategory?>(null) }

    val filtered = remember(logs, query, selectedCategory) {
        logs.filter { log ->
            (selectedCategory == null || log.category == selectedCategory) &&
                (query.isBlank() || log.relatedEntity.contains(query, true) || log.safeMessage.contains(query, true) || log.status.contains(query, true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.activityTitle, style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = {
                        val txt = filtered.joinToString("\n") { "[${formatTimestamp(it.timestamp)}] [${it.category}] ${it.relatedEntity} (${it.status}): ${it.safeMessage}" }
                        clipboard.setText(AnnotatedString(txt))
                        viewModel.postBanner(s.copied)
                    }) { Text(s.exportLogs) }
                    TextButton(onClick = { viewModel.clearLogs() }) { Text(s.clearLogs) }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(s.searchLogsHint) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = selectedCategory == null, onClick = { selectedCategory = null }, label = { Text("All") }) }
                items(ActivityCategory.entries) { cat ->
                    FilterChip(selected = selectedCategory == cat, onClick = { selectedCategory = cat }, label = { Text(cat.name) })
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Default.History,
                    title = s.activityTitle,
                    message = s.noActivityYet
                )
            }
        } else {
            items(filtered, key = { it.id }) { log ->
                GatewayCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${log.eventId} • ${log.category.name}", style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                        StatusPill(
                            text = log.status,
                            tone = if (log.status.contains("ERROR") || log.status.contains("FAIL") || log.status.contains("UNAUTHORIZED") || log.status.contains("REVOKED")) StatusTone.ERROR else StatusTone.INFO
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(log.relatedEntity, style = MaterialTheme.typography.titleSmall)
                    Text(log.safeMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTimestamp(log.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
