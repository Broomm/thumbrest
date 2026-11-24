package app.thumbrest

private val testPad = PadDevice(9, "test-node", (1 shl PadButton.entries.size) - 1, "test-pad", "Gamepad")
private val facePositions = listOf(PadButton.A, PadButton.B, PadButton.X, PadButton.Y)

private fun snapshot(physical: Int = 0, held: Int = 0, turbo: Int = 0) =
    PadSnapshot(1, physical, physical or held or turbo, held, turbo, 0, 0, 0, 0)

private fun physicalPressUsesFixedGlyphPositions() {
    // The owner's south button reports B and previously lit the east control.
    val frame = snapshot(physical = PadButton.B.bit or PadButton.Y.bit)
    for (style in ControllerStyle.entries) {
        val normal = facePositions.associateWith { style.control(it, false, testPad, frame) }
        val inverted = facePositions.associateWith { style.control(it, true, testPad, frame) }
        check(normal.filterValues { it.pressed }.keys == setOf(PadButton.B, PadButton.Y))
        check(inverted.filterValues { it.pressed }.keys == setOf(PadButton.A, PadButton.X)) {
            "Reported B/Y must highlight the south/west positions when inverted"
        }
        check(facePositions.all { normal.getValue(it).visual == inverted.getValue(it).visual }) {
            "Inverting input associations must leave every glyph at its normal position"
        }
    }
    val south = ControllerStyle.PlayStation.control(PadButton.A, true, testPad, frame)
    val east = ControllerStyle.PlayStation.control(PadButton.B, true, testPad, frame)
    check(south.visual.shape == ButtonShape.Cross && south.pressed)
    check(east.visual.shape == ButtonShape.Circle && !east.pressed)
}

private fun screenTapsControlTheHighlightedOriginalInput() {
    val frame = snapshot(held = PadButton.B.bit, turbo = PadButton.Y.bit)
    val south = ControllerStyle.PlayStation.control(PadButton.A, true, testPad, frame)
    val west = ControllerStyle.PlayStation.control(PadButton.X, true, testPad, frame)
    check(south.mode == ButtonMode.Held && west.mode == ButtonMode.Turbo)
    val commands = mutableListOf<PadButton>()
    south.cycle { commands += it }
    west.cycle { commands += it }
    check(commands.map { it.bit } == listOf(frame.held, frame.turbo)) {
        "Taps must cycle the original held/turbo input channels, not their glyph identities"
    }
    // Disabling inversion moves the display association back, without moving
    // the engine's existing B hold or Y turbo to another input channel.
    val normalSouth = ControllerStyle.PlayStation.control(PadButton.A, false, testPad, frame)
    val normalEast = ControllerStyle.PlayStation.control(PadButton.B, false, testPad, frame)
    check(normalSouth.mode == ButtonMode.Default && normalEast.mode == ButtonMode.Held)
    check(normalSouth.visual == south.visual && normalEast.visual.shape == ButtonShape.Circle)
}

private fun supportAndOtherControlsFollowTheOriginalInputs() {
    val sparse = testPad.copy(supported = PadButton.B.bit or PadButton.R1.bit)
    val frame = snapshot(physical = sparse.supported)
    val south = ControllerStyle.PlayStation.control(PadButton.A, true, sparse, frame)
    val east = ControllerStyle.PlayStation.control(PadButton.B, true, sparse, frame)
    val commands = mutableListOf<PadButton>()
    check(south.supported && south.pressed && !east.supported && !east.pressed)
    east.cycle { commands += it }
    check(commands.isEmpty()) { "An unsupported mapped input must not accept a tap" }
    for (button in PadButton.entries.filterNot { it in facePositions }) {
        val normal = ControllerStyle.Xbox.control(button, false, sparse, frame)
        val inverted = ControllerStyle.Xbox.control(button, true, sparse, frame)
        check(normal == inverted) { "Face inversion must leave shoulders, sticks, menus and D-pad unchanged" }
    }
    val shoulder = ControllerStyle.Xbox.control(PadButton.R1, true, sparse, frame)
    shoulder.cycle { commands += it }
    check(shoulder.pressed && commands == listOf(PadButton.R1))
}

fun main() {
    physicalPressUsesFixedGlyphPositions()
    screenTapsControlTheHighlightedOriginalInput()
    supportAndOtherControlsFollowTheOriginalInputs()
    println("Controller diagram mapping checks passed.")
}
