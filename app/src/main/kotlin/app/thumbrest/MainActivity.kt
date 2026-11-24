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
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var session: SessionService? = null
    private val screenState = MutableStateFlow(SessionState())
    private val controllerStyle = MutableStateFlow(ControllerStyle.Xbox)
    private val invertFaceGlyphs = MutableStateFlow(false)
    private var stateSubscription: Job? = null
    private var bound = false
    private var windowUpdateQueued = false
    private var requestedReady = false
    private var appliedWindowFlags: Int? = null

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!isFinishing) connect()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as SessionService.LocalBinder).service
            session = service
            stateSubscription?.cancel()
            stateSubscription = lifecycleScope.launch {
                service.state.collect { current ->
                    screenState.value = current
                    scheduleCompanionWindow(current.snapshot != null)
                }
            }
            service.setCompanionDisplay(currentDisplayId())
            service.setVisible(true)
            if (service.takeAutomaticStart()) requestConnection()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stateSubscription?.cancel()
            stateSubscription = null
            session = null
            screenState.value = SessionState(error = "Controller service disconnected. Tap Resume to reconnect.")
            scheduleCompanionWindow(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        controllerStyle.value = ControllerStyle.fromPreference(appearance.getString("controllerStyle", null))
        invertFaceGlyphs.value = appearance.getBoolean("invertFaceGlyphs", false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 30 && display?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        guardAgainstVirtualInput()
        setContent {
            val state by screenState.collectAsState()
            val style by controllerStyle.collectAsState()
            val inverted by invertFaceGlyphs.collectAsState()
            LaunchedEffect(state.snapshot?.status, state.snapshot?.held, state.snapshot?.turbo) {
                Log.i("ThumbrestUI", "Status=${state.snapshot?.status} held=${state.snapshot?.held} turbo=${state.snapshot?.turbo}")
            }
            ThumbrestTheme {
                ThumbrestScreen(
                    state = state,
                    controllerStyle = style,
                    onStyleChange = ::setControllerStyle,
                    invertFaceGlyphs = inverted,
                    onInvertFaceGlyphsChange = ::setInvertFaceGlyphs,
                    onConnect = ::requestConnection,
                    onSelect = { session?.select(it) },
                    onCycle = { session?.cycle(it) },
                    onRate = { session?.setRate(it) },
                    onClear = { session?.clearModes() },
                    onStop = { session?.stopSession() },
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
        stateSubscription?.cancel()
        stateSubscription = null
        if (bound) unbindService(connection)
        bound = false
        session = null
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayId(): Int = if (Build.VERSION.SDK_INT >= 30) {
        display?.displayId ?: windowManager.defaultDisplay.displayId
    } else windowManager.defaultDisplay.displayId

    private fun scheduleCompanionWindow(ready: Boolean) {
        requestedReady = ready
        if (windowUpdateQueued) return
        windowUpdateQueued = true
        // Initial composition can run during WindowManager.addView, before the
        // Activity can propagate attribute changes. Apply after attachment.
        window.decorView.post {
            windowUpdateQueued = false
            if (!isDestroyed) updateCompanionWindow(requestedReady)
        }
    }

    private fun updateCompanionWindow(ready: Boolean) {
        val displayId = window.decorView.display?.displayId
        val secondary = displayId != null && displayId != Display.DEFAULT_DISPLAY
        displayId?.let { session?.setCompanionDisplay(it) }
        val noFocus = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val managed = noFocus or keepOn
        // Touch controls on a second display must not take controller key focus
        // from the game. Avoid a window-manager update on every preview frame.
        val desired = (if (secondary) noFocus else 0) or (if (ready) keepOn else 0)
        if (appliedWindowFlags != desired) {
            window.setFlags(desired, managed)
            appliedWindowFlags = desired
            Log.i("ThumbrestWindow", "Ready=$ready display=$displayId companionFlags=$desired")
        }
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

    private fun setControllerStyle(style: ControllerStyle) {
        if (controllerStyle.value == style) return
        getSharedPreferences("appearance", MODE_PRIVATE).edit {
            putString("controllerStyle", style.preferenceId)
        }
        controllerStyle.value = style
    }

    private fun setInvertFaceGlyphs(inverted: Boolean) {
        if (invertFaceGlyphs.value == inverted) return
        getSharedPreferences("appearance", MODE_PRIVATE).edit {
            putBoolean("invertFaceGlyphs", inverted)
        }
        invertFaceGlyphs.value = inverted
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
