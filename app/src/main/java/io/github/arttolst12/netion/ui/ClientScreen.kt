package io.github.arttolst12.netion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arttolst12.netion.qr.ProxyProtocol
import io.github.arttolst12.netion.qr.QrPayload
import io.github.arttolst12.netion.qr.QrScannerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ClientScreen(
    proxyIp: String,
    proxyPort: String,
    isConnected: Boolean,
    isServerRunning: Boolean = false,
    onProxyIpChange: (String) -> Unit,
    onProxyPortChange: (String) -> Unit,
    onConnectWith: ((QrPayload) -> Unit)? = null,
    onToggleVpn: (ProxyProtocol) -> Unit,
    isStarting: Boolean = false
) {
    val s = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var gatewayIp by remember(context) { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        gatewayIp = withContext(Dispatchers.IO) {
            io.github.arttolst12.netion.NetworkUtils.getClientGatewayIp(context)
        }
    }

    var showScannerDialog by remember { mutableStateOf(false) }
    var showIpHelpDialog by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf(ProxyProtocol.fromPort(proxyPort.toIntOrNull() ?: 8080)) }

    LaunchedEffect(proxyPort) {
        // Preserve an explicitly chosen/scanned protocol for custom ports. Standard
        // port edits still select their conventional protocol for manual entry.
        when (proxyPort.toIntOrNull()) {
            8080 -> selectedProtocol = ProxyProtocol.HTTP
            1080 -> selectedProtocol = ProxyProtocol.SOCKS5
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // ─── Карточка статуса подключения ─────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(12.dp),
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Заголовок со статус-точкой и кнопкой справки
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text  = s.connectionParams,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isConnected) {
                        Spacer(Modifier.width(8.dp))
                        ConnectedDot()
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { showIpHelpDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = s.ipHelpTitle,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // IP-поле
                OutlinedTextField(
                    value         = proxyIp,
                    onValueChange = onProxyIpChange,
                    label         = { Text(s.senderIpLabel) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled       = !isConnected && !isStarting,
                    leadingIcon   = {
                        Icon(
                            Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon  = if (!isConnected && !isStarting) {
                        {
                            IconButton(onClick = { showScannerDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = s.scanQrCode,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else null
                )

                val detectedGateway = gatewayIp
                LaunchedEffect(detectedGateway) {
                    if (detectedGateway != null && (proxyIp.isBlank() || proxyIp == io.github.arttolst12.netion.NetworkUtils.DEFAULT_HOTSPOT_IP)) {
                        onProxyIpChange(detectedGateway)
                    }
                }
                val quickIps = remember(detectedGateway) {
                    val list = mutableListOf<String>()
                    if (detectedGateway != null) list.add(detectedGateway)
                    if (!list.contains(io.github.arttolst12.netion.NetworkUtils.DEFAULT_HOTSPOT_IP)) {
                        list.add(io.github.arttolst12.netion.NetworkUtils.DEFAULT_HOTSPOT_IP)
                    }
                    list
                }

                if (!isConnected && !isStarting && quickIps.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (quickIp in quickIps) {
                            val isSelected = proxyIp == quickIp
                            val labelText = if (quickIp == detectedGateway) {
                                "$quickIp (${s.ipChipAutoDetected})"
                            } else {
                                "$quickIp (${s.ipChipFallbackHotspot})"
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { onProxyIpChange(quickIp) },
                                label = {
                                    Text(
                                        text = labelText,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }

                // Protocol selection is independent from the port: QR configurations
                // may use custom ports for either HTTP or SOCKS5.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedProtocol == ProxyProtocol.HTTP,
                        onClick = {
                            if (!isConnected && !isStarting) {
                                selectedProtocol = ProxyProtocol.HTTP
                                if (proxyPort == "1080") onProxyPortChange("8080")
                            }
                        },
                        label = {
                            Text(
                                text = "HTTP",
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        enabled = !isConnected && !isStarting,
                        modifier = Modifier.weight(1f)
                    )

                    FilterChip(
                        selected = selectedProtocol == ProxyProtocol.SOCKS5,
                        onClick = {
                            if (!isConnected && !isStarting) {
                                selectedProtocol = ProxyProtocol.SOCKS5
                                if (proxyPort == "8080") onProxyPortChange("1080")
                            }
                        },
                        label = {
                            Text(
                                text = "SOCKS5",
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        enabled = !isConnected && !isStarting,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Порт-поле
                OutlinedTextField(
                    value         = proxyPort,
                    onValueChange = onProxyPortChange,
                    label         = { Text(s.portLabel) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled       = !isConnected && !isStarting,
                    supportingText = {
                        Text(
                            s.portHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                )
            }
        }


        // ─── Статус подключения ────────────────────────────────────
        if (isConnected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text  = s.vpnActive,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 14.sp
                    )
                    Text(
                        text  = "${s.trafficRoutedVia} $proxyIp:$proxyPort (${selectedProtocol.name})",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )
                }
            }

            TrafficStatsCard(isActive = true, isServer = false)
        }

        // ─── Предупреждение о взаимном исключении ─────────────────
        if (isServerRunning && !isConnected) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = s.stopServerBeforeVpnError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ─── Кнопка подключения ────────────────────────────────────
        Button(
            onClick = { onToggleVpn(selectedProtocol) },
            enabled = !isStarting,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape  = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                contentColor = Color.Black
            )
        ) {
            Text(
                text       = if (isConnected) s.disconnectVpn else s.connectVpn,
                fontWeight = FontWeight.Bold,
                fontSize   = 15.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showScannerDialog) {
        QrScannerDialog(
            onScanned = { payload ->
                showScannerDialog = false
                onProxyIpChange(payload.ip)
                onProxyPortChange(payload.port.toString())
                selectedProtocol = payload.protocol
                if (onConnectWith != null) {
                    onConnectWith(payload)
                } else {
                    onToggleVpn(payload.protocol)
                }
            },
            onDismiss = { showScannerDialog = false }
        )
    }

    if (showIpHelpDialog) {
        AlertDialog(
            onDismissRequest = { showIpHelpDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = s.ipHelpTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "1. ${s.ipHelpHotspotTitle}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = s.ipHelpHotspotDesc,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "2. ${s.ipHelpWifiTitle}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = s.ipHelpWifiDesc,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "3. ${s.ipHelpUsbTitle}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = s.ipHelpUsbDesc,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIpHelpDialog = false }) {
                    Text(s.close)
                }
            }
        )
    }
}

@Composable
private fun ConnectedDot() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF00E676))
    )
}
