package ru.nordcam.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Положение складного телефона:
 * TABLETOP — полусложен «ноутбуком» (сгиб горизонтальный): видео сверху, кнопки снизу;
 * BOOK — полураскрыт «книжкой» (сгиб вертикальный): две половины слева и справа.
 */
enum class Posture { NORMAL, TABLETOP, BOOK }

/** Размеры окна в dp и положение сгиба. */
data class WinSize(
    val widthDp: Int,
    val heightDp: Int,
    val posture: Posture,
) {
    /** Развёрнутый складной телефон или планшет. */
    val wide: Boolean get() = widthDp >= 600
    /** Невысокое окно: телефон в альбомной ориентации, внешний экран Flip. */
    val short: Boolean get() = heightDp < 480
    val landscape: Boolean get() = widthDp > heightDp
    /** Маленький экран: внешний дисплей Flip и узкие телефоны. */
    val tiny: Boolean get() = widthDp < 360
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun rememberWinSize(): WinSize {
    val cfg = LocalConfiguration.current
    val activity = LocalContext.current.findActivity()
    val flow: Flow<WindowLayoutInfo?> = remember(activity) {
        if (activity == null) flowOf(null)
        else try {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
        } catch (e: Throwable) {
            flowOf(null)
        }
    }
    val info by flow.collectAsState(initial = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    val posture = when {
        fold == null || fold.state != FoldingFeature.State.HALF_OPENED -> Posture.NORMAL
        fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> Posture.TABLETOP
        else -> Posture.BOOK
    }
    return WinSize(cfg.screenWidthDp, cfg.screenHeightDp, posture)
}

/** Ограничивает ширину содержимого на больших экранах, чтобы формы и списки не растягивались на весь разворот. */
@Composable
fun Centered(maxWidth: Dp = 840.dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize(), content = content)
    }
}

/**
 * Лучшая сетка для [n] окон 16:9 в области [w]×[h]: перебирает число столбцов
 * и выбирает вариант с самыми крупными окнами — без пустых полос на развёрнутом экране.
 */
fun bestGrid(n: Int, w: Float, h: Float, gap: Float): Pair<Int, Int> {
    var best = 1 to n
    var bestW = 0f
    for (cols in 1..n) {
        val rows = (n + cols - 1) / cols
        if (cols > 1 && (cols - 1) * rows >= n) continue
        val byW = (w - gap * (cols - 1)) / cols
        val byH = ((h - gap * (rows - 1)) / rows) * 16f / 9f
        val tw = minOf(byW, byH)
        if (tw > bestW + 0.5f) { bestW = tw; best = cols to rows }
    }
    return best
}
