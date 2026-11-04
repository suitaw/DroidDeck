package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun CursorOverlay(
    positionOf: () -> Offset, visible: Boolean, scale: Float,
    image: ImageBitmap? = null, hotX: Int = 0, hotY: Int = 0, imageScale: Float = 1f,
    clickCount: Int = 0, pressed: Boolean = false,
) {
    if (!visible) return
    // Shows that a click landed: the pointer dips while pressed and a ring spreads out from it.
    val ripple = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(clickCount) {
        if (clickCount > 0) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, androidx.compose.animation.core.tween(260))
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val position = positionOf()
        val spread = ripple.value
        if (spread < 1f) {
            val radius = (6f + 22f * spread) * scale
            val fade = 1f - spread
            drawCircle(Color.White.copy(alpha = 0.22f * fade), radius, position)
            drawCircle(Color.White.copy(alpha = 0.85f * fade), radius, position, style = Stroke(width = 1.5f * scale))
            drawCircle(Color(0xFF1B0730).copy(alpha = 0.5f * fade), radius + 1f * scale, position, style = Stroke(width = 0.75f * scale))
        }
        val dip = if (pressed) 0.82f else 1f
        if (image != null) {
            val drawn = imageScale * dip
            val w = (image.width * drawn).toInt().coerceAtLeast(1)
            val h = (image.height * drawn).toInt().coerceAtLeast(1)
            drawImage(
                image,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (position.x - hotX * drawn).toInt(), (position.y - hotY * drawn).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(w, h),
                filterQuality = FilterQuality.Low,
            )
            return@Canvas
        }
        val s = 11f * scale * dip
        val path = Path().apply {
            moveTo(position.x, position.y)
            lineTo(position.x, position.y + s * 1.45f)
            lineTo(position.x + s * 0.36f, position.y + s * 1.12f)
            lineTo(position.x + s * 0.62f, position.y + s * 1.62f)
            lineTo(position.x + s * 0.84f, position.y + s * 1.52f)
            lineTo(position.x + s * 0.58f, position.y + s * 1.02f)
            lineTo(position.x + s * 1.02f, position.y + s * 1.02f)
            close()
        }
        drawPath(path, Color.White)
        drawPath(path, Color(0xFF1B0730), style = Stroke(width = 1.5f * scale))
    }
}
