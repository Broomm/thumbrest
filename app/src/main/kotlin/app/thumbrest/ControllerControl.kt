package app.thumbrest

// A fixed diagram position reads and controls one original input channel.
// Glyphs stay with the position; snapshots and virtual input are not remapped.
internal data class ControllerControl(
    val inputButton: PadButton,
    val visual: ButtonVisual,
    val supported: Boolean,
    val mode: ButtonMode,
    val pressed: Boolean,
) {
    fun cycle(onCycle: ((PadButton) -> Unit)?) {
        if (supported) onCycle?.invoke(inputButton)
    }
}

internal fun ControllerStyle.control(
    diagramButton: PadButton,
    inverted: Boolean,
    device: PadDevice?,
    snapshot: PadSnapshot?,
): ControllerControl {
    val input = if (!inverted) diagramButton else when (diagramButton) {
        PadButton.A -> PadButton.B
        PadButton.B -> PadButton.A
        PadButton.X -> PadButton.Y
        PadButton.Y -> PadButton.X
        else -> diagramButton
    }
    return ControllerControl(
        inputButton = input,
        visual = visual(diagramButton),
        supported = device?.supports(input) != false,
        mode = snapshot?.mode(input) ?: ButtonMode.Default,
        pressed = (snapshot?.output ?: 0) and input.bit != 0,
    )
}
