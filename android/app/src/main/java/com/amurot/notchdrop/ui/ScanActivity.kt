package com.amurot.notchdrop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.NodeService
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScanActivity : ComponentActivity() {
    private sealed interface Phase {
        data object Scanning : Phase
        data object Pairing : Phase
        data class Paired(val name: String) : Phase
        data class Failed(val message: String) : Phase
    }

    private var phase by mutableStateOf<Phase>(Phase.Scanning)
    private var hasCamera by mutableStateOf(false)
    private val handled = AtomicBoolean(false)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent {
            NotchTheme {
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
                LaunchedEffect(Unit) { if (!hasCamera) ask.launch(Manifest.permission.CAMERA) }
                ScanScreen(
                    hasCamera = hasCamera,
                    camera = { CameraPreview() },
                    phase = phase,
                    onClose = ::finish,
                    onRetry = {
                        phase = Phase.Scanning
                        handled.set(false)
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        scanner.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun onCode(raw: String) {
        if (!raw.startsWith("notchdrop://pair") || !handled.compareAndSet(false, true)) return
        phase = Phase.Pairing
        lifecycleScope.launch {
            try {
                val peer = Node.pair(raw)
                phase = Phase.Paired(peer.displayName)
                Node.store.receiveEnabled = true
                NodeService.start(this@ScanActivity)
                delay(1400)
                finish()
            } catch (e: Exception) {
                phase = Phase.Failed(e.message ?: "Pairing failed")
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || handled.get()) {
            proxy.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { runOnUiThread { onCode(it) } } }
            .addOnCompleteListener { proxy.close() }
    }

    @Composable
    private fun CameraPreview() {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                    provider.unbindAll()
                    runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
        )
    }

    @Composable
    private fun ScanScreen(
        hasCamera: Boolean,
        camera: @Composable () -> Unit,
        phase: Phase,
        onClose: () -> Unit,
        onRetry: () -> Unit,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCamera) camera()
            // Viewfinder: the same dashed rounded box as the Mac drop zone.
            Box(
                Modifier.align(Alignment.Center).size(260.dp)
                    .dashedOutline(Color.White.copy(alpha = 0.7f), active = phase == Phase.Pairing, radius = 32.dp, phase = marchingPhase()),
            )
            IconButton(onClick = onClose, modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, "Close", tint = Color.White)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (phase) {
                    Phase.Scanning -> {
                        Text("Scan the code on your Mac", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (hasCamera) "Menu-bar icon → Pair Phone…" else "Camera permission is needed to scan.",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                        )
                    }
                    Phase.Pairing -> {
                        CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(28.dp))
                        Text("Pairing…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    is Phase.Paired -> {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Palette.ok, modifier = Modifier.size(44.dp))
                        Text("Paired with ${phase.name}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    is Phase.Failed -> {
                        Text("Couldn't pair", color = Palette.bad, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${phase.message}\nMake sure both are on the same Wi-Fi, or the Mac is on this phone's hotspot.",
                            color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Palette.accent)) { Text("Try again") }
                    }
                }
            }
        }
    }
}
