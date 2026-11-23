package app.thumbrest

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

// Root-only adapter. Associations belong to Thumbrest's uinput port; original
// devices and OEM configuration are never changed. Android 15 renamed the
// unique-ID methods, while older Android releases expose physical-port routing.
internal class DisplayRouting(context: Context, private val inputPort: String) {
    private val input = requireNotNull(context.getSystemService(InputManager::class.java))
    private val displays = requireNotNull(context.getSystemService(DisplayManager::class.java))
    var target: Int = Display.INVALID_DISPLAY
        private set

    fun prepare(displayId: Int) {
        clear()
        if (displayId == Display.INVALID_DISPLAY) return
        require(displayId >= 0)
        val display = displays.getDisplay(displayId)
            ?: error("The game display is no longer connected.")
        val unique = method("addUniqueIdAssociationByPort", String::class.java, String::class.java)
            ?: method("addUniqueIdAssociation", String::class.java, String::class.java)
        if (unique != null) {
            val id = Display::class.java.getMethod("getUniqueId").invoke(display) as String
            invoke(unique, inputPort, id)
        } else {
            val add = method("addPortAssociation", String::class.java, Int::class.javaPrimitiveType!!)
                ?: error("This Android build cannot keep the controller on the game display.")
            val address = Display::class.java.getMethod("getAddress").invoke(display)
                ?: error("The game display has no routable physical address.")
            val port = (address.javaClass.getMethod("getPort").invoke(address) as Number).toInt()
            require(port in 0..255) { "The game display has an unsupported physical port." }
            invoke(add, inputPort, port)
        }
        target = displayId
    }

    fun clear() {
        val unique = method("removeUniqueIdAssociationByPort", String::class.java)
            ?: method("removeUniqueIdAssociation", String::class.java)
        unique?.let { invoke(it, inputPort) }
        method("removePortAssociation", String::class.java)?.let { invoke(it, inputPort) }
        target = Display.INVALID_DISPLAY
    }

    fun awaitVirtualController() {
        val deadline = SystemClock.uptimeMillis() + 1_500
        // The Java associated-display accessor exists only on newer Android.
        // On Android 13 the association API acknowledges the mapping; actual
        // InputReader viewport behavior is covered by the Thor device checks.
        val associated = InputDevice::class.java.methods.firstOrNull {
            it.name == "getAssociatedDisplayId" && it.parameterCount == 0
        }
        do {
            val pads = input.inputDeviceIds.map(input::getInputDevice).filterNotNull()
                .filter { it.name == PadProtocol.VIRTUAL_NAME }
            check(pads.size <= 1) { "More than one Thumbrest virtual controller is connected." }
            if (pads.size == 1) {
                if (target == Display.INVALID_DISPLAY || associated == null ||
                    (associated.invoke(pads.single()) as Int) == target
                ) return
            }
            Thread.sleep(10)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Android did not register the virtual controller on the game display.")
    }

    private fun method(name: String, vararg parameters: Class<*>): Method? =
        InputManager::class.java.methods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(parameters)
        }

    private fun invoke(method: Method, vararg arguments: Any) {
        try {
            method.invoke(input, *arguments)
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("Android could not route the virtual controller: " +
                (error.targetException.message ?: method.name), error.targetException)
        }
    }
}
