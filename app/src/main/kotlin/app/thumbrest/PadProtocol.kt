package app.thumbrest

internal object PadProtocol {
    const val SCAN = 1
    const val START = 2
    const val SNAPSHOT = 3
    const val CYCLE = 5
    const val RATE = 6
    const val CLEAR = 7
    const val STOP = 8
    const val RELEASE = 9

    const val VIRTUAL_NAME = "Thumbrest Virtual Gamepad"
}

internal data class PadDevice(
    val node: Int,
    val identity: String,
    val supported: Int,
    val stableId: String,
    val name: String,
) {
    companion object {
        fun parse(row: String): PadDevice? {
            val fields = row.split('\t', limit = 5)
            if (fields.size != 5) return null
            return PadDevice(
                fields[0].toIntOrNull() ?: return null,
                fields[1],
                fields[2].toIntOrNull() ?: return null,
                fields[3],
                fields[4],
            )
        }
    }

    fun supports(button: PadButton) = supported and button.bit != 0
}

internal enum class PadButton(val label: String, val description: String = label) {
    A("A"), B("B"), X("X"), Y("Y"),
    L1("L1"), R1("R1"), L2("L2"), R2("R2"), L3("L3"), R3("R3"),
    Select("Select"), Start("Start"),
    Up("↑", "D-pad up"), Down("↓", "D-pad down"),
    Left("←", "D-pad left"), Right("→", "D-pad right"), Guide("◆", "Guide");

    val bit: Int get() = 1 shl ordinal
}

internal enum class ButtonMode(val label: String) {
    Default("Default"), Held("Held"), Turbo("Turbo"),
}

internal data class PadSnapshot(
    val status: Int,
    val physical: Int,
    val output: Int,
    val held: Int,
    val turbo: Int,
    val leftX: Int,
    val leftY: Int,
    val rightX: Int,
    val rightY: Int,
) {
    fun mode(button: PadButton): ButtonMode = when {
        held and button.bit != 0 -> ButtonMode.Held
        turbo and button.bit != 0 -> ButtonMode.Turbo
        else -> ButtonMode.Default
    }

    companion object {
        fun parse(values: IntArray?): PadSnapshot? {
            if (values == null || values.size != 9) return null
            return PadSnapshot(
                values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8],
            )
        }
    }
}
