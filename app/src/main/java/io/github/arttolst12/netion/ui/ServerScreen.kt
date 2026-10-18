package io.github.arttolst12.netion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arttolst12.netion.NetworkUtils
import io.github.arttolst12.netion.qr.QrCodeDialog

@Composable
fun ServerScreen(
    serverIp: String?,
    httpPort: Int = 8080,
    socks5Port: Int = 1080,
    isRunning: Boolean,
    onRefreshIp: () -> Unit,
    onPortsChange: (httpPort: Int, socks5Port: Int) -> Unit,
    onToggleServer: () -> Unit
) {
    val s = LocalStrings.current
    val hasTethering = serverIp != null

    var showQrDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showBusyPromptDialog by remember { mutableStateOf(false) }
    var suggestedHttpPort by remember { mutableIntStateOf(8081) }
    var suggestedSocksPort by remember { mutableIntStateOf(1081) }
    var httpWasBusy by remember { mutableStateOf(false) }
    var socksWasBusy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // ─── Error / Info card ─────────────────────────────────────
        if (!hasTethering && !isRunning) {
            ErrorBanner(s.noNetworkError)
        }

        if (hasTethering || isRunning) {
            IpPortCard(
                ip          = serverIp ?: "—",
                httpPort    = httpPort,
                socks5Port  = socks5Port,
                isRunning   = isRunning,
                ipLabel     = s.serverIpLabel,
                httpLabel   = s.httpPortLabel,
                socks5Label = s.socks5PortLabel,
                onEditPorts = { showEditDialog = true },
                onShowQr    = if (serverIp != null) { { showQrDialog = true } } else null
            )
        }

        if (isRunning) {
            TrafficStatsCard(isActive = true, isServer = true)
        }

        // ─── Refresh IP button ─────────────────────────────────────
        TextButton(
            onClick = onRefreshIp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text  = s.refreshIp,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // ─── Start/Stop button ─────────────────────────────────────
        Button(
            onClick = {
                if (isRunning) {
                    onToggleServer()
                } else {
                    // Check if ports are available before starting
                    val httpFree = NetworkUtils.isPortAvailable(httpPort)
                    val socksFree = NetworkUtils.isPortAvailable(socks5Port) && socks5Port != httpPort

                    if (!httpFree || !socksFree) {
                        httpWasBusy = !httpFree
                        socksWasBusy = !socksFree
                        val freeHttp = NetworkUtils.findAvailablePort(httpPort)
                        val freeSocks = NetworkUtils.findAvailablePort(socks5Port, setOf(freeHttp))
                        suggestedHttpPort = freeHttp
                        suggestedSocksPort = freeSocks
                        showBusyPromptDialog = true
                    } else {
                        onToggleServer()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                contentColor   = Color.Black
            )
        ) {
            Text(
                text       = if (isRunning) s.stopTethering else s.startTethering,
                fontWeight = FontWeight.Bold,
                fontSize   = 15.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showQrDialog && serverIp != null) {
        QrCodeDialog(
            ip = serverIp,
            httpPort = httpPort,
            socks5Port = socks5Port,
            onDismiss = { showQrDialog = false }
        )
    }

    // ─── Диалог предупреждения о занятых портах ─────────────────
    if (showBusyPromptDialog) {
        AlertDialog(
            onDismissRequest = { showBusyPromptDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(s.portsBusyDialogTitle)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.portsBusyDialogDesc)

                    if (httpWasBusy) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    text = "HTTP $httpPort — ${s.portBusy}",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = s.suggestedFreePort.format(suggestedHttpPort),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    if (socksWasBusy) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    text = "SOCKS5 $socks5Port — ${s.portBusy}",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = s.suggestedFreePort.format(suggestedSocksPort),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBusyPromptDialog = false
                        onPortsChange(suggestedHttpPort, suggestedSocksPort)
                        onToggleServer()
                    }
                ) {
                    Text(s.useSuggestedPorts)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showBusyPromptDialog = false }) {
                        Text(s.cancel)
                    }
                    TextButton(
                        onClick = {
                            showBusyPromptDialog = false
                            showEditDialog = true
                        }
                    ) {
                        Text(s.configureManually)
                    }
                }
            }
        )
    }

    // ─── Диалог ручной настройки портов ──────────────────────────
    if (showEditDialog) {
        PortConfigDialog(
            currentHttpPort = httpPort,
            currentSocksPort = socks5Port,
            onDismiss = { showEditDialog = false },
            onSave = { newHttp, newSocks ->
                onPortsChange(newHttp, newSocks)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun PortConfigDialog(
    currentHttpPort: Int,
    currentSocksPort: Int,
    onDismiss: () -> Unit,
    onSave: (httpPort: Int, socksPort: Int) -> Unit
) {
    val s = LocalStrings.current
    var httpInput by remember { mutableStateOf(currentHttpPort.toString()) }
    var socksInput by remember { mutableStateOf(currentSocksPort.toString()) }

    val httpVal = httpInput.toIntOrNull()
    val socksVal = socksInput.toIntOrNull()

    val isHttpFree = httpVal != null && NetworkUtils.isPortAvailable(httpVal)
    val isSocksFree = socksVal != null && socksVal != httpVal && NetworkUtils.isPortAvailable(socksVal)

    val canSave = httpVal != null && httpVal in 1024..65535 &&
            socksVal != null && socksVal in 1024..65535 &&
            httpVal != socksVal

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.editPortsTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // HTTP Port
                Column {
                    OutlinedTextField(
                        value = httpInput,
                        onValueChange = { httpInput = it.filter { char -> char.isDigit() } },
                        label = { Text(s.httpPortLabel) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (httpVal != null) {
                                PortStatusBadge(isFree = isHttpFree)
                            }
                        }
                    )
                }

                // SOCKS5 Port
                Column {
                    OutlinedTextField(
                        value = socksInput,
                        onValueChange = { socksInput = it.filter { char -> char.isDigit() } },
                        label = { Text(s.socks5PortLabel) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (socksVal != null) {
                                PortStatusBadge(isFree = isSocksFree)
                            }
                        }
                    )
                }

                // Кнопка авто-подбора свободных портов
                OutlinedButton(
                    onClick = {
                        val freeHttp = NetworkUtils.findAvailablePort(8080)
                        val freeSocks = NetworkUtils.findAvailablePort(1080, setOf(freeHttp))
                        httpInput = freeHttp.toString()
                        socksInput = freeSocks.toString()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoMode,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(s.autoSelectFreePorts, fontSize = 13.sp)
                }

                // Быстрые пресеты
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {
                            httpInput = "8080"
                            socksInput = "1080"
                        },
                        label = { Text("8080 / 1080", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(
                        onClick = {
                            httpInput = "8888"
                            socksInput = "1088"
                        },
                        label = { Text("8888 / 1088", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSave) {
                        onSave(httpVal!!, socksVal!!)
                    }
                },
                enabled = canSave
            ) {
                Text(s.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.cancel)
            }
        }
    )
}

@Composable
private fun PortStatusBadge(isFree: Boolean) {
    val s = LocalStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isFree) Color(0xFF2E7D32).copy(alpha = 0.15f) else Color(0xFFC62828).copy(alpha = 0.15f),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            text = if (isFree) s.portFree else s.portBusy,
            color = if (isFree) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFB71C1C))
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text  = message,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun IpPortCard(
    ip: String,
    httpPort: Int,
    socks5Port: Int,
    isRunning: Boolean,
    ipLabel: String,
    httpLabel: String,
    socks5Label: String,
    onEditPorts: () -> Unit,
    onShowQr: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!isRunning) Modifier.clickable { onEditPorts() } else Modifier),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text  = ipLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isRunning) {
                        Spacer(Modifier.width(8.dp))
                        StatusDot(active = true)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!isRunning) {
                        IconButton(
                            onClick = onEditPorts,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit ports",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (onShowQr != null) {
                        IconButton(
                            onClick = onShowQr,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "QR Code",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text     = ip,
                style    = MaterialTheme.typography.headlineMedium,
                color    = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PortBadge(
                    label = "HTTP",
                    port = httpPort,
                    desc = httpLabel,
                    modifier = Modifier.weight(1f)
                )
                PortBadge(
                    label = "SOCKS5",
                    port = socks5Port,
                    desc = socks5Label,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PortBadge(
    label: String,
    port: Int,
    desc: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "$port",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFF4CAF50) else Color.Gray)
    )
}
