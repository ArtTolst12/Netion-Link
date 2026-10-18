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
import io.github.arttolst12.netion.qr.QrScannerDialog

@Composable
fun ClientScreen(
    proxyIp: String,
    proxyPort: String,
    isConnected: Boolean,
    isServerRunning: Boolean = false,
    onProxyIpChange: (String) -> Unit,
    onProxyPortChange: (String) -> Unit,
    onConnectWith: ((ip: String, port: Int) -> Unit)? = null,
    onToggleVpn: () -> Unit
) {
    val s = LocalStrings.current
    var showScannerDialog by remember { mutableStateOf(false) }
    var showIpHelpDialog by remember { mutableStateOf(false) }

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
                    enabled       = !isConnected,
                    leadingIcon   = {
                        Icon(
                            Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon  = if (!isConnected) {
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

                val context = androidx.compose.ui.platform.LocalContext.current
                val gatewayIp = remember { io.github.arttolst12.netion.NetworkUtils.getClientGatewayIp(context) }
                LaunchedEffect(gatewayIp) {
                    if (gatewayIp != null && (proxyIp.isBlank() || proxyIp == io.github.arttolst12.netion.NetworkUtils.DEFAULT_HOTSPOT_IP)) {
                        onProxyIpChange(gatewayIp)
                    }
                }
                val quickIps = remember(gatewayIp) {
                    val list = mutableListOf<String>()
                    if (gatewayIp != null) list.add(gatewayIp)
                    if (!list.contains(io.github.arttolst12.netion.NetworkUtils.DEFAULT_HOTSPOT_IP)) {
                        list.add(io.github.arttolst12.netion.NetworkUtils.DEFAULT_HOTSPOT_IP)
                    }
                    list
                }

                if (!isConnected && quickIps.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (quickIp in quickIps) {
                            val isSelected = proxyIp == quickIp
                            val labelText = if (quickIp == gatewayIp) {
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

                // Быстрый выбор протокола / порта
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isHttpSelected = proxyPort == "8080"
                    val isSocksSelected = proxyPort == "1080"

                    FilterChip(
                        selected = isHttpSelected,
                        onClick = { if (!isConnected) onProxyPortChange("8080") },
                        label = {
                            Text(
                                text = "HTTP (8080)",
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        enabled = !isConnected,
                        modifier = Modifier.weight(1f)
                    )

                    FilterChip(
                        selected = isSocksSelected,
                        onClick = { if (!isConnected) onProxyPortChange("1080") },
                        label = {
                            Text(
                                text = "SOCKS5 (1080)",
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        enabled = !isConnected,
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
                    enabled       = !isConnected,
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
            val protocolName = if (proxyPort == "1080") "SOCKS5" else "HTTP"
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
                        text  = "${s.trafficRoutedVia} $proxyIp:$proxyPort ($protocolName)",
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
            onClick = onToggleVpn,
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
            onScanned = { ip, port ->
                showScannerDialog = false
                onProxyIpChange(ip)
                onProxyPortChange(port.toString())
                if (onConnectWith != null) {
                    onConnectWith(ip, port)
                } else {
                    onToggleVpn()
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
