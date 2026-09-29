package com.sketchref.app

import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage

@Composable
fun TracingScreen(
    reference: ReferenceItem,
    onBackToGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    // Studio Settings
    var opacity by remember { mutableFloatStateOf(0.55f) }
    var isPinned by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var isContourOnly by remember { mutableStateOf(false) }

    // Transformation Coordinates (Never lost or reset on Pin!)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }

    // Camera handle for Hardware Flashlight
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // 1. LAYER 1: Full-Speed Camera Feed of Desk & Paper
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }.also { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraInstance = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview
                            )
                        } catch (exc: Exception) {
                            exc.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. LAYER 2: High-Contrast Line Art Filter
        val colorMatrix = remember(isContourOnly) {
            if (isContourOnly) {
                ColorMatrix(
                    floatArrayOf(
                        -3f, -3f, -3f, 0f, 255f,
                        -3f, -3f, -3f, 0f, 255f,
                        -3f, -3f, -3f, 0f, 255f,
                        0f,  0f,  0f, 1f,   0f
                    )
                )
            } else null
        }

        // 3. LAYER 3: The Ghost Reference Image
        // When pinned, this layer stays at the exact scale, offset, and rotation with ZERO jump!
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = reference.imageUrl,
                contentDescription = reference.title,
                contentScale = ContentScale.Fit,
                colorFilter = colorMatrix?.let { ColorFilter.colorMatrix(it) },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        rotationZ = rotation
                        translationX = offset.x
                        translationY = offset.y
                        alpha = opacity
                    }
                    .then(
                        if (!isPinned) {
                            Modifier.border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(12.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
            )
        }

        // 4. LAYER 4: Touch Shield
        // When unpinned: 1-finger drag + 2-finger zoom/rotate
        // When pinned: Intercepts all touch events to protect line work from accidental touches
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isPinned) {
                    if (!isPinned) {
                        detectTransformGestures { _, pan, zoom, rotationChange ->
                            scale = (scale * zoom).coerceIn(0.2f, 8.0f)
                            rotation += rotationChange
                            offset += pan
                        }
                    } else {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
        )

        // 5. LAYER 5: Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBackToGallery,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.90f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Surface(
                color = if (isPinned) Color(0xFF059669).copy(alpha = 0.95f) else Color(0xFF2563EB).copy(alpha = 0.90f),
                shape = RoundedCornerShape(20.dp),
                border = if (isPinned) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34D399)) else null
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Default.Lock else Icons.Default.PanTool,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPinned) "PINNED TO PAPER (Draw Freely)" else "Position on Paper • Tap Pin",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // 6. LAYER 6: Bottom Studio Shelf
        Surface(
            color = Color(0xFF0F172A).copy(alpha = 0.96f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 10.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Opacity Slider (Disabled when pinned to prevent accidental adjustments)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = if (!isPinned) MaterialTheme.colorScheme.primary else Color(0xFF64748B),
                        modifier = Modifier.size(18.dp)
                    )
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        enabled = !isPinned,
                        valueRange = 0.05f..0.95f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(opacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (!isPinned) Color.White else Color(0xFF94A3B8),
                        modifier = Modifier.width(36.dp)
                    )
                }

                // Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Torch / Flashlight Toggle (Always active)
                    IconButton(
                        onClick = {
                            isTorchOn = !isTorchOn
                            cameraInstance?.cameraControl?.enableTorch(isTorchOn)
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (isTorchOn) Color(0xFFF59E0B) else Color(0xFF1E293B)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashlightOn,
                            contentDescription = "Torch",
                            tint = if (isTorchOn) Color.Black else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Secondary Editing Actions (Hide when pinned to keep the display clear)
                    AnimatedVisibility(visible = !isPinned) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Rotate 90 Degrees
                            IconButton(
                                onClick = { rotation = (rotation + 90f) % 360f },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E293B))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RotateRight,
                                    contentDescription = "Rotate 90",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Contour Line Filter
                            IconButton(
                                onClick = { isContourOnly = !isContourOnly },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (isContourOnly) MaterialTheme.colorScheme.primary else Color(0xFF1E293B)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Draw,
                                    contentDescription = "Outline",
                                    tint = if (isContourOnly) Color.White else Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Reset Position
                            IconButton(
                                onClick = {
                                    scale = 1f
                                    offset = Offset.Zero
                                    rotation = 0f
                                },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E293B))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // MAIN PIN / UNPIN BUTTON (Zero Jump Guarantee)
                    Button(
                        onClick = {
                            isPinned = !isPinned
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPinned) Color(0xFFDC2626) else MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.LockOpen else Icons.Default.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isPinned) "Unlock Position" else "Pin to Paper",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}