package app.thumbrest

internal enum class ButtonShape { Triangle, Circle, Cross, Square, Home }

internal data class ButtonVisual(
    val label: String,
    val description: String = label,
    val shape: ButtonShape? = null,
)

// Resolve the glyph source only; input identity and button modes keep using
// the original PadButton at the control's position.
internal fun faceGlyphButton(button: PadButton, inverted: Boolean): PadButton =
    if (!inverted) button else when (button) {
        PadButton.A -> PadButton.B
        PadButton.B -> PadButton.A
        PadButton.X -> PadButton.Y
        PadButton.Y -> PadButton.X
        else -> button
    }

// Presentation only: every style renders the same PadButton identities.
internal enum class ControllerStyle(val preferenceId: String, val label: String) {
    Xbox("xbox", "Xbox"),
    Switch("switch", "Switch"),
    PlayStation("playstation", "PlayStation");

    fun visual(button: PadButton): ButtonVisual = when (this) {
        Xbox -> when (button) {
            PadButton.L1 -> ButtonVisual("LB", "Left bumper")
            PadButton.R1 -> ButtonVisual("RB", "Right bumper")
            PadButton.L2 -> ButtonVisual("LT", "Left trigger")
            PadButton.R2 -> ButtonVisual("RT", "Right trigger")
            PadButton.L3 -> ButtonVisual("LS", "Left stick")
            PadButton.R3 -> ButtonVisual("RS", "Right stick")
            PadButton.Select -> ButtonVisual("View")
            PadButton.Start -> ButtonVisual("Menu")
            PadButton.Guide -> ButtonVisual("Home", shape = ButtonShape.Home)
            else -> ButtonVisual(button.label, button.description)
        }
        Switch -> when (button) {
            PadButton.A -> ButtonVisual("B")
            PadButton.B -> ButtonVisual("A")
            PadButton.X -> ButtonVisual("Y")
            PadButton.Y -> ButtonVisual("X")
            PadButton.L1 -> ButtonVisual("L", "Left shoulder")
            PadButton.R1 -> ButtonVisual("R", "Right shoulder")
            PadButton.L2 -> ButtonVisual("ZL", "Left trigger")
            PadButton.R2 -> ButtonVisual("ZR", "Right trigger")
            PadButton.Select -> ButtonVisual("−", "Minus")
            PadButton.Start -> ButtonVisual("+", "Plus")
            PadButton.Guide -> ButtonVisual("Home", shape = ButtonShape.Home)
            else -> ButtonVisual(button.label, button.description)
        }
        PlayStation -> when (button) {
            PadButton.A -> ButtonVisual("×", "Cross", ButtonShape.Cross)
            PadButton.B -> ButtonVisual("○", "Circle", ButtonShape.Circle)
            PadButton.X -> ButtonVisual("□", "Square", ButtonShape.Square)
            PadButton.Y -> ButtonVisual("△", "Triangle", ButtonShape.Triangle)
            PadButton.Select -> ButtonVisual("Share")
            PadButton.Start -> ButtonVisual("Options")
            PadButton.Guide -> ButtonVisual("PS", "PS button")
            else -> ButtonVisual(button.label, button.description)
        }
    }

    companion object {
        fun fromPreference(value: String?): ControllerStyle =
            entries.firstOrNull { it.preferenceId == value } ?: Xbox
    }
}
