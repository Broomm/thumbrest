package app.thumbrest

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService

internal object NativePad {
    init { System.loadLibrary("thumbrest") }

    external fun scan(): Array<String>
    external fun start(node: Int, identity: String, rate: Int)
    external fun stop()
    external fun rate(rate: Int)
    external fun cycle(button: Int)
    external fun clear()
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
        Log.i(TAG, "Root controller service created")
    }

    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            try {
                NativePad.stop()
                Log.i(TAG, "Controller released on root service shutdown")
            } catch (error: Exception) {
                Log.e(TAG, "Controller recovery failed during root service shutdown", error)
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
                    result.putIntArray("snapshot", NativePad.snapshot())
                    Log.i(TAG, "Controller ready: event${request.arg1}")
                }
                PadProtocol.SNAPSHOT -> result.putIntArray("snapshot", NativePad.snapshot())
                PadProtocol.CYCLE -> {
                    NativePad.cycle(request.arg1)
                    result.putIntArray("snapshot", NativePad.snapshot())
                }
                PadProtocol.RATE -> {
                    require(request.arg1 in 1..60)
                    NativePad.rate(request.arg1)
                    result.putIntArray("snapshot", NativePad.snapshot())
                }
                PadProtocol.CLEAR -> {
                    NativePad.clear()
                    result.putIntArray("snapshot", NativePad.snapshot())
                }
                PadProtocol.STOP, PadProtocol.RELEASE -> {
                    NativePad.stop()
                    Log.i(TAG, "Controller released")
                }
                else -> return
            }
        } catch (error: Exception) {
            Log.e(TAG, "Controller command ${request.what} failed", error)
            result.putString("error", error.message ?: "Controller operation failed.")
        } catch (error: LinkageError) {
            Log.e(TAG, "Native controller library failed", error)
            result.putString("error", "The native controller engine could not start.")
        }

        try {
            request.replyTo?.send(Message.obtain(null, request.what).apply { data = result })
        } catch (_: RemoteException) {
            // libsu also closes the root process once the client dies.
            runCatching { NativePad.stop() }
        }
    }

    companion object { private const val TAG = "ThumbrestRoot" }
}
