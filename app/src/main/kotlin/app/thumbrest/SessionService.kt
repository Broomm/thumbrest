package app.thumbrest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal data class SessionState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val scanning: Boolean = false,
    val scanError: String? = null,
    val devices: List<PadDevice> = emptyList(),
    val selected: PadDevice? = null,
    val snapshot: PadSnapshot? = null,
    val rate: Int = 15,
    val error: String? = null,
)

class SessionService : Service() {
    internal inner class LocalBinder : Binder() {
        val service: SessionService get() = this@SessionService
    }

    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(SessionState())
    internal val state: StateFlow<SessionState> = mutableState
    private var connection: RootConnection? = null
    private var visible = false
    private var scanAfter = 0L
    private var snapshotPending = false

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> if (connection == null) {
                startForeground(NOTIFICATION_ID, notification())
                connect()
            }
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        releaseConnection()
        super.onDestroy()
    }

    internal fun setVisible(value: Boolean) {
        visible = value
        if (state.value.selected != null) send(PadProtocol.VISIBILITY, if (value) 1 else 0)
        if (value) {
            scanAfter = 0
            schedulePoll(0)
        }
    }

    internal fun select(controller: PadDevice) {
        val current = state.value
        if (!current.connected || current.selected != null || current.stopping) return
        mutableState.update { it.copy(selected = controller, starting = true, error = null) }
        val identity = Bundle().apply {
            putString("identity", controller.identity)
            putBoolean("visible", visible)
        }
        send(PadProtocol.START, controller.node, current.rate, identity)
    }

    internal fun setEditing(editing: Boolean) {
        if (state.value.snapshot != null && !state.value.stopping) {
            send(PadProtocol.EDIT, if (editing) 1 else 0)
        }
    }

    internal fun cycle(button: PadButton) {
        if (state.value.snapshot?.editing == true && !state.value.stopping) {
            send(PadProtocol.CYCLE, button.ordinal)
        }
    }

    internal fun setRate(rate: Int) {
        if (rate !in 1..60 || state.value.snapshot?.editing != true || state.value.stopping) return
        mutableState.update { it.copy(rate = rate) }
        send(PadProtocol.RATE, rate)
    }

    internal fun clearModes() {
        if (state.value.snapshot?.editing == true && !state.value.stopping) send(PadProtocol.CLEAR)
    }

    internal fun stopSession() {
        val client = connection
        if (state.value.stopping) return
        if (client?.peer == null) {
            finish()
            return
        }
        mutableState.update { it.copy(stopping = true) }
        send(PadProtocol.STOP)
        main.postDelayed({
            if (connection === client && state.value.stopping) finish("Controller service stopped responding.")
        }, 2_000)
    }

    private fun connect() {
        val client = RootConnection()
        connection = client
        mutableState.value = SessionState(connecting = true, rate = state.value.rate)
        try {
            Shell.getShell { shell ->
                main.post {
                    if (connection !== client) return@post
                    if (!shell.isRoot) {
                        finish("Grant Thumbrest superuser access in Magisk and try again.")
                        return@post
                    }
                    client.binding = true
                    RootService.bind(Intent(this, RootPadService::class.java), client)
                }
            }
        } catch (_: RuntimeException) {
            finish("Could not request Magisk root access.")
        }
        main.postDelayed({
            if (connection === client && client.peer == null) {
                finish("Root service did not respond. Check the Magisk permission prompt.")
            }
        }, 45_000)
    }

    private inner class RootConnection : ServiceConnection {
        var peer: Messenger? = null
        var binding = false
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (connection === this) receive(message)
            true
        })

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (connection !== this) {
                if (binding) RootService.unbind(this)
                return
            }
            peer = Messenger(binder)
            mutableState.update { it.copy(connecting = false, connected = true) }
            scanAfter = 0
            scan()
            schedulePoll(0)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (connection === this) finish("Root service disconnected. Reconnect to start again.")
        }
    }

    private fun scan() {
        if (state.value.scanning) return
        scanAfter = SystemClock.uptimeMillis() + 1_000
        mutableState.update { it.copy(scanning = true) }
        send(PadProtocol.SCAN)
    }

    private val poll = Runnable {
        val current = state.value
        if (!current.connected || current.stopping) return@Runnable
        if (current.selected == null && visible && SystemClock.uptimeMillis() >= scanAfter) scan()
        if (current.snapshot != null && !snapshotPending) {
            snapshotPending = true
            send(PadProtocol.SNAPSHOT)
        }
        if (state.value.connected) schedulePoll(if (visible && current.snapshot != null) 40 else 300)
    }

    private fun schedulePoll(delay: Long) {
        main.removeCallbacks(poll)
        main.postDelayed(poll, delay)
    }

    private fun send(code: Int, first: Int = 0, second: Int = 0, payload: Bundle? = null) {
        val client = connection ?: return
        val peer = client.peer ?: return
        val message = Message.obtain(null, code, first, second).apply {
            replyTo = client.receiver
            if (payload != null) data = payload
        }
        try {
            peer.send(message)
        } catch (_: RemoteException) {
            finish("Root service disconnected. Reconnect to start again.")
        }
    }

    private fun receive(message: Message) {
        val error = message.data.getString("error")
        if (state.value.stopping && message.what != PadProtocol.STOP) return
        when (message.what) {
            PadProtocol.SCAN -> {
                val devices = message.data.getStringArray("devices")?.mapNotNull(PadDevice::parse)
                mutableState.update {
                    it.copy(scanning = false, devices = devices ?: it.devices, scanError = error)
                }
            }
            PadProtocol.START -> {
                val snapshot = PadSnapshot.parse(message.data.getIntArray("snapshot"))
                if (snapshot?.status == 1) {
                    mutableState.update { it.copy(starting = false, snapshot = snapshot) }
                    send(PadProtocol.VISIBILITY, if (visible) 1 else 0)
                    schedulePoll(0)
                } else if (error != null) {
                    mutableState.update { it.copy(starting = false, selected = null, error = error) }
                } else {
                    finish("The controller disconnected before the session started.")
                }
            }
            PadProtocol.SNAPSHOT, PadProtocol.EDIT -> {
                if (message.what == PadProtocol.SNAPSHOT) snapshotPending = false
                val snapshot = PadSnapshot.parse(message.data.getIntArray("snapshot"))
                if (snapshot?.status == 1) {
                    mutableState.update { it.copy(snapshot = snapshot) }
                } else {
                    finish(error ?: when (snapshot?.status) {
                        2 -> "Controller disconnected. Reconnect it to start again."
                        3 -> "Thumbrest lost access to the controller."
                        4 -> "The virtual gamepad stopped accepting input."
                        else -> "The controller session ended."
                    })
                }
            }
            PadProtocol.STOP -> finish(error)
            else -> if (error != null) finish(error)
        }
    }

    private fun releaseConnection() {
        val client = connection
        connection = null
        if (client?.binding == true) RootService.unbind(client)
    }

    private fun finish(error: String? = null) {
        main.removeCallbacks(poll)
        releaseConnection()
        snapshotPending = false
        mutableState.value = SessionState(rate = state.value.rate, error = error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.session_notification))
            .setContentText(getString(R.string.session_notification_body))
            .setSmallIcon(R.drawable.ic_session)
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_session), stop).build())
            .build()
    }

    companion object {
        internal const val ACTION_CONNECT = "app.thumbrest.CONNECT"
        private const val ACTION_STOP = "app.thumbrest.STOP"
        private const val CHANNEL = "controller_session"
        private const val NOTIFICATION_ID = 1
    }
}
