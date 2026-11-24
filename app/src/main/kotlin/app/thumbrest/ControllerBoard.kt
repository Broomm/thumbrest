package app.thumbrest

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

private const val BoardWidth = 560f
private const val BoardHeight = 306f
private val ShellEdge = Color(0xFF537064)
private val Control = Color(0xFF293D34)
private val Idle = Color(0xFF739085)
private val Label = Color(0xFFECF6EF)
private val Dark = Color(0xFF14251F)

private data class Position(val button: PadButton, val x: Float, val y: Float, val width: Float = 34f) {
    val center = Offset(x, y)
}

private val positions = listOf(
    Position(PadButton.L2, 141f, 26f, 88f), Position(PadButton.R2, 419f, 26f, 88f),
    Position(PadButton.L1, 141f, 59f, 108f), Position(PadButton.R1, 419f, 59f, 108f),
    Position(PadButton.Up, 155f, 107f), Position(PadButton.Down, 155f, 174f),
    Position(PadButton.Left, 121f, 141f), Position(PadButton.Right, 189f, 141f),
    Position(PadButton.Y, 405f, 107f), Position(PadButton.A, 405f, 174f),
    Position(PadButton.X, 371f, 141f), Position(PadButton.B, 439f, 141f),
    Position(PadButton.Select, 228f, 121f, 63f), Position(PadButton.Start, 332f, 121f, 63f),
    Position(PadButton.Guide, 280f, 158f),
    Position(PadButton.L3, 218f, 205f, 47f), Position(PadButton.R3, 342f, 205f, 47f),
)

private val shell = Path().apply {
    moveTo(154f, 72f)
    cubicTo(105f, 72f, 78f, 94f, 61f, 133f)
    cubicTo(47f, 166f, 36f, 211f, 31f, 243f)
    cubicTo(26f, 274f, 44f, 287f, 66f, 285f)
    cubicTo(93f, 283f, 114f, 259f, 137f, 243f)
    cubicTo(151f, 233f, 201f, 245f, 220f, 247f)
    cubicTo(242f, 249f, 255f, 242f, 280f, 242f)
    cubicTo(305f, 242f, 318f, 249f, 340f, 247f)
    cubicTo(359f, 245f, 409f, 233f, 423f, 243f)
    cubicTo(446f, 259f, 467f, 283f, 494f, 285f)
    cubicTo(516f, 287f, 534f, 274f, 529f, 243f)
    cubicTo(524f, 211f, 513f, 166f, 499f, 133f)
    cubicTo(482f, 94f, 455f, 72f, 406f, 72f)
    close()
}

@Composable
internal fun ControllerBoard(
    device: PadDevice? = null,
    snapshot: PadSnapshot? = null,
    onCycle: ((PadButton) -> Unit)? = null,
    style: ControllerStyle = ControllerStyle.Xbox,
    invertFaceGlyphs: Boolean = false,
) {
    val handler = rememberUpdatedState(onCycle)
    val activeSession = snapshot?.status == 1
    val controls = positions.associate { position ->
        position.button to style.control(position.button, invertFaceGlyphs, device, snapshot)
    }
    val currentControls = rememberUpdatedState(controls)
    val accessibleDescription = if (device == null || snapshot == null) {
        "${style.label} gamepad diagram"
    } else {
        controls.values.filter { it.supported }
            .joinToString(separator = ", ", prefix = "${style.label} gamepad modes. ") {
                "${it.visual.description} ${it.mode.label}"
            }
    }

    Canvas(
        Modifier.fillMaxWidth().aspectRatio(BoardWidth / BoardHeight)
            .semantics { contentDescription = accessibleDescription }
            .pointerInput(device?.supported, activeSession, invertFaceGlyphs) {
                if (activeSession && device != null) {
                    detectTapGestures { tap ->
                        val point = Offset(tap.x * BoardWidth / size.width, tap.y * BoardHeight / size.height)
                        val bound = currentControls.value
                        val hit = positions.filter { bound.getValue(it.button).supported }
                            .filter {
                                kotlin.math.abs(point.x - it.x) <= it.width / 2f + 5f &&
                                    kotlin.math.abs(point.y - it.y) <= 18f
                            }
                            .minByOrNull { (point - it.center).getDistanceSquared() }
                        hit?.let { bound.getValue(it.button).cycle(handler.value) }
                    }
                }
            },
    ) {
        scale(size.width / BoardWidth, size.height / BoardHeight, Offset.Zero) {
            drawPath(shell, Brush.verticalGradient(listOf(Color(0xFF385247), Color(0xFF1B3028)),
                startY = 70f, endY = 285f))
            drawPath(shell, ShellEdge, style = Stroke(2f))
            drawCircle(Dark, radius = 54f, center = Offset(155f, 141f))
            drawCircle(Dark, radius = 54f, center = Offset(405f, 141f))
            for (position in positions) {
                val button = position.button
                val control = controls.getValue(button)
                val visual = control.visual
                val supported = control.supported
                val mode = control.mode
                val pressed = control.pressed
                val color = when (mode) {
                    ButtonMode.Default -> Idle
                    ButtonMode.Held -> Mint
                    ButtonMode.Turbo -> Lilac
                }
                val edge = if (supported) color else Color(0xFF4F645B)
                val fill = if (pressed && supported) {
                    if (mode == ButtonMode.Default) Label else color
                } else Control
                val label = if (pressed) Dark else if (supported) style.glyphColor(button) else Idle
                when (button) {
                    PadButton.L3, PadButton.R3 -> {
                        val axes = if (button == PadButton.L3) {
                            Offset((snapshot?.leftX ?: 0).toFloat(), (snapshot?.leftY ?: 0).toFloat())
                        } else {
                            Offset((snapshot?.rightX ?: 0).toFloat(), (snapshot?.rightY ?: 0).toFloat())
                        }
                        drawCircle(Dark, radius = 29f, center = position.center)
                        drawCircle(ShellEdge, radius = 29f, center = position.center, style = Stroke(1.5f))
                        val cap = position.center + axes * .010f
                        drawCircle(fill, radius = 20f, center = cap)
                        drawCircle(edge, radius = 20f, center = cap, style = Stroke(2f))
                        buttonGlyph(visual, cap, label, 13f)
                    }
                    PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2, PadButton.Select, PadButton.Start -> {
                        val height = if (position.width > 70f) 28f else 23f
                        val topLeft = position.center - Offset(position.width / 2f, height / 2f)
                        val size = Size(position.width, height)
                        drawRoundRect(fill, topLeft, size, CornerRadius(10f))
                        drawRoundRect(edge, topLeft, size, CornerRadius(10f), style = Stroke(1.7f))
                        buttonGlyph(visual, position.center, label, 14f)
                    }
                    else -> {
                        drawCircle(fill, radius = 16f, center = position.center)
                        drawCircle(edge, radius = 16f, center = position.center, style = Stroke(1.7f))
                        buttonGlyph(visual, position.center, label, 16f)
                    }
                }
            }
        }
    }
}

private fun ControllerStyle.glyphColor(button: PadButton): Color = when (this) {
    ControllerStyle.Xbox -> when (button) {
        PadButton.A -> Color(0xFF80D778)
        PadButton.B -> Color(0xFFF08080)
        PadButton.X -> Color(0xFF7EB5FF)
        PadButton.Y -> Color(0xFFF2D36C)
        else -> Label
    }
    ControllerStyle.PlayStation -> when (button) {
        PadButton.A -> Color(0xFF8DB8FF)
        PadButton.B -> Color(0xFFF09090)
        PadButton.X -> Color(0xFFF1A5D1)
        PadButton.Y -> Color(0xFF8EDCC6)
        else -> Label
    }
    ControllerStyle.Switch -> Label
}

private fun DrawScope.buttonGlyph(visual: ButtonVisual, center: Offset, color: Color, fontSize: Float) {
    val half = fontSize * .38f
    val stroke = Stroke(1.8f, cap = StrokeCap.Round)
    when (visual.shape) {
        null -> buttonLabel(visual.label, center, color, fontSize)
        ButtonShape.Circle -> drawCircle(color, half, center, style = stroke)
        ButtonShape.Square -> drawRoundRect(color, center - Offset(half, half),
            Size(half * 2f, half * 2f), CornerRadius(1f), style = stroke)
        ButtonShape.Cross -> {
            drawLine(color, center - Offset(half, half), center + Offset(half, half),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, center + Offset(-half, half), center + Offset(half, -half),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        ButtonShape.Triangle -> {
            val triangle = Path().apply {
                moveTo(center.x, center.y - half - 1f)
                lineTo(center.x + half + 1f, center.y + half)
                lineTo(center.x - half - 1f, center.y + half)
                close()
            }
            drawPath(triangle, color, style = stroke)
        }
        ButtonShape.Home -> {
            val home = Path().apply {
                moveTo(center.x - half - 1f, center.y - 1f)
                lineTo(center.x, center.y - half)
                lineTo(center.x + half + 1f, center.y - 1f)
                moveTo(center.x - half + 1f, center.y - 2f)
                lineTo(center.x - half + 1f, center.y + half)
                lineTo(center.x + half - 1f, center.y + half)
                lineTo(center.x + half - 1f, center.y - 2f)
            }
            drawPath(home, color, style = stroke)
        }
    }
}

private fun DrawScope.buttonLabel(text: String, center: Offset, color: Color, fontSize: Float) {
    drawIntoCanvas {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            textSize = fontSize
            this.color = color.toArgb()
        }
        it.nativeCanvas.drawText(text, center.x, center.y - (paint.ascent() + paint.descent()) / 2f, paint)
    }
}
