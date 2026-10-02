package com.sketchref.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

val Studio950 = Color(0xFF090D16)
val Studio900 = Color(0xFF0F172A)
val Studio850 = Color(0xFF141E33)
val Studio800 = Color(0xFF1E293B)
val Studio700 = Color(0xFF334155)
val AccentBlue = Color(0xFF3B82F6)
val PinterestRed = Color(0xFFE60023)
val AmberGold = Color(0xFFF59E0B)
val EmeraldGreen = Color(0xFF10B981)

data class RefItem(
    val id: String,
    val title: String,
    val imageUrl: String,
    val category: String = "Web",
    val lighting: String = "Dynamic"
)

@Composable
fun TracingScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var activeTab by remember { mutableStateOf("tracing") }
    var serverHost by remember { mutableStateOf("sketchref-ai.onrender.com") }
    var activeSessionId by remember { mutableStateOf("default") }
    var activeRefImageUrl by remember { mutableStateOf("") }

    // Live transformation states
    var ghostOpacity by remember { mutableFloatStateOf(0.5f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var isMirrored by remember { mutableStateOf(false) }
    var isPinned by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var filterMode by remember { mutableIntStateOf(0) } // 0: Normal, 1: Grayscale, 2: High-Contrast Stencil

    val pinnedList = remember { mutableStateListOf<RefItem>() }
    var isConnected by remember { mutableStateOf(false) }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            activeRefImageUrl = it.toString()
            activeTab = "tracing"
        }
    }

    fun connectToSession(host: String, session: String) {
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.close(1000, "Reconnecting")

        val client = OkHttpClient.Builder()
            .pingInterval(10, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val cleanHost = host.replace("https://", "").replace("http://", "").trimEnd('/')
        val isLocal = cleanHost.contains("localhost") ||
                cleanHost.startsWith("192.") ||
                cleanHost.startsWith("10.") ||
                cleanHost.contains(":8000")

        val protocol = if (isLocal) "ws://" else "wss://"
        val wsUrl = "$protocol$cleanHost/ws/session/$session"

        Log.d("SketchRef", "Connecting WebSocket to: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        var newSocket: WebSocket? = null

        newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                mainHandler.post {
                    if (webSocket === ws) {
                        isConnected = true
                    }
                }
                val initMsg = JSONObject().apply {
                    put("action", "PHONE_CONNECTED")
                    put("type", "PHONE_CONNECTED")
                }
                ws.send(initMsg.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val action = json.optString("action",
                        json.optString("type",
                            json.optString("event",
                                json.optString("command", "")))).uppercase()

                    // Unpack nested payloads from Web clients
                    val target = json.optJSONObject("payload")
                        ?: json.optJSONObject("data")
                        ?: json.optJSONObject("transform")
                        ?: json.optJSONObject("position")
                        ?: json.optJSONObject("state")
                        ?: json

                    when {
                        action.contains("SWAP") || (action.contains("IMAGE") && !action.contains("TRANSFORM")) -> {
                            val newUrl = target.optString("imageUrl",
                                target.optString("url",
                                    target.optString("image", "")))
                            if (newUrl.isNotEmpty()) {
                                mainHandler.post { activeRefImageUrl = newUrl }
                            }
                        }

                        action.contains("OPACITY") -> {
                            val rawVal = target.optDouble("value",
                                target.optDouble("opacity",
                                    target.optDouble("ghostOpacity", -1.0)))
                            if (rawVal >= 0.0) {
                                val normalized = if (rawVal > 1.0) (rawVal / 100.0).toFloat() else rawVal.toFloat()
                                mainHandler.post {
                                    ghostOpacity = normalized.coerceIn(0.05f, 1.0f)
                                }
                            }
                        }

                        // Robust parsing for PC Move/Drag/Transform/Pan events
                        action.contains("TRANSFORM") || action.contains("MOVE") || action.contains("PAN") || action.contains("DRAG") || action.contains("POSITION") -> {
                            mainHandler.post {
                                if (target.has("scale") || target.has("zoom")) {
                                    scale = target.optDouble("scale", target.optDouble("zoom", scale.toDouble())).toFloat().coerceIn(0.2f, 5.0f)
                                }
                                if (target.has("rotation") || target.has("rotationAngle") || target.has("angle")) {
                                    rotationAngle = target.optDouble("rotation", target.optDouble("rotationAngle", target.optDouble("angle", rotationAngle.toDouble()))).toFloat()
                                }

                                val hasX = target.has("x") || target.has("offsetX") || target.has("translationX") || target.has("left") || target.has("posX")
                                val hasY = target.has("y") || target.has("offsetY") || target.has("translationY") || target.has("top") || target.has("posY")

                                if (hasX || hasY) {
                                    val x = target.optDouble("x", target.optDouble("offsetX", target.optDouble("translationX", target.optDouble("left", target.optDouble("posX", offset.x.toDouble())))))
                                    val y = target.optDouble("y", target.optDouble("offsetY", target.optDouble("translationY", target.optDouble("top", target.optDouble("posY", offset.y.toDouble())))))
                                    offset = Offset(x.toFloat(), y.toFloat())
                                }

                                if (target.has("opacity")) {
                                    val rawOp = target.optDouble("opacity", ghostOpacity.toDouble())
                                    val normOp = if (rawOp > 1.0) (rawOp / 100.0).toFloat() else rawOp.toFloat()
                                    ghostOpacity = normOp.coerceIn(0.05f, 1.0f)
                                }
                            }
                        }

                        action.contains("ROTATE") -> {
                            mainHandler.post { rotationAngle = (rotationAngle + 90f) % 360f }
                        }

                        action.contains("FLIP") || action.contains("MIRROR") -> {
                            mainHandler.post { isMirrored = target.optBoolean("mirrored", target.optBoolean("isMirrored", !isMirrored)) }
                        }

                        action.contains("LOCK") || action.contains("PIN") -> {
                            mainHandler.post { isPinned = target.optBoolean("locked", target.optBoolean("isPinned", !isPinned)) }
                        }

                        action.contains("RESET") -> {
                            mainHandler.post {
                                scale = 1f
                                rotationAngle = 0f
                                offset = Offset.Zero
                            }
                        }

                        action.contains("TORCH") || action.contains("FLASHLIGHT") -> {
                            mainHandler.post { isTorchOn = target.optBoolean("enabled", !isTorchOn) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SketchRefWS", "Error: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (webSocket === ws) isConnected = false
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    if (webSocket === ws) isConnected = false
                }
                coroutineScope.launch {
                    delay(3000)
                    if (webSocket === null || webSocket === ws) {
                        connectToSession(serverHost, activeSessionId)
                    }
                }
            }
        })

        webSocket = newSocket
    }

    LaunchedEffect(Unit) {
        connectToSession(serverHost, activeSessionId)
    }

    Scaffold(
        containerColor = Studio950,
        topBar = {
            Column(modifier = Modifier.background(Studio900)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_logo),
                            contentDescription = "SketchRef Logo",
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("SketchRef AI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(if (isConnected) EmeraldGreen else Color(0xFF38BDF8), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    if (isConnected) "PC LINKED" else "STANDALONE",
                                    color = if (isConnected) EmeraldGreen else Color(0xFF94A3B8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier
                                .background(Studio800, RoundedCornerShape(10.dp))
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(18.dp))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { activeTab = "qr" },
                            colors = ButtonDefaults.buttonColors(containerColor = Studio800),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan PC", tint = AccentBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pair PC", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .background(Studio950, RoundedCornerShape(12.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TabButton("Tracer", activeTab == "tracing", EmeraldGreen) { activeTab = "tracing" }
                    TabButton("Web", activeTab == "web", AccentBlue) { activeTab = "web" }
                    TabButton("Pinterest", activeTab == "pinterest", PinterestRed) { activeTab = "pinterest" }
                    TabButton("AI Studio", activeTab == "ai", AmberGold) { activeTab = "ai" }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (activeTab) {
                "tracing" -> {
                    if (hasCameraPermission) {
                        LiveTracingCameraView(
                            activeRefImageUrl = activeRefImageUrl,
                            isConnected = isConnected,
                            webSocket = webSocket,
                            ghostOpacity = ghostOpacity,
                            onOpacityChange = { ghostOpacity = it },
                            scale = scale,
                            onScaleChange = { scale = it },
                            offset = offset,
                            onOffsetChange = { offset = it },
                            rotationAngle = rotationAngle,
                            onRotationChange = { rotationAngle = it },
                            isMirrored = isMirrored,
                            onMirrorChange = { isMirrored = it },
                            isPinned = isPinned,
                            onPinnedChange = { isPinned = it },
                            isTorchOn = isTorchOn,
                            onTorchChange = { isTorchOn = it },
                            filterMode = filterMode,
                            onFilterModeChange = { filterMode = it },
                            onOpenGallery = { galleryLauncher.launch("image/*") },
                            onNavigateToTab = { activeTab = it }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Grant Camera Permission")
                            }
                        }
                    }
                }
                "web" -> WebSearchView(
                    host = serverHost,
                    onSelectImage = { url ->
                        activeRefImageUrl = url
                        activeTab = "tracing"
                        webSocket?.send(JSONObject().apply {
                            put("action", "SWAP_REFERENCE")
                            put("type", "SWAP_REFERENCE")
                            put("imageUrl", url)
                            put("url", url)
                        }.toString())
                    },
                    onPinImage = { item -> pinnedList.add(item) }
                )
                "pinterest" -> PinterestView(
                    host = serverHost,
                    onSelectImage = { url ->
                        activeRefImageUrl = url
                        activeTab = "tracing"
                        webSocket?.send(JSONObject().apply {
                            put("action", "SWAP_REFERENCE")
                            put("type", "SWAP_REFERENCE")
                            put("imageUrl", url)
                            put("url", url)
                        }.toString())
                    }
                )
                "ai" -> AiStudioView(
                    host = serverHost,
                    onSelectImage = { url ->
                        activeRefImageUrl = url
                        activeTab = "tracing"
                        webSocket?.send(JSONObject().apply {
                            put("action", "SWAP_REFERENCE")
                            put("type", "SWAP_REFERENCE")
                            put("imageUrl", url)
                            put("url", url)
                        }.toString())
                    }
                )
                "qr" -> QrCameraScannerView(
                    onQrCodeScanned = { scannedUrl ->
                        try {
                            val uri = Uri.parse(scannedUrl)
                            val authority = uri.authority ?: uri.host ?: serverHost
                            val id = uri.getQueryParameter("id") ?: "default"
                            serverHost = authority
                            activeSessionId = id
                            connectToSession(authority, id)
                            activeTab = "tracing"
                        } catch (e: Exception) {
                            activeTab = "tracing"
                        }
                    },
                    onClose = { activeTab = "tracing" }
                )
            }
        }
    }
}

@Composable
fun TabButton(title: String, isSelected: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) activeColor else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = if (isSelected) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// -------------------------------------------------------------
// LIVE CAMERA TRACER SCREEN (SMOOTH ACCUMULATIVE GESTURES)
// -------------------------------------------------------------
@Composable
fun LiveTracingCameraView(
    activeRefImageUrl: String,
    isConnected: Boolean,
    webSocket: WebSocket?,
    ghostOpacity: Float,
    onOpacityChange: (Float) -> Unit,
    scale: Float,
    onScaleChange: (Float) -> Unit,
    offset: Offset,
    onOffsetChange: (Offset) -> Unit,
    rotationAngle: Float,
    onRotationChange: (Float) -> Unit,
    isMirrored: Boolean,
    onMirrorChange: (Boolean) -> Unit,
    isPinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    isTorchOn: Boolean,
    onTorchChange: (Boolean) -> Unit,
    filterMode: Int,
    onFilterModeChange: (Int) -> Unit,
    onOpenGallery: () -> Unit,
    onNavigateToTab: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    val liveWebSocket by rememberUpdatedState(webSocket)
    val liveIsConnected by rememberUpdatedState(isConnected)
    val isProcessing = remember { AtomicBoolean(false) }
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }

    // Dynamic State Getters to prevent closure freeze in PointerInput
    val currentScale by rememberUpdatedState(scale)
    val currentRotation by rememberUpdatedState(rotationAngle)
    val currentOffset by rememberUpdatedState(offset)
    val currentOpacity by rememberUpdatedState(ghostOpacity)
    val currentPinned by rememberUpdatedState(isPinned)

    LaunchedEffect(isTorchOn) {
        try {
            cameraInstance?.cameraControl?.enableTorch(isTorchOn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                val backgroundExecutor = Executors.newSingleThreadExecutor()

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .setTargetResolution(Size(360, 480))
                        .build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(360, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    var lastStreamTime = 0L
                    imageAnalysis.setAnalyzer(backgroundExecutor) { imageProxy ->
                        if (!isProcessing.compareAndSet(false, true)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        try {
                            val now = System.currentTimeMillis()
                            val ws = liveWebSocket
                            val connected = liveIsConnected

                            if (now - lastStreamTime > 80 && connected && ws != null && ws.queueSize() == 0L) {
                                lastStreamTime = now
                                val rawBitmap = imageProxy.toBitmap()
                                val rotation = imageProxy.imageInfo.rotationDegrees

                                val targetWidth = 360
                                val targetHeight = (targetWidth * rawBitmap.height) / rawBitmap.width

                                val matrix = Matrix().apply {
                                    if (rotation != 0) postRotate(rotation.toFloat())
                                    postScale(targetWidth.toFloat() / rawBitmap.width, targetHeight.toFloat() / rawBitmap.height)
                                }

                                val finalBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                                rawBitmap.recycle()

                                val stream = ByteArrayOutputStream(8192)
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 35, stream)
                                finalBitmap.recycle()

                                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                                val dataUri = "data:image/jpeg;base64,$base64"

                                val frameMsg = JSONObject().apply {
                                    put("action", "CAMERA_FRAME")
                                    put("type", "CAMERA_FRAME")
                                    put("frame", dataUri)
                                    put("image", dataUri)
                                }
                                ws.send(frameMsg.toString())
                            }
                        } catch (e: Exception) {
                            Log.e("SketchRef", "Analyzer error: ${e.message}")
                        } finally {
                            imageProxy.close()
                            isProcessing.set(false)
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraInstance = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, executor)

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        if (activeRefImageUrl.isNotEmpty()) {
            val colorFilter = when (filterMode) {
                1 -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                2 -> ColorFilter.colorMatrix(
                    ColorMatrix(
                        floatArrayOf(
                            3.5f, 0f, 0f, 0f, -180f,
                            0f, 3.5f, 0f, 0f, -180f,
                            0f, 0f, 3.5f, 0f, -180f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
                else -> null
            }

            AsyncImage(
                model = activeRefImageUrl,
                contentDescription = "Active Reference",
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = if (isMirrored) -scale else scale,
                        scaleY = scale,
                        rotationZ = rotationAngle,
                        translationX = offset.x,
                        translationY = offset.y,
                        alpha = ghostOpacity
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            if (currentPinned) return@detectTransformGestures

                            // Smooth accumulation using dynamic state
                            val newScale = (currentScale * zoom).coerceIn(0.2f, 5.0f)
                            val newRotation = currentRotation + rotation
                            val newOffset = Offset(currentOffset.x + pan.x, currentOffset.y + pan.y)

                            onScaleChange(newScale)
                            onRotationChange(newRotation)
                            onOffsetChange(newOffset)

                            val ws = liveWebSocket
                            if (liveIsConnected && ws != null) {
                                ws.send(JSONObject().apply {
                                    put("action", "SYNC_TRANSFORM")
                                    put("type", "SYNC_TRANSFORM")
                                    put("scale", newScale.toDouble())
                                    put("rotation", newRotation.toDouble())
                                    put("x", newOffset.x.toDouble())
                                    put("y", newOffset.y.toDouble())
                                    put("offsetX", newOffset.x.toDouble())
                                    put("offsetY", newOffset.y.toDouble())
                                    put("opacity", currentOpacity.toDouble())
                                }.toString())
                            }
                        }
                    }
            )
        } else {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Studio900.copy(alpha = 0.92f)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Draw, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Live Camera Ready", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (isConnected) "Broadcasting feed to PC" else "Choose reference to trace",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onOpenGallery,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pick from Phone Gallery", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { onNavigateToTab("web") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.LightGray)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search Web References", fontSize = 12.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (isConnected) EmeraldGreen else Color(0xFF38BDF8), CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "STREAMING TO PC" else "STANDALONE CAMERA",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeRefImageUrl.isNotEmpty()) {
                    // Filter Mode (Normal -> Grayscale -> Stencil Outline)
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onFilterModeChange((filterMode + 1) % 3)
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                when (filterMode) {
                                    1 -> Color(0xFF64748B)
                                    2 -> EmeraldGreen
                                    else -> Studio900.copy(alpha = 0.85f)
                                },
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Icon(
                            imageVector = when (filterMode) {
                                2 -> Icons.Default.FilterBAndW
                                1 -> Icons.Default.Contrast
                                else -> Icons.Default.ColorLens
                            },
                            contentDescription = "Filter Mode",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val next = !isMirrored
                            onMirrorChange(next)
                            val ws = liveWebSocket
                            if (liveIsConnected && ws != null) {
                                ws.send(JSONObject().apply {
                                    put("action", "FLIP")
                                    put("type", "FLIP")
                                    put("mirrored", next)
                                }.toString())
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(if (isMirrored) AccentBlue else Studio900.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Default.Flip, contentDescription = "Mirror", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }

                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val next = !isTorchOn
                        onTorchChange(next)
                        val ws = liveWebSocket
                        if (liveIsConnected && ws != null) {
                            ws.send(JSONObject().apply {
                                put("action", "TORCH_STATUS")
                                put("type", "TORCH_STATUS")
                                put("enabled", next)
                            }.toString())
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .background(if (isTorchOn) AmberGold else Studio900.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        if (isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                        contentDescription = "Torch",
                        tint = if (isTorchOn) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (activeRefImageUrl.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Studio950.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacity", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(12.dp))
                    Slider(
                        value = ghostOpacity,
                        onValueChange = {
                            onOpacityChange(it)
                            val ws = liveWebSocket
                            if (liveIsConnected && ws != null) {
                                ws.send(JSONObject().apply {
                                    put("action", "SET_OPACITY")
                                    put("type", "SET_OPACITY")
                                    put("value", it.toDouble())
                                    put("opacity", it.toDouble())
                                }.toString())
                            }
                        },
                        valueRange = 0.05f..1.0f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = AccentBlue,
                            activeTrackColor = AccentBlue
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${(ghostOpacity * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenGallery,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Swap", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val next = !isPinned
                            onPinnedChange(next)
                            val ws = liveWebSocket
                            if (liveIsConnected && ws != null) {
                                ws.send(JSONObject().apply {
                                    put("action", "LOCK_POSITION")
                                    put("type", "LOCK_POSITION")
                                    put("locked", next)
                                }.toString())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isPinned) Color(0xFFEF4444) else AccentBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(if (isPinned) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPinned) "Unlock" else "Pin to Paper", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// WEB ENGINE SEARCH VIEW (ACCURATE OUTLINE KEYWORDS)
// -------------------------------------------------------------
@Composable
fun WebSearchView(host: String, onSelectImage: (String) -> Unit, onPinImage: (RefItem) -> Unit) {
    var searchQuery by remember { mutableStateOf("batman") }
    var selectedFilter by remember { mutableStateOf("outline") }
    val results = remember { mutableStateListOf<RefItem>() }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun executeSearch() {
        isLoading = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val isLocal = host.contains("localhost") || host.startsWith("192.") || host.contains(":8000")
                val scheme = if (isLocal) "http://" else "https://"

                // Clean keywords that reliably return black-and-white tracing references
                val effectiveQuery = when (selectedFilter) {
                    "outline" -> "${searchQuery.trim()} coloring page line art outline"
                    "sketch" -> "${searchQuery.trim()} pencil sketch black and white drawing"
                    "stencil" -> "${searchQuery.trim()} tattoo stencil clean line drawing"
                    else -> searchQuery.trim()
                }

                val encodedQuery = URLEncoder.encode(effectiveQuery, "UTF-8")
                val req = Request.Builder().url("${scheme}$host/api/search?q=$encodedQuery").build()
                val res = client.newCall(req).execute()

                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("results") ?: json.optJSONArray("images") ?: json.optJSONArray("data")
                    withContext(Dispatchers.Main) {
                        results.clear()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                results.add(
                                    RefItem(
                                        id = obj.optString("id", i.toString()),
                                        title = obj.optString("title", "Reference"),
                                        imageUrl = obj.optString("thumbnailUrl", obj.optString("imageUrl", obj.optString("url", "")))
                                    )
                                )
                            }
                        }
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(selectedFilter) { executeSearch() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search character or subject...", color = Color.Gray) },
            trailingIcon = {
                IconButton(onClick = { executeSearch() }) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = AccentBlue)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Studio900,
                unfocusedContainerColor = Studio900,
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = Studio700,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedFilter == "outline",
                    onClick = { selectedFilter = "outline" },
                    label = { Text("Coloring Outline", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBlue,
                        selectedLabelColor = Color.White
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == "sketch",
                    onClick = { selectedFilter = "sketch" },
                    label = { Text("Pencil Sketch", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBlue,
                        selectedLabelColor = Color.White
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == "stencil",
                    onClick = { selectedFilter = "stencil" },
                    label = { Text("Stencil Art", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBlue,
                        selectedLabelColor = Color.White
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == "raw",
                    onClick = { selectedFilter = "raw" },
                    label = { Text("Raw Search", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Studio800,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentBlue)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(results) { item ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Studio900),
                        modifier = Modifier.fillMaxWidth().clickable { onSelectImage(item.imageUrl) }
                    ) {
                        Column {
                            AsyncImage(
                                model = item.imageUrl,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(160.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.title, color = Color.White, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onPinImage(item) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = AmberGold, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// PINTEREST ART BOARD VIEW
// -------------------------------------------------------------
@Composable
fun PinterestView(host: String, onSelectImage: (String) -> Unit) {
    var query by remember { mutableStateOf("batman line art sketch") }
    val pins = remember { mutableStateListOf<RefItem>() }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun fetchPins() {
        isLoading = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val isLocal = host.contains("localhost") || host.startsWith("192.") || host.contains(":8000")
                val scheme = if (isLocal) "http://" else "https://"

                val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
                val req = Request.Builder().url("${scheme}$host/api/pinterest?q=$encodedQuery").build()
                val res = client.newCall(req).execute()

                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("results") ?: json.optJSONArray("pins") ?: json.optJSONArray("data")
                    withContext(Dispatchers.Main) {
                        pins.clear()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                pins.add(
                                    RefItem(
                                        id = obj.optString("id", i.toString()),
                                        title = obj.optString("title", "Pin"),
                                        imageUrl = obj.optString("thumbnailUrl", obj.optString("imageUrl", obj.optString("url", "")))
                                    )
                                )
                            }
                        }
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(Unit) { fetchPins() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search Pinterest sketches...", color = Color.Gray) },
            trailingIcon = {
                IconButton(onClick = { fetchPins() }) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = PinterestRed)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Studio900,
                unfocusedContainerColor = Studio900,
                focusedBorderColor = PinterestRed,
                unfocusedBorderColor = Studio700,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PinterestRed)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(pins) { pin ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Studio900),
                        modifier = Modifier.fillMaxWidth().clickable { onSelectImage(pin.imageUrl) }
                    ) {
                        AsyncImage(
                            model = pin.imageUrl,
                            contentDescription = pin.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(180.dp)
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// AI ART GENERATOR (STRICT MONOCHROME SKETCH PROMPT INJECTION)
// -------------------------------------------------------------
@Composable
fun AiStudioView(host: String, onSelectImage: (String) -> Unit) {
    var prompt by remember { mutableStateOf("batman portrait") }
    var selectedStyle by remember { mutableStateOf("Clean Pencil Sketch") }
    var generatedUrl by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val styles = listOf(
        "Clean Pencil Sketch",
        "Charcoal Drawing Study",
        "Ink Outline Stencil",
        "Anatomy Planar Study"
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI Art Reference Generator", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Generates clean black-and-white drawings ready for tracing", color = Color.Gray, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = { Text("E.g. Batman portrait, anime hand, dragon...", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Studio900,
                unfocusedContainerColor = Studio900,
                focusedBorderColor = AmberGold,
                unfocusedBorderColor = Studio700,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text("Style Preset:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            styles.forEach { style ->
                item {
                    FilterChip(
                        selected = selectedStyle == style,
                        onClick = { selectedStyle = style },
                        label = { Text(style, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberGold,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = {
                isGenerating = true
                errorMessage = null
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val client = OkHttpClient.Builder()
                            .connectTimeout(45, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build()

                        val isLocal = host.contains("localhost") || host.startsWith("192.") || host.contains(":8000")
                        val scheme = if (isLocal) "http://" else "https://"

                        // Injects strict graphite sketch tokens directly into the prompt string
                        val sketchPrompt = "${prompt.trim()}, ${selectedStyle.lowercase()}, clean monochrome pencil sketch on pure white paper, sharp contours, coloring page outline stencil, no colors, no shading artifacts, high contrast drawing"

                        val encPrompt = URLEncoder.encode(sketchPrompt, "UTF-8")
                        val encStyle = URLEncoder.encode(selectedStyle, "UTF-8")

                        val req = Request.Builder()
                            .url("${scheme}$host/api/generate-ai?prompt=$encPrompt&style=$encStyle")
                            .build()

                        val res = client.newCall(req).execute()
                        if (res.isSuccessful) {
                            val body = res.body?.string() ?: ""
                            val json = JSONObject(body)
                            val url = json.optString("imageUrl", json.optString("image", json.optString("url", "")))
                            withContext(Dispatchers.Main) {
                                if (url.isNotEmpty()) {
                                    generatedUrl = url
                                } else {
                                    errorMessage = "Backend did not return an image URL."
                                }
                                isGenerating = false
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                errorMessage = "Backend error: ${res.code}"
                                isGenerating = false
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            errorMessage = e.message ?: "Failed to generate."
                            isGenerating = false
                        }
                    }
                }
            },
            enabled = !isGenerating,
            colors = ButtonDefaults.buttonColors(containerColor = AmberGold),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isGenerating) "Synthesizing Line Study..." else "Generate Study", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = Color(0xFFEF4444), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isGenerating) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AmberGold)
            }
        }

        generatedUrl?.let { url ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Studio900),
                modifier = Modifier.fillMaxWidth().clickable { onSelectImage(url) }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                    AsyncImage(
                        model = url,
                        contentDescription = "AI Study",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Tap to Beam to Tracing Screen", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// -------------------------------------------------------------
// QR CODE SCANNER (OPTIONAL PC PAIRING)
// -------------------------------------------------------------
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrCameraScannerView(
    onQrCodeScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                val scanner = BarcodeScanning.getClient()

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetResolution(Size(360, 480))
                        .build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(360, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(executor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val img = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            scanner.process(img)
                                .addOnSuccessListener { barcodes: List<Barcode> ->
                                    for (barcode in barcodes) {
                                        val raw: String? = barcode.rawValue
                                        if (raw != null) {
                                            onQrCodeScanned(raw)
                                            scanner.close()
                                            return@addOnSuccessListener
                                        }
                                    }
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (e: Exception) {}
                }, executor)

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onClose, modifier = Modifier.background(Studio900, CircleShape)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .size(240.dp)
                    .background(Color.Transparent, RoundedCornerShape(16.dp))
            ) {
                Text(
                    "Point at QR Code on PC Studio",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}