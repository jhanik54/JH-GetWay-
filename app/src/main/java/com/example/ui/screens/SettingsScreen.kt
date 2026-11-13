package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocalStrings
import com.example.ui.GatewayViewModel
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.GatewayCard
import com.example.ui.navigation.Routes
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: GatewayViewModel,
    onNavigate: (String) -> Unit
) {
    val s = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var showPinDialog by remember { mutableStateOf(false) }
    var showBackupExportDialog by remember { mutableStateOf(false) }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var infoModalTitle by remember { mutableStateOf<String?>(null) }
    var infoModalBody by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text(s.settingsTitle, style = MaterialTheme.typography.headlineSmall) }

        // General: Language & Theme
        item {
            GatewayCard {
                Text(s.generalSection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(s.languageLabel, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    listOf(
                        AppLanguage.ENGLISH to s.langEnglish,
                        AppLanguage.BENGALI to s.langBengali,
                        AppLanguage.SYSTEM to s.langSystem
                    ).forEach { (lang, label) ->
                        FilterChip(
                            selected = settings.language == lang,
                            onClick = { viewModel.setLanguage(lang) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("settings_lang_${lang.name}")
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(s.themeLabel, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    listOf(
                        ThemeMode.LIGHT to s.themeLight,
                        ThemeMode.DARK to s.themeDark,
                        ThemeMode.SYSTEM to s.themeSystem
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("settings_theme_${mode.name}")
                        )
                    }
                }
            }
        }

        // Gateway & Routing Quick Navigation
        item {
            GatewayCard {
                Text("${s.gatewaySection} & ${s.routingSection}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onNavigate(Routes.GATEWAY) }, modifier = Modifier.weight(1f)) { Text(s.navGateway) }
                    OutlinedButton(onClick = { onNavigate(Routes.SMART_ROUTER) }, modifier = Modifier.weight(1f)) { Text(s.navSmartRouter) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onNavigate(Routes.VAULT) }, modifier = Modifier.weight(1f)) { Text(s.navVault) }
                    OutlinedButton(onClick = { onNavigate(Routes.DEVICES) }, modifier = Modifier.weight(1f)) { Text(s.navDevices) }
                }
            }
        }

        // Security & Privacy
        item {
            GatewayCard {
                Text(s.securitySection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.saveChatHistoryLabel, modifier = Modifier.weight(1f))
                    Switch(checked = settings.saveChatHistoryAllowed, onCheckedChange = { viewModel.setSaveChatHistory(it) })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.appLockLabel, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.appLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) showPinDialog = true else viewModel.setAppLockPin(false, "")
                        }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("Log Retention: ${settings.logRetentionDays} days", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = settings.logRetentionDays.toFloat(),
                    onValueChange = { viewModel.setLogRetentionDays(it.toInt()) },
                    valueRange = 7f..90f
                )
            }
        }

        // Notifications
        item {
            GatewayCard {
                Text(s.notificationsSection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                NotificationRow("Provider Failure Alerts", settings.notifyProviderFailure) {
                    viewModel.updateNotificationPrefs(it, settings.notifyGatewayDisconnected, settings.notifyRateLimit, settings.notifyWorkflowCompleted, settings.notifySecurityEvent)
                }
                NotificationRow("Gateway Disconnected", settings.notifyGatewayDisconnected) {
                    viewModel.updateNotificationPrefs(settings.notifyProviderFailure, it, settings.notifyRateLimit, settings.notifyWorkflowCompleted, settings.notifySecurityEvent)
                }
                NotificationRow("Rate Limit Warnings", settings.notifyRateLimit) {
                    viewModel.updateNotificationPrefs(settings.notifyProviderFailure, settings.notifyGatewayDisconnected, it, settings.notifyWorkflowCompleted, settings.notifySecurityEvent)
                }
                NotificationRow("Workflow Completed", settings.notifyWorkflowCompleted) {
                    viewModel.updateNotificationPrefs(settings.notifyProviderFailure, settings.notifyGatewayDisconnected, settings.notifyRateLimit, it, settings.notifySecurityEvent)
                }
                NotificationRow("Security & Auth Events", settings.notifySecurityEvent) {
                    viewModel.updateNotificationPrefs(settings.notifyProviderFailure, settings.notifyGatewayDisconnected, settings.notifyRateLimit, settings.notifyWorkflowCompleted, it)
                }
            }
        }

        // Data & Encrypted Backup
        item {
            GatewayCard {
                Text(s.dataBackupSection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showBackupExportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.exportEncryptedBackup)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showBackupRestoreDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.importEncryptedBackup)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.clearLocalData)
                }
            }
        }

        // About JH Soft Corporation
        item {
            GatewayCard {
                Text(s.aboutSection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("${s.appName} — ${s.orgName}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(s.versionLabel, style = MaterialTheme.typography.bodySmall)
                Text(s.copyrightLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        infoModalTitle = s.privacyPolicyTitle
                        infoModalBody = "JH GetWay stores all API keys and tokens locally using Android Keystore AES-256-GCM encryption. Request prompts and responses are never logged unless explicitly enabled by the user."
                    }) { Text(s.privacyPolicyTitle) }
                    TextButton(onClick = {
                        infoModalTitle = s.termsTitle
                        infoModalBody = "JH GetWay is an AI infrastructure gateway management tool by JH Soft Corporation. Users are responsible for complying with each connected AI provider's Terms of Service."
                    }) { Text(s.termsTitle) }
                    TextButton(onClick = {
                        infoModalTitle = s.openSourceLicensesTitle
                        infoModalBody = "Built with Jetpack Compose, AndroidX Room, DataStore, OkHttp, Moshi, and Material Icons (Apache 2.0)."
                    }) { Text("Licenses") }
                }
            }
        }
    }

    if (showPinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(s.appLockLabel) },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("4-8 digit PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (pin.length >= 4) {
                        viewModel.setAppLockPin(true, pin)
                        showPinDialog = false
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text(s.cancel) } }
        )
    }

    if (showBackupExportDialog) {
        var passphrase by remember { mutableStateOf("") }
        var exportedText by remember { mutableStateOf<String?>(null) }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showBackupExportDialog = false },
            title = { Text(s.exportEncryptedBackup) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text(s.passphraseLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    exportedText?.let { payload ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Text(payload.take(180) + "...", style = MonoTextStyle, modifier = Modifier.padding(8.dp))
                        }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(payload)); viewModel.postBanner(s.copied) }) {
                            Text(s.copy)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        viewModel.exportEncryptedBackup(passphrase).fold(
                            onSuccess = { exportedText = it; err = null },
                            onFailure = { err = it.message }
                        )
                    }
                }) { Text(s.exportLabel) }
            },
            dismissButton = { TextButton(onClick = { showBackupExportDialog = false }) { Text(s.close) } }
        )
    }

    if (showBackupRestoreDialog) {
        var payload by remember { mutableStateOf("") }
        var passphrase by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showBackupRestoreDialog = false },
            title = { Text(s.importEncryptedBackup) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = payload, onValueChange = { payload = it }, label = { Text("JHBACKUP_V1 Payload") }, textStyle = MonoTextStyle, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text(s.passphraseLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        viewModel.restoreEncryptedBackup(payload, passphrase).fold(
                            onSuccess = { count ->
                                viewModel.postBanner("Restored $count items")
                                showBackupRestoreDialog = false
                            },
                            onFailure = { err = it.message ?: "Restore failed" }
                        )
                    }
                }) { Text(s.importLabel) }
            },
            dismissButton = { TextButton(onClick = { showBackupRestoreDialog = false }) { Text(s.cancel) } }
        )
    }

    if (showClearConfirm) {
        ConfirmDeleteDialog(
            title = s.clearLocalData,
            message = s.clearLocalDataWarning,
            onConfirm = { viewModel.clearAllLocalData(); showClearConfirm = false },
            onDismiss = { showClearConfirm = false }
        )
    }

    infoModalTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { infoModalTitle = null },
            title = { Text(title) },
            text = { Text(infoModalBody) },
            confirmButton = { TextButton(onClick = { infoModalTitle = null }) { Text(s.close) } }
        )
    }
}

@Composable
private fun NotificationRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
