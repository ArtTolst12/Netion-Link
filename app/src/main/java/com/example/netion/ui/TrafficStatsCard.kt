package com.example.netion.ui

import android.net.TrafficStats
import android.os.Process
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

object TrafficTracker {
    const val MAX_POINTS = 30
    var serverHistory: List<Long> = List(MAX_POINTS) { 0L }
    var serverPrevRaw: Long? = null
    var serverPeakSpeed: Long = 0L

    var clientHistory: List<Long> = List(MAX_POINTS) { 0L }
    var clientPrevRaw: Long? = null
    var clientPeakSpeed: Long = 0L

    fun resetServer() {
        serverHistory = List(MAX_POINTS) { 0L }
        serverPrevRaw = null
        serverPeakSpeed = 0L
    }

    fun resetClient() {
        clientHistory = List(MAX_POINTS) { 0L }
        clientPrevRaw = null
        clientPeakSpeed = 0L
    }
}

@Composable
fun TrafficStatsCard(
    isActive: Boolean,
    isServer: Boolean,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current

    var currentSpeed by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var peakSpeed by remember { mutableStateOf(if (isServer) TrafficTracker.serverPeakSpeed else TrafficTracker.clientPeakSpeed) }
    var speedHistory by remember {
        mutableStateOf(if (isServer) TrafficTracker.serverHistory else TrafficTracker.clientHistory)
    }

    LaunchedEffect(isActive, isServer) {
        if (!isActive) {
            currentSpeed = 0L
            if (isServer) TrafficTracker.resetServer() else TrafficTracker.resetClient()
            return@LaunchedEffect
        }

        while (true) {
            // Get accurate raw bytes directly from service layer if available; fallback to TrafficStats
            val curRaw: Long = if (isServer) {
                val srv = com.example.netion.proxy.ProxyService.currentInstance?.proxyServer
                srv?.totalBytesTx?.get() ?: com.example.netion.proxy.ProxyService.serverBytesTx.get()
            } else {
                val vpnBytes = com.example.netion.vpn.TunVpnService.clientBytesRx.get()
                if (vpnBytes > 0L) {
                    vpnBytes
                } else {
                    val uid = Process.myUid()
                    val uidBytes = TrafficStats.getUidRxBytes(uid)
                    if (uidBytes < 0) 0L else uidBytes
                }
            }

            totalBytes = curRaw

            val prev = if (isServer) TrafficTracker.serverPrevRaw else TrafficTracker.clientPrevRaw
            if (prev == null) {
                if (isServer) TrafficTracker.serverPrevRaw = curRaw else TrafficTracker.clientPrevRaw = curRaw
                currentSpeed = 0L
            } else {
                val delta = (curRaw - prev).coerceAtLeast(0L)
                currentSpeed = delta

                val currentPeak = if (isServer) TrafficTracker.serverPeakSpeed else TrafficTracker.clientPeakSpeed
                val newPeak = maxOf(currentPeak, delta)
                if (isServer) TrafficTracker.serverPeakSpeed = newPeak else TrafficTracker.clientPeakSpeed = newPeak
                peakSpeed = newPeak

                val hist = if (isServer) TrafficTracker.serverHistory else TrafficTracker.clientHistory
                val updatedHist = (hist.drop(1) + delta).takeLast(TrafficTracker.MAX_POINTS)
                if (isServer) TrafficTracker.serverHistory = updatedHist else TrafficTracker.clientHistory = updatedHist
                speedHistory = updatedHist

                if (isServer) TrafficTracker.serverPrevRaw = curRaw else TrafficTracker.clientPrevRaw = curRaw
            }

            delay(1000L)
        }
    }

    val cardTitle = if (isServer) s.trafficUpload else s.trafficDownload
    val cardIcon = if (isServer) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
    val accentColor = if (isServer) Color(0xFF00B0FF) else MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = cardIcon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = cardTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = accentColor
                    )
                }

                // Live Speed Pill with pulsing dot
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isActive)
                        Color(0xFF4CAF50).copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = formatSpeed(currentSpeed),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isActive) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Real-time Traffic Graph
            TrafficGraph(
                history = speedHistory,
                primaryColor = accentColor,
                gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                peakLabel = "${s.trafficPeak}: ${formatSpeed(peakSpeed)}",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )

            // 3-Metric Row: Current Speed | Total Transferred | Peak Speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricChip(
                    title = s.trafficSpeed,
                    value = formatSpeed(currentSpeed),
                    color = accentColor,
                    modifier = Modifier.weight(1f)
                )

                MetricChip(
                    title = s.trafficTotal,
                    value = formatBytes(totalBytes),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                MetricChip(
                    title = s.trafficPeak,
                    value = formatSpeed(peakSpeed),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricChip(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontWeight = FontWeight.Medium
            )

            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun TrafficGraph(
    history: List<Long>,
    primaryColor: Color,
    gridColor: Color,
    peakLabel: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Horizontal grid lines
            val lineCount = 3
            for (i in 0..lineCount) {
                val y = height * (i.toFloat() / lineCount)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }

            if (history.isEmpty()) return@Canvas

            val maxVal = (history.maxOrNull() ?: 1L).coerceAtLeast(1024L).toFloat()
            val stepX = width / (history.size - 1).coerceAtLeast(1)

            val fillPath = Path()
            val strokePath = Path()

            history.forEachIndexed { index, value ->
                val x = index * stepX
                val ratio = (value.toFloat() / maxVal).coerceIn(0f, 1f)
                val y = height - (ratio * (height - 8f)) - 4f

                if (index == 0) {
                    strokePath.moveTo(x, y)
                    fillPath.moveTo(x, height)
                    fillPath.lineTo(x, y)
                } else {
                    val prevX = (index - 1) * stepX
                    val prevRatio = (history[index - 1].toFloat() / maxVal).coerceIn(0f, 1f)
                    val prevY = height - (prevRatio * (height - 8f)) - 4f

                    val cx = (prevX + x) / 2f
                    strokePath.cubicTo(cx, prevY, cx, y, x, y)
                    fillPath.cubicTo(cx, prevY, cx, y, x, y)
                }
            }

            fillPath.lineTo(width, height)
            fillPath.close()

            // Draw gradient area under curve
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.30f),
                        primaryColor.copy(alpha = 0.0f)
                    ),
                    startY = 0f,
                    endY = height
                )
            )

            // Draw smooth curve stroke
            drawPath(
                path = strokePath,
                color = primaryColor,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
        }

        // Peak label at the top right of the graph
        Text(
            text = peakLabel,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 4.dp)
        )
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var b = bytes.toDouble()
    var i = 0
    while (b >= 1024.0 && i < units.size - 1) {
        b /= 1024.0
        i++
    }
    return if (i == 0) {
        String.format(Locale.US, "%d %s", bytes, units[i])
    } else {
        String.format(Locale.US, "%.1f %s", b, units[i])
    }
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var b = bytesPerSec.toDouble()
    var i = 0
    while (b >= 1024.0 && i < units.size - 1) {
        b /= 1024.0
        i++
    }
    return if (i == 0) {
        String.format(Locale.US, "%d %s", bytesPerSec, units[i])
    } else {
        String.format(Locale.US, "%.1f %s", b, units[i])
    }
}
