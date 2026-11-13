package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.BengaliStrings
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.gateway.GatewayRunStatus
import com.example.ui.GatewayViewModel
import com.example.ui.components.StatusPill
import com.example.ui.components.StatusTone
import com.example.ui.navigation.Routes
import com.example.ui.screens.*
import com.example.ui.theme.JhGetWayTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: GatewayViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            val isUnlocked by vm.isAppUnlocked.collectAsState()

            val activeStrings = remember(settings.language) {
                when (settings.language) {
                    AppLanguage.ENGLISH -> EnglishStrings
                    AppLanguage.BENGALI -> BengaliStrings
                    AppLanguage.SYSTEM -> if (Locale.getDefault().language.equals("bn", true)) BengaliStrings else EnglishStrings
                }
            }

            CompositionLocalProvider(LocalStrings provides activeStrings) {
                JhGetWayTheme(themeMode = settings.themeMode) {
                    when {
                        !isUnlocked -> AppLockGateScreen(onUnlock = { vm.verifyUnlockPin(it) })
                        !settings.onboardingCompleted -> OnboardingScreen(
                            viewModel = vm,
                            onCompleted = { vm.completeOnboarding() }
                        )
                        else -> JhGetWayMainShell(viewModel = vm)
                    }
                }
            }
        }
    }
}

private data class NavDestinationItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JhGetWayMainShell(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Routes.DASHBOARD
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val banner by viewModel.uiBannerMessage.collectAsState()
    val runtime by viewModel.gatewayRuntime.collectAsState()
    val pendingApproval by viewModel.pendingMcpApproval.collectAsState()

    LaunchedEffect(banner) {
        banner?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBanner()
        }
    }

    val bottomNavItems = listOf(
        NavDestinationItem(Routes.DASHBOARD, s.navHome, Icons.Filled.Home, Icons.Outlined.Home),
        NavDestinationItem(Routes.PROVIDERS, s.navProviders, Icons.Filled.CloudQueue, Icons.Outlined.CloudQueue),
        NavDestinationItem(Routes.PLAYGROUND, s.navPlayground, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
        NavDestinationItem(Routes.ANALYTICS, s.navAnalytics, Icons.Filled.Analytics, Icons.Outlined.Analytics),
        NavDestinationItem(Routes.SETTINGS, s.navSettings, Icons.Filled.Settings, Icons.Outlined.Settings)
    )

    val allDrawerItems = listOf(
        NavDestinationItem(Routes.DASHBOARD, s.navHome, Icons.Filled.Home, Icons.Outlined.Home),
        NavDestinationItem(Routes.PROVIDERS, s.navProviders, Icons.Filled.CloudQueue, Icons.Outlined.CloudQueue),
        NavDestinationItem(Routes.MODELS, s.navModels, Icons.Filled.Memory, Icons.Outlined.Memory),
        NavDestinationItem(Routes.GATEWAY, s.navGateway, Icons.Filled.Hub, Icons.Outlined.Hub),
        NavDestinationItem(Routes.SMART_ROUTER, s.navSmartRouter, Icons.AutoMirrored.Filled.AltRoute, Icons.AutoMirrored.Outlined.AltRoute),
        NavDestinationItem(Routes.CODING_AGENTS, s.navCodingAgents, Icons.Filled.Code, Icons.Outlined.Code),
        NavDestinationItem(Routes.LOCAL_AI, s.navLocalAi, Icons.Filled.Computer, Icons.Outlined.Computer),
        NavDestinationItem(Routes.MCP_SERVERS, s.navMcpServers, Icons.Filled.Extension, Icons.Outlined.Extension),
        NavDestinationItem(Routes.WORKFLOWS, s.navWorkflows, Icons.Filled.AccountTree, Icons.Outlined.AccountTree),
        NavDestinationItem(Routes.PLAYGROUND, s.navPlayground, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
        NavDestinationItem(Routes.ANALYTICS, s.navAnalytics, Icons.Filled.Analytics, Icons.Outlined.Analytics),
        NavDestinationItem(Routes.DEVICES, s.navDevices, Icons.Filled.Devices, Icons.Outlined.Devices),
        NavDestinationItem(Routes.VAULT, s.navVault, Icons.Filled.VpnKey, Icons.Outlined.VpnKey),
        NavDestinationItem(Routes.ACTIVITY_LOGS, s.navActivityLogs, Icons.Filled.History, Icons.Outlined.History),
        NavDestinationItem(Routes.SETTINGS, s.navSettings, Icons.Filled.Settings, Icons.Outlined.Settings)
    )

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 300.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(s.appName, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    Text(s.orgName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    allDrawerItems.forEach { item ->
                        NavigationDrawerItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateTo(item.route)
                            },
                            icon = { Icon(if (currentRoute == item.route) item.selectedIcon else item.unselectedIcon, contentDescription = null) },
                            label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("drawer_${item.route}")
                        )
                    }
                }
            }
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpanded = maxWidth >= 840.dp

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = allDrawerItems.firstOrNull { it.route == currentRoute }?.label ?: s.appName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("open_drawer_btn")
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = s.navMore)
                            }
                        },
                        actions = {
                            StatusPill(
                                text = if (runtime.status == GatewayRunStatus.RUNNING) s.running else s.stopped,
                                tone = if (runtime.status == GatewayRunStatus.RUNNING) StatusTone.SUCCESS else StatusTone.NEUTRAL,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    )
                },
                bottomBar = {
                    if (!isExpanded) {
                        NavigationBar {
                            bottomNavItems.forEach { item ->
                                val selected = currentRoute == item.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTo(item.route) },
                                    icon = { Icon(if (selected) item.selectedIcon else item.unselectedIcon, contentDescription = item.label) },
                                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.testTag("bottom_nav_${item.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isExpanded) {
                        NavigationRail {
                            bottomNavItems.forEach { item ->
                                val selected = currentRoute == item.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTo(item.route) },
                                    icon = { Icon(if (selected) item.selectedIcon else item.unselectedIcon, contentDescription = item.label) },
                                    label = { Text(item.label, maxLines = 1) }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        NavHost(
                            navController = navController,
                            startDestination = Routes.DASHBOARD
                        ) {
                            composable(Routes.DASHBOARD) { DashboardScreen(viewModel, ::navigateTo) }
                            composable(Routes.PROVIDERS) { ProvidersScreen(viewModel, ::navigateTo) }
                            composable(Routes.MODELS) { ModelsScreen(viewModel, ::navigateTo) }
                            composable(Routes.GATEWAY) { GatewayScreen(viewModel) }
                            composable(Routes.SMART_ROUTER) { SmartRouterScreen(viewModel) }
                            composable(Routes.CODING_AGENTS) { CodingAgentsScreen(viewModel) }
                            composable(Routes.LOCAL_AI) { LocalAiScreen(viewModel) }
                            composable(Routes.MCP_SERVERS) { McpServersScreen(viewModel) }
                            composable(Routes.WORKFLOWS) { WorkflowsScreen(viewModel) }
                            composable(Routes.PLAYGROUND) { PlaygroundScreen(viewModel, ::navigateTo) }
                            composable(Routes.ANALYTICS) { AnalyticsScreen(viewModel) }
                            composable(Routes.DEVICES) { DevicesScreen(viewModel) }
                            composable(Routes.VAULT) { VaultScreen(viewModel) }
                            composable(Routes.ACTIVITY_LOGS) { ActivityLogsScreen(viewModel) }
                            composable(Routes.SETTINGS) { SettingsScreen(viewModel, ::navigateTo) }
                        }
                    }
                }
            }
        }
    }

    pendingApproval?.let { req ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveMcpApproval(false) },
            title = { Text(s.approvalRequiredTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.approvalRequiredMessage)
                    Text("${req.server.name} → ${req.tool.toolName}", style = MaterialTheme.typography.titleSmall)
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.resolveMcpApproval(true) }) { Text(s.approveAndRun) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveMcpApproval(false) }) { Text(s.cancel) }
            }
        )
    }
}

@Composable
private fun AppLockGateScreen(onUnlock: (String) -> Boolean) {
    val s = LocalStrings.current
    var pin by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(s.appName, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); invalid = false },
                label = { Text("Enter Passcode") },
                visualTransformation = PasswordVisualTransformation(),
                isError = invalid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)
            )
            if (invalid) {
                Text("Incorrect passcode", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (!onUnlock(pin)) invalid = true
            }) { Text("Unlock") }
        }
    }
}
