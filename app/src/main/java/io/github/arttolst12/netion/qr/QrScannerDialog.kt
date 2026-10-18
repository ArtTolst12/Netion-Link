package io.github.arttolst12.netion.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import io.github.arttolst12.netion.ui.LocalStrings
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerDialog(
    onScanned: (ip: String, port: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val s = LocalStrings.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                CameraScannerContent(
                    onScanned = { payload ->
                        vibrateBriefly(context)
                        onScanned(payload.ip, payload.port)
                        onDismiss()
                    },
                    onDismiss = onDismiss
                )
            } else {
                // Permission Denied View
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = s.cameraPermissionRequired,
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(s.openSettings)
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDismiss) {
                        Text(s.cancel, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraScannerContent(
    onScanned: (QrPayload) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val s = LocalStrings.current

    var camera by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            try {
                androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {}
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(
                        cameraExecutor,
                        QrCodeAnalyzer { payload ->
                            previewView.post {
                                onScanned(payload)
                            }
                        }
                    )

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e("QrScanner", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // Viewfinder overlay
        ViewfinderOverlay()

        // Top controls: Close button and Torch button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }

            // Torch toggle
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(
                    onClick = {
                        val next = !isTorchOn
                        camera?.cameraControl?.enableTorch(next)
                        isTorchOn = next
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Torch",
                        tint = if (isTorchOn) Color.Yellow else Color.White
                    )
                }
            }
        }

        // Bottom hint
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.65f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp, start = 32.dp, end = 32.dp)
        ) {
            Text(
                text = s.qrDialogSubtitle,
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun ViewfinderOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val laserYRatio by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val boxSize = minOf(size.width * 0.72f, 280.dp.toPx())
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2.3f
        val right = left + boxSize
        val bottom = top + boxSize

        val boxRect = Rect(left, top, right, bottom)
        val cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx())

        // Dim background outside the box
        val path = Path().apply {
            addRoundRect(RoundRect(boxRect, cornerRadius))
        }

        clipPath(path, clipOp = ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = 0.55f))
        }

        // Draw corner brackets
        val strokeWidth = 4.dp.toPx()
        val cornerLen = 28.dp.toPx()
        val cornerColor = Color(0xFF6750A4)

        // Top-left
        drawLine(cornerColor, Offset(left, top + cornerLen), Offset(left, top + 10f), strokeWidth)
        drawLine(cornerColor, Offset(left, top), Offset(left + cornerLen, top), strokeWidth)

        // Top-right
        drawLine(cornerColor, Offset(right - cornerLen, top), Offset(right, top), strokeWidth)
        drawLine(cornerColor, Offset(right, top), Offset(right, top + cornerLen), strokeWidth)

        // Bottom-left
        drawLine(cornerColor, Offset(left, bottom - cornerLen), Offset(left, bottom), strokeWidth)
        drawLine(cornerColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeWidth)

        // Bottom-right
        drawLine(cornerColor, Offset(right - cornerLen, bottom), Offset(right, bottom), strokeWidth)
        drawLine(cornerColor, Offset(right, bottom - cornerLen), Offset(right, bottom), strokeWidth)

        // Animated laser scan line
        val laserY = top + (bottom - top) * laserYRatio
        drawLine(
            color = Color(0xFF9A82DB).copy(alpha = 0.85f),
            start = Offset(left + 12f, laserY),
            end = Offset(right - 12f, laserY),
            strokeWidth = 2.dp.toPx()
        )
    }
}

private class QrCodeAnalyzer(
    private val onQrFound: (QrPayload) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        setHints(hints)
    }

    private val isFound = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (isFound.get()) {
            image.close()
            return
        }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height

            val yBytes = ByteArray(buffer.remaining())
            buffer.get(yBytes)

            val rotationDegrees = image.imageInfo.rotationDegrees
            val rotatedBytes = rotateBytes(yBytes, width, height, rowStride, pixelStride, rotationDegrees)
            val finalWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
            val finalHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

            val source = PlanarYUVLuminanceSource(
                rotatedBytes,
                finalWidth,
                finalHeight,
                0,
                0,
                finalWidth,
                finalHeight,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val result = reader.decodeWithState(binaryBitmap)
            if (result != null && result.text.isNotBlank()) {
                val payload = QrCodeManager.parseQrString(result.text)
                if (payload != null && isFound.compareAndSet(false, true)) {
                    onQrFound(payload)
                }
            }
        } catch (_: NotFoundException) {
            // No barcode found in current frame
        } catch (e: Exception) {
            Log.d("QrCodeAnalyzer", "Analyze error: ${e.message}")
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun rotateBytes(
        src: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        degrees: Int
    ): ByteArray {
        val clean = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                clean[y * width + x] = src[y * rowStride + x * pixelStride]
            }
        }

        return when (degrees) {
            90 -> {
                val rotated = ByteArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        rotated[x * height + (height - y - 1)] = clean[y * width + x]
                    }
                }
                rotated
            }
            180 -> {
                val rotated = ByteArray(width * height)
                val len = width * height
                for (i in 0 until len) {
                    rotated[len - 1 - i] = clean[i]
                }
                rotated
            }
            270 -> {
                val rotated = ByteArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        rotated[(width - x - 1) * height + y] = clean[y * width + x]
                    }
                }
                rotated
            }
            else -> clean
        }
    }
}

private fun vibrateBriefly(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(100)
        }
    } catch (_: Exception) {}
}
