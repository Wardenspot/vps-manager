package ru.nordcam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.Vendors
import ru.nordcam.app.player.CamPlayer
import ru.nordcam.app.player.PlayState
import ru.nordcam.app.player.Vlc

/** true, пока экран виден (приложение не свёрнуто). */
@Composable
fun rememberVisible(): Boolean {
    val owner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, _ -> visible = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return visible
}

/**
 * Окно видео камеры. Само переподключается при обрыве, останавливается, когда приложение свёрнуто.
 * [url] — если задан, играет его (архив), иначе живой поток камеры.
 */
@Composable
fun CamVideo(
    cam: Camera,
    modifier: Modifier = Modifier,
    sub: Boolean = true,
    audio: Boolean = false,
    muted: Boolean = true,
    fill: Boolean = false,
    url: String? = null,
    autoReconnect: Boolean = true,
    onPlayer: (CamPlayer) -> Unit = {},
    videoModifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val player = remember(cam.id, url) { CamPlayer(ctx) }
    var attached by remember(player) { mutableStateOf(false) }
    var attempt by remember(player) { mutableIntStateOf(0) }
    val visible = rememberVisible()
    val state by player.state.collectAsState()
    val src = url ?: Vendors.liveUrl(cam, sub)

    DisposableEffect(player) {
        onPlayer(player)
        onDispose { player.release() }
    }
    LaunchedEffect(player, attached, visible, src, attempt, cam.tcp, cam.user, cam.password, audio) {
        if (!attached || src.isBlank()) return@LaunchedEffect
        if (!visible) {
            player.stop(); return@LaunchedEffect
        }
        player.play(Vlc.liveMedia(ctx, src, cam, lowLatency = url == null, audio = audio))
    }
    LaunchedEffect(state, visible) {
        if (autoReconnect && visible && (state == PlayState.ERROR || state == PlayState.ENDED)) {
            delay(if (state == PlayState.ERROR) 4000 else 1500)
            attempt++
        }
    }
    LaunchedEffect(muted, state) { player.setVolume(if (muted) 0 else 100) }
    LaunchedEffect(fill, attached) { if (attached) player.setFill(fill) }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c -> VLCVideoLayout(c).also { l -> player.attach(l); attached = true } },
            modifier = Modifier.fillMaxSize().then(videoModifier),
        )
        when {
            src.isBlank() -> Hint("Не задан адрес потока")
            state == PlayState.CONNECTING -> CircularProgressIndicator(Modifier.size(28.dp), color = C.accent2, strokeWidth = 2.dp)
            state == PlayState.ERROR -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.VideocamOff, null, tint = C.muted)
                Hint("Нет связи — переподключаюсь…")
            }
            state == PlayState.ENDED && url != null -> Hint("Запись закончилась")
        }
    }
}

@Composable
private fun Hint(t: String) {
    Text(t, color = C.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
}
