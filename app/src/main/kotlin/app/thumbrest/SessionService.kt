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
import android.util.Log
import androidx.core.content.edit
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
    private val preferences by lazy { getSharedPreferences("session", MODE_PRIVATE) }
    private var connection: RootConnection? = null
    private var visible = false
    private var scanAfter = 0L
    private var snapshotPending = false
    private var automaticAttempted = false
    private var blockedIdentity: String? = null
    private var releasing = false

    override fun onCreate() {
        super.onCreate()
        mutableState.value = SessionState(rate = preferences.getInt("rate", 15).coerceIn(1, 60))
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    internal fun takeAutomaticStart(): Boolean {
        if (automaticAttempted || connection != null || !preferences.getBoolean("enabled", true)) return false
        automaticAttempted = true
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> if (connection == null) {
                preferences.edit { putBoolean("enabled", true) }
                automaticAttempted = true
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
        if (value) {
            scanAfter = 0
            schedulePoll(0)
        }
    }

    internal fun select(controller: PadDevice) {
        val current = state.value
        if (!current.connected || current.selected != null || current.stopping || releasing) return
        mutableState.update { it.copy(selected = controller, starting = true, error = null) }
        send(PadProtocol.START, controller.node, current.rate, Bundle().apply {
            putString("identity", controller.identity)
        })
    }

    internal fun cycle(button: PadButton) {
        if (state.value.snapshot?.status == 1 && !state.value.stopping) {
            send(PadProtocol.CYCLE, button.ordinal)
        }
    }

    internal fun setRate(rate: Int) {
        if (rate !in 1..60 || rate == state.value.rate || state.value.snapshot?.status != 1 || state.value.stopping) return
        preferences.edit { putInt("rate", rate) }
        mutableState.update { it.copy(rate = rate) }
        send(PadProtocol.RATE, rate)
    }

    internal fun clearModes() {
        if (state.value.snapshot?.status == 1 && !state.value.stopping) send(PadProtocol.CLEAR)
    }

    internal fun stopSession() {
        preferences.edit { putBoolean("enabled", false) }
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
        blockedIdentity = null
        mutableState.value = SessionState(connecting = true, rate = state.value.rate)
        Log.i(TAG, "Requesting root controller connection")
        try {
            Shell.getShell { shell ->
                main.post {
                    if (connection !== client) return@post
                    if (!shell.isRoot) {
                        finish("Grant Thumbrest superuser access in Magisk and try again.")
                        return@post
                    }
                    try {
                        client.binding = true
                        RootService.bind(Intent(this, RootPadService::class.java), client)
                    } catch (error: RuntimeException) {
                        client.binding = false
                        Log.e(TAG, "Root service binding failed", error)
                        finish("Could not bind the root controller service. Tap Resume to retry.")
                    }
                }
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Magisk connection failed", error)
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
            Log.i(TAG, "Root controller connection ready")
            scanAfter = 0
            scan()
            schedulePoll(0)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (connection === this) finish("Root service disconnected. Tap Resume to reconnect.")
        }
    }

    private fun scan() {
        if (state.value.scanning || releasing) return
        scanAfter = SystemClock.uptimeMillis() + 1_000
        mutableState.update { it.copy(scanning = true) }
        send(PadProtocol.SCAN)
    }

    private val poll = Runnable {
        val current = state.value
        if (!current.connected || current.stopping) return@Runnable
        if (current.selected == null && SystemClock.uptimeMillis() >= scanAfter) scan()
        if (current.snapshot != null && !snapshotPending && !releasing) {
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
            finish("Root service disconnected. Tap Resume to reconnect.")
        }
    }

    private fun receive(message: Message) {
        val error = message.data.getString("error")
        if (state.value.stopping && message.what != PadProtocol.STOP) return
        if (releasing && message.what != PadProtocol.RELEASE && message.what != PadProtocol.STOP) return
        when (message.what) {
            PadProtocol.SCAN -> {
                val devices = message.data.getStringArray("devices")?.mapNotNull(PadDevice::parse)
                mutableState.update {
                    it.copy(scanning = false, devices = devices ?: it.devices, scanError = error)
                }
                if (devices != null && error == null) {
                    automaticController(devices, preferences.getString("controller", null))
                        ?.takeIf { it.identity != blockedIdentity }?.let(::select)
                }
            }
            PadProtocol.START -> {
                val snapshot = PadSnapshot.parse(message.data.getIntArray("snapshot"))
                if (snapshot?.status == 1) {
                    state.value.selected?.let { pad -> preferences.edit { putString("controller", pad.stableId) } }
                    mutableState.update { it.copy(starting = false, snapshot = snapshot) }
                    schedulePoll(0)
                } else {
                    blockedIdentity = state.value.selected?.identity
                    Log.w(TAG, "Controller start failed: ${error ?: "disconnected"}")
                    mutableState.update { it.copy(starting = false, selected = null,
                        error = error ?: "The controller disconnected before it was ready.") }
                    releasing = true
                    send(PadProtocol.RELEASE)
                }
            }
            PadProtocol.SNAPSHOT, PadProtocol.CYCLE, PadProtocol.CLEAR, PadProtocol.RATE -> {
                if (message.what == PadProtocol.SNAPSHOT) snapshotPending = false
                val snapshot = PadSnapshot.parse(message.data.getIntArray("snapshot"))
                if (snapshot?.status == 1 && error == null) {
                    mutableState.update { it.copy(snapshot = snapshot) }
                } else {
                    if (snapshot?.status != 2) blockedIdentity = state.value.selected?.identity
                    val reason = error ?: when (snapshot?.status) {
                        2 -> "Controller disconnected. Waiting for it to reconnect…"
                        3 -> "Thumbrest lost access to the controller. Select it to retry."
                        4 -> "The virtual gamepad stopped accepting input. Select the controller to retry."
                        5 -> "The controller recovery process ended. Physical input has been restored."
                        6 -> "Could not restore the physical controller node. Reboot the device to restore its input devices."
                        else -> "The controller session ended. Select the controller to retry."
                    }
                    Log.w(TAG, reason)
                    releasing = true
                    mutableState.update { it.copy(selected = null, snapshot = null, error = reason) }
                    send(PadProtocol.RELEASE)
                }
            }
            PadProtocol.RELEASE -> {
                releasing = false
                snapshotPending = false
                if (error != null) finish(error) else { scan(); schedulePoll(0) }
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
        if (error != null) Log.w(TAG, error) else Log.i(TAG, "Controller session stopped")
        main.removeCallbacks(poll)
        releaseConnection()
        snapshotPending = false
        releasing = false
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
        private const val TAG = "ThumbrestSession"
    }
}
