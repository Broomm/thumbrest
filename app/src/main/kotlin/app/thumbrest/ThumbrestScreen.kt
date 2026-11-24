package app.thumbrest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val Mint = Color(0xFFB5F5C7)
internal val Lilac = Color(0xFFD6BCFF)
private val Line = Color(0xFF334640)
private val PanelShape = RoundedCornerShape(24.dp)
private val Colors = darkColorScheme(
    background = Color(0xFF0E1513),
    surface = Color(0xFF182420),
    onBackground = Color(0xFFF2F8F4),
    onSurface = Color(0xFFF2F8F4),
    onSurfaceVariant = Color(0xFFAABFB4),
    outline = Line,
    primary = Mint,
    onPrimary = Color(0xFF11261B),
    secondary = Lilac,
    errorContainer = Color(0xFF412621),
    onErrorContainer = Color(0xFFFFDFD6),
)

@Composable
internal fun ThumbrestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

@Composable
internal fun ThumbrestScreen(
    state: SessionState,
    controllerStyle: ControllerStyle,
    onStyleChange: (ControllerStyle) -> Unit,
    invertFaceGlyphs: Boolean,
    onInvertFaceGlyphsChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onSelect: (PadDevice) -> Unit,
    onCycle: (PadButton) -> Unit,
    onRate: (Int) -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Thumbrest",
                        Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 32.sp,
                        letterSpacing = (-1.1).sp,
                    )
                    if (state.connected || state.connecting) {
                        TextButton(onClick = onStop, enabled = !state.stopping) { Text("Stop") }
                    }
                }

                (state.error ?: state.scanError)?.let { error ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(error, Modifier.padding(16.dp))
                    }
                }

                val pad = state.selected
                val snapshot = state.snapshot
                when {
                    state.stopping -> Pending("Releasing controller…")
                    state.starting -> Pending("Opening controller…")
                    pad != null && snapshot != null -> LiveController(
                        pad, snapshot, controllerStyle, invertFaceGlyphs, state.rate, onCycle, onRate, onClear,
                    )
                    state.connecting -> Pending("Waiting for Magisk…")
                    state.connected -> DevicePicker(state.devices, state.scanning, state.scanError != null, onSelect)
                    else -> CardPanel {
                        ControllerBoard(style = controllerStyle, invertFaceGlyphs = invertFaceGlyphs)
                        Text("Hold buttons down or fire repeatedly without keeping your thumb on the controller.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                            Text("Resume")
                        }
                        Text("Requires Magisk root", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                CardPanel {
                    Text("Button style", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ControllerStyle.entries.forEach { style ->
                            FilterChip(
                                selected = style == controllerStyle,
                                onClick = { onStyleChange(style) },
                                label = { Text(style.label) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = invertFaceGlyphs,
                            role = Role.Switch,
                            onValueChange = onInvertFaceGlyphsChange,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Invert face glyphs")
                            Text("Swap A/B and X/Y, or their PlayStation symbols.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = invertFaceGlyphs, onCheckedChange = null)
                    }
                    Text("Changes the diagram’s labels and glyphs.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CardPanel(content: @Composable () -> Unit) {
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, PanelShape),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun DevicePicker(
    devices: List<PadDevice>,
    scanning: Boolean,
    scanFailed: Boolean,
    onSelect: (PadDevice) -> Unit,
) {
    CardPanel {
        Text("Connected controllers", style = MaterialTheme.typography.titleMedium)
        if (devices.isEmpty()) {
            if (scanning) Pending("Looking for controllers…")
            else if (!scanFailed) Text("Connect a Bluetooth or USB controller, or use your handheld’s built-in controls.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        devices.forEach { device ->
            val subtitle = if (devices.count { it.name == device.name } > 1) "Input ${device.node}" else null
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF23332C))
                    .clickable(onClickLabel = "Use controller") { onSelect(device) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("→", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LiveController(
    device: PadDevice,
    snapshot: PadSnapshot,
    controllerStyle: ControllerStyle,
    invertFaceGlyphs: Boolean,
    rate: Int,
    onCycle: (PadButton) -> Unit,
    onRate: (Int) -> Unit,
    onClear: () -> Unit,
) {
    CardPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(device.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Ready", color = Mint,
                style = MaterialTheme.typography.labelLarge)
        }
        ControllerBoard(device, snapshot, onCycle, controllerStyle, invertFaceGlyphs)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ModeLegend("Default", Color(0xFF859C90))
            ModeLegend("Held", Mint)
            ModeLegend("Turbo", Lilac)
        }
        Text("Tap a button to cycle Default → Held → Turbo → Default. Changes take effect immediately.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClear, enabled = snapshot.held != 0 || snapshot.turbo != 0) {
            Text("All default")
        }
    }

    CardPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Turbo rate", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("$rate presses/sec", color = Lilac, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = rate.toFloat(), onValueChange = { onRate(it.roundToInt()) },
            valueRange = 1f..60f, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ModeLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.size(7.dp).background(color, CircleShape))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Pending(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label)
    }
}
