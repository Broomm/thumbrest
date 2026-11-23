package app.thumbrest

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.WindowManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var session by mutableStateOf<SessionService?>(null)
    private var bound = false

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!isFinishing) connect()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            session = (binder as SessionService.LocalBinder).service.also {
                it.setVisible(true)
                if (it.takeAutomaticStart()) requestConnection()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            session = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        guardAgainstVirtualInput()
        setContent {
            val fallback = remember { MutableStateFlow(SessionState()) }
            val service = session
            val state by (service?.state ?: fallback).collectAsState()
            SideEffect { updateCompanionWindow(state.snapshot != null) }
            ThumbrestTheme {
                ThumbrestScreen(
                    state = state,
                    onConnect = ::requestConnection,
                    onSelect = { service?.select(it) },
                    onCycle = { service?.cycle(it) },
                    onRate = { service?.setRate(it) },
                    onClear = { service?.clearModes() },
                    onStop = { service?.stopSession() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, SessionService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        session?.setVisible(false)
        if (bound) unbindService(connection)
        bound = false
        session = null
        super.onStop()
    }

    private fun updateCompanionWindow(ready: Boolean) {
        val secondary = window.decorView.display?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true
        val noFocus = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val managed = noFocus or keepOn
        // Touch controls on a second display must not take controller key focus
        // from the game. Avoid a window-manager update on every preview frame.
        val desired = (if (ready && secondary) noFocus else 0) or (if (ready) keepOn else 0)
        if ((window.attributes.flags and managed) != desired) window.setFlags(desired, managed)
    }

    private fun guardAgainstVirtualInput() {
        // A latched button from the virtual pad must never activate our own UI.
        val original = window.callback
        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                event.device?.name == PadProtocol.VIRTUAL_NAME || original.dispatchKeyEvent(event)

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                event.device?.name == PadProtocol.VIRTUAL_NAME || original.dispatchGenericMotionEvent(event)
        }
    }

    private fun requestConnection() {
        val preferences = getPreferences(MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("askedForNotifications", false)
        ) {
            preferences.edit { putBoolean("askedForNotifications", true) }
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            connect()
        }
    }

    private fun connect() {
        startForegroundService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_CONNECT))
    }
}
