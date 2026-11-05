package ru.nordcam.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import ru.nordcam.app.service.MonitorService
import ru.nordcam.app.share.ShareCodec
import ru.nordcam.app.ui.C
import ru.nordcam.app.ui.EditCameraScreen
import ru.nordcam.app.ui.FullscreenScreen
import ru.nordcam.app.ui.HomeScreen
import ru.nordcam.app.ui.ImportScreen
import ru.nordcam.app.ui.LocalPlayerScreen
import ru.nordcam.app.ui.LockScreen
import ru.nordcam.app.ui.NordCamTheme
import ru.nordcam.app.ui.PlaybackScreen
import ru.nordcam.app.ui.ShareScreen

sealed interface Screen {
    data object Home : Screen
    data class Live(val camId: String) : Screen
    data class Playback(val camId: String, val at: Long = 0L, val tab: Int = 0) : Screen
    data class LocalPlay(val path: String, val camId: String = "") : Screen
    data class EditCamera(val camId: String?) : Screen
    data object Share : Screen
    data class Import(val code: String = "") : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val tab = mutableStateOf(0)
    val top: Screen get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home(tabIndex: Int? = null) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (tabIndex != null) tab.value = tabIndex
    }
}

class MainActivity : FragmentActivity() {
    private val nav = Nav()
    private val locked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked.value = app.store.settings.value.pin.isNotBlank()
        handle(intent)
        MonitorService.sync(this)
        setContent {
            NordCamTheme {
                Surface(Modifier.fillMaxSize(), color = C.bg) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (locked.value) LockScreen(this@MainActivity) { locked.value = false }
                        else Root(nav)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        app.backgroundSince = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        val s = app.store.settings.value
        val since = app.backgroundSince
        if (s.pin.isNotBlank() && since > 0 && System.currentTimeMillis() - since > s.autoRelockSec * 1000L) {
            locked.value = true
        }
        app.backgroundSince = 0
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        val data = i.dataString
        if (data != null && data.startsWith("nordcam:")) {
            ShareCodec.extract(data)?.let { nav.home(); nav.go(Screen.Import(it)) }
            return
        }
        if (i.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { ShareCodec.extract(it) }?.let { nav.home(); nav.go(Screen.Import(it)) }
            return
        }
        if (i.getStringExtra(EXTRA_EVENT) != null) {
            nav.home(2)
        }
    }

    companion object {
        const val EXTRA_EVENT = "event"
        const val EXTRA_CAMERA = "camera"
    }
}

@Composable
private fun Root(nav: Nav) {
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    when (val s = nav.top) {
        Screen.Home -> HomeScreen(nav)
        is Screen.Live -> FullscreenScreen(nav, s.camId)
        is Screen.Playback -> PlaybackScreen(nav, s.camId, s.at, s.tab)
        is Screen.LocalPlay -> LocalPlayerScreen(nav, s.path, s.camId)
        is Screen.EditCamera -> EditCameraScreen(nav, s.camId)
        Screen.Share -> ShareScreen(nav)
        is Screen.Import -> ImportScreen(nav, s.code)
    }
}
