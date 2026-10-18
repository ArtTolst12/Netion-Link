package com.example.netion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.netion.proxy.ProxyService
import com.example.netion.ui.*
import com.example.netion.ui.theme.NetionTheme
import com.example.netion.vpn.TunVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class BannerType { ERROR, WARNING, SUCCESS }

data class BannerMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val type: BannerType,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val isSystemDark = isSystemInDarkTheme()

    val prefs = remember { context.getSharedPreferences("netion_prefs", Context.MODE_PRIVATE) }
    
    // При первом запуске (если ключ "dark_theme" ещё не сохранялся) берем системную тему
    var darkTheme by remember {
        mutableStateOf(
            if (prefs.contains("dark_theme")) prefs.getBoolean("dark_theme", true)
            else isSystemDark
        )
    }

    var langCode by remember {
        mutableStateOf(
            prefs.getString("app_language", null)
                ?: AppLanguage.fromSystemLocale().code
        )
    }
    val initialIp = remember {
        val saved = prefs.getString("proxy_ip", null)
        if (!saved.isNullOrEmpty() && saved != "192.168.43.1") {
            saved
        } else {
            NetworkUtils.getClientGatewayIp(context) ?: saved ?: "192.168.43.1"
        }
    }
    var proxyIp   by remember { mutableStateOf(initialIp) }
    var proxyPort by remember { mutableStateOf(prefs.getString("proxy_port", "8080") ?: "8080") }

    val currentLanguage = when (langCode) {
        "en" -> AppLanguage.EN
        "de" -> AppLanguage.DE
        "fr" -> AppLanguage.FR
        "es" -> AppLanguage.ES
        "pt" -> AppLanguage.PT
        "pl" -> AppLanguage.PL
        "zh" -> AppLanguage.ZH
        "ja" -> AppLanguage.JA
        "ko" -> AppLanguage.KO
        "ru" -> AppLanguage.RU
        else -> AppLanguage.fromSystemLocale()
    }

    val currentStrings = when (currentLanguage) {
        AppLanguage.EN -> EnStrings
        AppLanguage.DE -> DeStrings
        AppLanguage.FR -> FrStrings
        AppLanguage.ES -> EsStrings
        AppLanguage.PT -> PtStrings
        AppLanguage.PL -> PlStrings
        AppLanguage.ZH -> ZhStrings
        AppLanguage.JA -> JaStrings
        AppLanguage.KO -> KoStrings
        AppLanguage.RU -> RuStrings
    }

    CompositionLocalProvider(LocalStrings provides currentStrings) {
        NetionTheme(darkTheme = darkTheme) {
            val drawerState = rememberDrawerState(DrawerValue.Closed)

            ModalNavigationDrawer(
                drawerState   = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        SettingsDrawer(
                            darkTheme = darkTheme,
                            currentLanguage = currentLanguage,
                            onDarkThemeToggle = { v ->
                                darkTheme = v
                                prefs.edit().putBoolean("dark_theme", v).apply()
                            },
                            onLanguageChange = { newLang ->
                                langCode = newLang.code
                                prefs.edit().putString("app_language", newLang.code).apply()
                            }
                        )
                    }
                }
            ) {
                MainScaffold(
                    context      = context,
                    prefs        = prefs,
                    proxyIp      = proxyIp,
                    proxyPort    = proxyPort,
                    onIpChange   = { proxyIp = it; prefs.edit().putString("proxy_ip", it).apply() },
                    onPortChange = { proxyPort = it; prefs.edit().putString("proxy_port", it).apply() },
                    onMenuClick  = { scope.launch { drawerState.open() } }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    context: Context,
    prefs: android.content.SharedPreferences,
    proxyIp: String,
    proxyPort: String,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onMenuClick: () -> Unit
) {
    val s = LocalStrings.current

    var selectedTab   by remember { mutableIntStateOf(0) }
    var serverIp      by remember { mutableStateOf<String?>(null) }
    var serverRunning by remember { mutableStateOf(ProxyService.isRunning) }
    var vpnConnected  by remember { mutableStateOf(TunVpnService.isRunning) }

    // Список активных баннеров
    val banners = remember { mutableStateListOf<BannerMessage>() }

    fun addBanner(
        text: String,
        type: BannerType,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        if (banners.none { it.text == text }) {
            banners.add(BannerMessage(text = text, type = type, actionLabel = actionLabel, onAction = onAction))
        }
    }

    fun removeBanner(id: String) {
        banners.removeAll { it.id == id }
    }

    var serverHttpPort by remember { mutableIntStateOf(prefs.getInt("server_http_port", prefs.getInt("server_port", 8080))) }
    var serverSocksPort by remember { mutableIntStateOf(prefs.getInt("server_socks_port", 1080)) }
    var activeHttpPortState by remember { mutableIntStateOf(com.example.netion.proxy.ProxyService.activeHttpPort) }
    var activeSocksPortState by remember { mutableIntStateOf(com.example.netion.proxy.ProxyService.activeSocksPort) }
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }

    LaunchedEffect(Unit) {
        serverIp = NetworkUtils.getServerIp(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            vpnConnected = TunVpnService.isRunning
            serverRunning = ProxyService.isRunning
            activeHttpPortState = com.example.netion.proxy.ProxyService.activeHttpPort
            activeSocksPortState = com.example.netion.proxy.ProxyService.activeSocksPort
            if (!serverRunning) {
                val latestIp = NetworkUtils.getServerIp(context)
                if (latestIp != serverIp) {
                    serverIp = latestIp
                }
            }
            TunVpnService.lastError?.let { err ->
                addBanner(err, BannerType.ERROR)
                TunVpnService.lastError = null
            }
            ProxyService.portReallocationNotice?.let { notice ->
                addBanner("${s.portReallocatedBanner}: $notice", BannerType.WARNING)
                ProxyService.portReallocationNotice = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
                banners.removeAll { it.text == s.batteryOptBanner }
            }
            delay(1000)
        }
    }

    // ─── Запрос отключения энергосбережения / оптимизации батареи ─
    val batteryBannerDismissed = remember { prefs.getBoolean("dismiss_battery_opt", false) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !batteryBannerDismissed) {
            val isIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            if (!isIgnored) {
                addBanner(
                    text = s.batteryOptBanner,
                    type = BannerType.WARNING,
                    actionLabel = s.disableBatteryOpt,
                    onAction = {
                        // User interacted with the setting; remember dismissal so it doesn't repeatedly harass on OEM shells
                        prefs.edit().putBoolean("dismiss_battery_opt", true).apply()
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    }
                )
            }
        }
    }

    // ─── Запрос разрешения на уведомления (Android 13+) ──────────
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            addBanner(
                text = s.notifPermissionDenied,
                type = BannerType.WARNING,
                actionLabel = s.openSettings,
                onAction = {
                    try {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ─── VPN permission launcher ──────────────────────────────────
    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val portNum = proxyPort.toIntOrNull() ?: 8080
            val pType = if (portNum == 1080) "SOCKS5" else "HTTP"
            startVpnService(context, proxyIp, portNum, pType)
            vpnConnected = true
        } else {
            addBanner(
                text = s.vpnRejectedError,
                type = BannerType.ERROR
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text       = s.appName,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 22.sp,
                            maxLines   = 1,
                            color      = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text       = "V2.3.15",
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.primary,
                                modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ─── Вкладки ──────────────────────────────────────────
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor   = MaterialTheme.colorScheme.background,
                contentColor     = MaterialTheme.colorScheme.primary,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick  = { selectedTab = 0 },
                    text     = { Text(s.serverTab) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick  = { selectedTab = 1 },
                    text     = { Text(s.clientTab) }
                )
            }

            // ─── Стек плашек / баннеров с ошибками ─────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                banners.forEach { msg ->
                    AppBanner(
                        message = msg,
                        onDismiss = {
                            if (msg.text == s.batteryOptBanner) {
                                prefs.edit().putBoolean("dismiss_battery_opt", true).apply()
                            }
                            removeBanner(msg.id)
                        }
                    )
                }
            }

            // ─── Контент вкладок ───────────────────────────────────
            when (selectedTab) {
                0 -> ServerScreen(
                    serverIp   = serverIp,
                    httpPort   = if (serverRunning) activeHttpPortState else serverHttpPort,
                    socks5Port = if (serverRunning) activeSocksPortState else serverSocksPort,
                    isRunning  = serverRunning,
                    onRefreshIp = {
                        serverIp = NetworkUtils.getServerIp(context)
                        banners.removeAll { it.text == s.networkNotFoundBanner || it.text == s.tetheringUnavailableBanner }
                    },
                    onPortsChange = { newHttp, newSocks ->
                        serverHttpPort = newHttp
                        serverSocksPort = newSocks
                        prefs.edit()
                            .putInt("server_http_port", newHttp)
                            .putInt("server_socks_port", newSocks)
                            .putInt("server_port", newHttp)
                            .apply()
                    },
                    onToggleServer = {
                        if (serverRunning) {
                            stopProxyService(context)
                            serverRunning = false
                        } else {
                            if (vpnConnected) {
                                addBanner(s.disconnectVpnBeforeServerError, BannerType.WARNING)
                                return@ServerScreen
                            }
                            if (serverIp == null) {
                                serverIp = NetworkUtils.getServerIp(context)
                            }
                            if (serverIp != null) {
                                startProxyService(context, serverHttpPort, serverSocksPort)
                                serverRunning = true
                                banners.removeAll { it.type == BannerType.ERROR && it.text == s.tetheringUnavailableBanner }
                            }
                        }
                    }
                )

                1 -> ClientScreen(
                    proxyIp         = proxyIp,
                    proxyPort       = proxyPort,
                    isConnected     = vpnConnected,
                    isServerRunning = serverRunning,
                    onProxyIpChange   = onIpChange,
                    onProxyPortChange = onPortChange,
                    onConnectWith = { scannedIp, scannedPort ->
                        onIpChange(scannedIp)
                        onPortChange(scannedPort.toString())
                        if (serverRunning) {
                            addBanner(s.stopServerBeforeVpnError, BannerType.WARNING)
                        } else {
                            addBanner(s.qrScanSuccess, BannerType.SUCCESS)
                            val vpnIntent = VpnService.prepare(context)
                            val pType = if (scannedPort == 1080) "SOCKS5" else "HTTP"
                            if (vpnIntent == null) {
                                startVpnService(context, scannedIp, scannedPort, pType)
                                vpnConnected = true
                            } else {
                                vpnPermLauncher.launch(vpnIntent)
                            }
                        }
                    },
                    onToggleVpn = {
                        if (vpnConnected) {
                            stopVpnService(context)
                            vpnConnected = false
                        } else {
                            if (serverRunning) {
                                addBanner(s.stopServerBeforeVpnError, BannerType.WARNING)
                                return@ClientScreen
                            }
                            val ip = proxyIp.trim()
                            val port = proxyPort.toIntOrNull()

                            var hasInputError = false
                            if (ip.isEmpty()) {
                                addBanner(s.enterIpError, BannerType.ERROR)
                                hasInputError = true
                            }
                            if (port == null || port !in 1..65535) {
                                addBanner(s.invalidPortError, BannerType.ERROR)
                                hasInputError = true
                            }

                            if (!hasInputError) {
                                val vpnIntent = VpnService.prepare(context)
                                val pType = if (port == 1080) "SOCKS5" else "HTTP"
                                if (vpnIntent == null) {
                                    startVpnService(context, ip, port!!, pType)
                                    vpnConnected = true
                                } else {
                                    vpnPermLauncher.launch(vpnIntent)
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppBanner(message: BannerMessage, onDismiss: () -> Unit) {
    val containerColor = when (message.type) {
        BannerType.ERROR   -> MaterialTheme.colorScheme.errorContainer
        BannerType.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        BannerType.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when (message.type) {
        BannerType.ERROR   -> MaterialTheme.colorScheme.onErrorContainer
        BannerType.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        BannerType.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        color = containerColor,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text     = message.text,
                color    = contentColor,
                style    = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )

            if (message.actionLabel != null && message.onAction != null) {
                TextButton(onClick = message.onAction) {
                    Text(
                        text = message.actionLabel,
                        color = contentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Text("✕", color = contentColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun startProxyService(context: Context, httpPort: Int, socksPort: Int = 1080) {
    val intent = Intent(context, ProxyService::class.java).apply {
        putExtra(ProxyService.EXTRA_HTTP_PORT, httpPort)
        putExtra(ProxyService.EXTRA_SOCKS_PORT, socksPort)
        putExtra(ProxyService.EXTRA_PORT, httpPort)
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopProxyService(context: Context) {
    val intent = Intent(context, ProxyService::class.java).apply {
        action = ProxyService.ACTION_STOP
    }
    context.startService(intent)
    context.stopService(Intent(context, ProxyService::class.java))
}

private fun startVpnService(
    context: Context,
    ip: String,
    port: Int,
    proxyType: String = if (port == 1080) "SOCKS5" else "HTTP"
) {
    val intent = Intent(context, TunVpnService::class.java).apply {
        putExtra(TunVpnService.EXTRA_PROXY_IP, ip)
        putExtra(TunVpnService.EXTRA_PROXY_PORT, port)
        putExtra(TunVpnService.EXTRA_PROXY_TYPE, proxyType)
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopVpnService(context: Context) {
    TunVpnService.isRunning = false
    val intent = Intent(context, TunVpnService::class.java).apply {
        action = TunVpnService.ACTION_STOP
    }
    context.startService(intent)
    context.stopService(Intent(context, TunVpnService::class.java))
}
