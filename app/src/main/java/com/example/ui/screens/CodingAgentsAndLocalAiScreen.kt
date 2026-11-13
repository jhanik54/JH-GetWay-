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
import com.example.network.ProviderPresets
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.theme.MonoTextStyle

@Composable
fun CodingAgentsScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val agents by viewModel.codingAgents.collectAsState()
    val runtime by viewModel.gatewayRuntime.collectAsState()
    val clipboard = LocalClipboardManager.current

    var showDialog by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<CodingAgentEntity?>(null) }
    var deletingAgent by remember { mutableStateOf<CodingAgentEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingAgent = null; showDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = s.addAgent) },
                text = { Text(s.addAgent) },
                modifier = Modifier.testTag("add_agent_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(s.codingAgentsTitle, style = MaterialTheme.typography.headlineSmall) }

            if (agents.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.Code,
                        title = s.codingAgentsTitle,
                        message = s.noAgentsMessage,
                        actionLabel = s.addAgent,
                        onAction = { editingAgent = null; showDialog = true }
                    )
                }
            } else {
                items(agents, key = { it.id }) { agent ->
                    val baseV1 = "${runtime.boundAddress.trimEnd('/')}/v1"
                    val configSnippet = when (agent.agentType) {
                        CodingAgentType.CODEX -> "OPENAI_BASE_URL=$baseV1\nOPENAI_API_KEY=${viewModel.decryptAgentToken(agent)}\nmodel = \"${agent.modelAlias}\""
                        else -> "Base URL: $baseV1\nAPI Key: ${viewModel.decryptAgentToken(agent)}\nModel: ${agent.modelAlias}"
                    }
                    GatewayCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(agent.name, style = MaterialTheme.typography.titleMedium)
                                Text("${agent.agentType.name} • ${agent.modelAlias}", style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                            }
                            StatusPill(
                                text = if (agent.enabled) s.enabled else s.disabled,
                                tone = if (agent.enabled) StatusTone.SUCCESS else StatusTone.NEUTRAL
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Token: ${agent.tokenPreview} • Requests: ${agent.requestCount}", style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        agent.dailyTokenLimit?.let {
                            Text("Daily Limit: ${agent.tokensUsedToday} / $it tokens", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Base URL: $baseV1\nAPI Key: ${agent.tokenPreview}\nModel: ${agent.modelAlias}",
                                style = MonoTextStyle,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { viewModel.testCodingAgentGateway(agent) }) { Text(s.testConnection) }
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(configSnippet))
                                viewModel.postBanner(s.copied)
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.copyConfigSnippet)
                            }
                            IconButton(onClick = { viewModel.toggleAgentEnabled(agent) }) {
                                Icon(if (agent.enabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff, contentDescription = null)
                            }
                            IconButton(onClick = { editingAgent = agent; showDialog = true }) {
                                Icon(Icons.Default.Edit, contentDescription = s.edit)
                            }
                            IconButton(onClick = { deletingAgent = agent }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        var name by remember { mutableStateOf(editingAgent?.name ?: "OpenCode Local") }
        var type by remember { mutableStateOf(editingAgent?.agentType ?: CodingAgentType.OPENCODE) }
        var alias by remember { mutableStateOf(editingAgent?.modelAlias ?: "jh-coder") }
        var streaming by remember { mutableStateOf(editingAgent?.streaming ?: true) }
        var toolCalling by remember { mutableStateOf(editingAgent?.toolCalling ?: true) }
        var limitText by remember { mutableStateOf(editingAgent?.dailyTokenLimit?.toString().orEmpty()) }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (editingAgent == null) s.addAgent else s.edit) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(CodingAgentType.entries) { t ->
                            FilterChip(selected = type == t, onClick = { type = t; if (editingAgent == null) name = t.name }, label = { Text(t.name) })
                        }
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.agentName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("Model / Alias") }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = limitText, onValueChange = { limitText = it.filter { c -> c.isDigit() } }, label = { Text("${s.dailyTokenLimit} (${s.optional})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = streaming, onCheckedChange = { streaming = it }); Text(s.streamingLabel)
                        Spacer(Modifier.width(12.dp))
                        Checkbox(checked = toolCalling, onCheckedChange = { toolCalling = it }); Text(s.toolCalling)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.saveCodingAgent(editingAgent?.id, name, type, alias, streaming, toolCalling, limitText.toLongOrNull())
                        showDialog = false
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(s.cancel) } }
        )
    }

    deletingAgent?.let { ag ->
        ConfirmDeleteDialog(
            title = "${s.delete} ${ag.name}?",
            message = s.deleteConfirmMessage,
            onConfirm = { viewModel.deleteAgent(ag); deletingAgent = null },
            onDismiss = { deletingAgent = null }
        )
    }
}

@Composable
fun LocalAiScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val servers by viewModel.localServers.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showDialog by remember { mutableStateOf(false) }
    var deletingServer by remember { mutableStateOf<LocalAiServerEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = s.addLocalServer) },
                text = { Text(s.addLocalServer) },
                modifier = Modifier.testTag("add_local_server_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(s.localAiTitle, style = MaterialTheme.typography.headlineSmall)
            }

            // Local Network Safety & Routing Policy
            item {
                GatewayCard {
                    Text(s.localRoutingPolicy, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(LocalRoutingPolicy.entries) { p ->
                            FilterChip(
                                selected = settings.localRoutingPolicy == p,
                                onClick = { viewModel.updateLocalAiSettings(p, settings.allowCloudFallbackForLocal, settings.localNetworkAccessAllowed) },
                                label = { Text(p.name.replace("_", " ")) }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Switch(
                            checked = settings.localNetworkAccessAllowed,
                            onCheckedChange = { viewModel.updateLocalAiSettings(settings.localRoutingPolicy, settings.allowCloudFallbackForLocal, it) },
                            modifier = Modifier.testTag("local_network_access_switch")
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(s.localNetworkConsentLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Switch(
                            checked = settings.allowCloudFallbackForLocal,
                            onCheckedChange = { viewModel.updateLocalAiSettings(settings.localRoutingPolicy, it, settings.localNetworkAccessAllowed) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(s.allowCloudFallback, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (servers.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.Computer,
                        title = s.localAiTitle,
                        message = s.noLocalServersMessage,
                        actionLabel = s.addLocalServer,
                        onAction = { showDialog = true }
                    )
                }
            } else {
                items(servers, key = { it.id }) { srv ->
                    val (statusTxt, tone) = providerStatusLabelAndTone(srv.status)
                    GatewayCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(srv.name, style = MaterialTheme.typography.titleMedium)
                                Text("${srv.engineType.name} • ${if (srv.isPrivateNetwork) s.privateNetworkBadge else s.publicNetworkBadge}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill(text = statusTxt, tone = tone)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(srv.serverUrl, style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                        srv.lastError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { viewModel.testLocalServer(srv) }) { Text(s.testConnection) }
                            OutlinedButton(onClick = { viewModel.discoverLocalModels(srv) }) { Text(s.discoverModels) }
                            IconButton(onClick = { viewModel.toggleLocalServer(srv) }) {
                                Icon(if (srv.enabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff, contentDescription = null)
                            }
                            IconButton(onClick = { deletingServer = srv }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        var engine by remember { mutableStateOf(LocalEngineType.OLLAMA) }
        var name by remember { mutableStateOf("Local Ollama") }
        var url by remember { mutableStateOf(ProviderPresets.defaultUrlForLocalEngine(LocalEngineType.OLLAMA)) }
        var authToken by remember { mutableStateOf("") }
        var timeoutText by remember { mutableStateOf("60") }
        var defModel by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(s.addLocalServer) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(LocalEngineType.entries) { e ->
                            FilterChip(
                                selected = engine == e,
                                onClick = {
                                    engine = e
                                    name = e.name
                                    url = ProviderPresets.defaultUrlForLocalEngine(e)
                                },
                                label = { Text(e.name) }
                            )
                        }
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.serverName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(s.serverUrl) }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = authToken, onValueChange = { authToken = it }, label = { Text("Auth Token (${s.optional})") }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = defModel, onValueChange = { defModel = it }, label = { Text("${s.defaultModel} (${s.optional})") }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = timeoutText, onValueChange = { timeoutText = it.filter { c -> c.isDigit() } }, label = { Text(s.requestTimeoutSec) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        viewModel.saveLocalServer(name, engine, url, authToken, timeoutText.toIntOrNull() ?: 60, defModel)
                        showDialog = false
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(s.cancel) } }
        )
    }

    deletingServer?.let { srv ->
        ConfirmDeleteDialog(
            title = "${s.delete} ${srv.name}?",
            message = s.deleteConfirmMessage,
            onConfirm = { viewModel.deleteLocalServer(srv); deletingServer = null },
            onDismiss = { deletingServer = null }
        )
    }
}
