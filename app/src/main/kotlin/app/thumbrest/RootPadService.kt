package app.thumbrest

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.topjohnwu.superuser.ipc.RootService

internal object NativePad {
    init {
        System.loadLibrary("thumbrest")
    }

    external fun scan(): Array<String>
    external fun start(node: Int, identity: String, rate: Int)
    external fun stop()
    external fun edit(enabled: Boolean)
    external fun rate(rate: Int)
    external fun cycle(button: Int)
    external fun clear()
    external fun visible(visible: Boolean)
    external fun snapshot(): IntArray
}

class RootPadService : RootService() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate() {
        worker = HandlerThread("Thumbrest root commands").apply { start() }
        val ownerUid = applicationInfo.uid
        handler = Handler(worker.looper) { request ->
            if (request.sendingUid == ownerUid) process(request)
            true
        }
    }

    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            try {
                NativePad.stop()
            } catch (_: LinkageError) {
                // A native library that could not be loaded owns no descriptors.
            }
        }
        worker.quitSafely()
    }

    private fun process(request: Message) {
        val result = Bundle()
        try {
            when (request.what) {
                PadProtocol.SCAN -> result.putStringArray("devices", NativePad.scan())
                PadProtocol.START -> {
                    val identity = requireNotNull(request.data.getString("identity"))
                    require(request.arg1 >= 0 && request.arg2 in 1..60)
                    NativePad.start(request.arg1, identity, request.arg2)
                    try {
                        NativePad.visible(request.data.getBoolean("visible"))
                        result.putIntArray("snapshot", NativePad.snapshot())
                    } catch (error: Throwable) {
                        NativePad.stop()
                        throw error
                    }
                }
                PadProtocol.SNAPSHOT -> result.putIntArray("snapshot", NativePad.snapshot())
                PadProtocol.EDIT -> {
                    NativePad.edit(request.arg1 != 0)
                    result.putIntArray("snapshot", NativePad.snapshot())
                }
                PadProtocol.CYCLE -> NativePad.cycle(request.arg1)
                PadProtocol.RATE -> NativePad.rate(request.arg1)
                PadProtocol.CLEAR -> NativePad.clear()
                PadProtocol.STOP -> NativePad.stop()
                PadProtocol.VISIBILITY -> NativePad.visible(request.arg1 != 0)
                else -> return
            }
        } catch (error: Exception) {
            result.putString("error", error.message ?: "Controller operation failed.")
        } catch (_: LinkageError) {
            result.putString("error", "The native controller engine could not start.")
        }

        try {
            request.replyTo?.send(Message.obtain(null, request.what).apply { data = result })
        } catch (_: RemoteException) {
            // libsu also closes the root process once the client dies.
            runCatching { NativePad.stop() }
        }
    }
}
