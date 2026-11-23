package app.thumbrest

private fun pad(node: Int, stableId: String, name: String = "Gamepad") =
    PadDevice(node, "inode-$node", 15, stableId, name)

fun main() {
    val remembered = pad(19, "controller-one", "Renamed pad")
    check(automaticController(listOf(remembered), null) == remembered) {
        "One connected pad should become ready without a picker"
    }
    check(automaticController(listOf(pad(4, "other"), remembered), "controller-one") == remembered) {
        "A remembered hardware identity must survive renaming and node changes"
    }
    check(automaticController(listOf(remembered, pad(20, remembered.stableId)), remembered.stableId) == null) {
        "Indistinguishable pads require an explicit choice"
    }
    check(automaticController(listOf(pad(4, "other")), remembered.stableId) == null) {
        "A missing remembered pad must not silently select another controller"
    }
    check(automaticController(listOf(remembered, pad(4, "other")), null) == null) {
        "The first connection must not guess between different pads"
    }
    check(automaticController(emptyList(), remembered.stableId) == null) {
        "A disconnected controller must remain pending"
    }
    println("Automatic controller selection checks passed.")
}
