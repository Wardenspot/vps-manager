package ru.nordkey.app.ui.setup

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Палитра мастера — всегда тёмная, независимо от темы приложения. */
object G {
    val Bg1 = Color(0xFF070B14)
    val Bg2 = Color(0xFF0B1324)
    val Card = Color(0xFF111A2E)
    val Card2 = Color(0xFF16213A)
    val Line = Color(0xFF22304D)
    val Text = Color(0xFFEAF1FF)
    val Muted = Color(0xFF8E9BB5)
    val Cyan = Color(0xFF35E0FF)
    val Blue = Color(0xFF3B7BFF)
    val Violet = Color(0xFF7C5CFF)
    val Ok = Color(0xFF3FE0A0)
    val Bad = Color(0xFFFF6B7A)
    val Warn = Color(0xFFFFC266)
}

/** Фон: глубокий тёмно-синий с мягким свечением сверху. */
fun Modifier.glowBackground() = this
    .background(Brush.verticalGradient(listOf(G.Bg2, G.Bg1)))
    .drawBehind {
        drawCircle(Brush.radialGradient(listOf(G.Blue.copy(alpha = 0.28f), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.22f),
                                        radius = size.width * 0.75f), radius = size.width * 0.75f, center = Offset(size.width * 0.5f, size.height * 0.22f))
        drawCircle(Brush.radialGradient(listOf(G.Violet.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.9f, size.height * 0.05f),
                                        radius = size.width * 0.5f), radius = size.width * 0.5f, center = Offset(size.width * 0.9f, size.height * 0.05f))
    }

/**
 * Роутер со светящимся логотипом. state: 0 — ждёт, 1 — работа (пульсирует), 2 — готово (зелёный), 3 — ошибка (красный).
 */
@Composable
fun RouterArt(state: Int, modifier: Modifier = Modifier) {
    val accent = when (state) { 2 -> G.Ok; 3 -> G.Bad; else -> G.Cyan }
    val pulse = if (state == 1) {
        rememberInfiniteTransition(label = "pulse").animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p").value
    } else 1f
    Canvas(modifier.aspectRatio(1.25f)) {
        val w = size.width; val h = size.height
        val cx = w / 2
        // свечение под роутером
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * pulse), Color.Transparent), center = Offset(cx, h * 0.52f), radius = w * 0.48f),
                   radius = w * 0.48f, center = Offset(cx, h * 0.52f))
        // тень на «полу»
        drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, h * 0.86f), radius = w * 0.32f),
                 topLeft = Offset(cx - w * 0.32f, h * 0.80f), size = Size(w * 0.64f, h * 0.12f))
        // корпус
        val bw = w * 0.52f; val bh = h * 0.56f
        val tl = Offset(cx - bw / 2, h * 0.22f)
        val r = CornerRadius(bw * 0.16f)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2B3550), Color(0xFF151C2E), Color(0xFF0E1322)), startY = tl.y, endY = tl.y + bh),
                      topLeft = tl, size = Size(bw, bh), cornerRadius = r)
        // блик по краю
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), startY = tl.y, endY = tl.y + bh * 0.5f),
                      topLeft = tl, size = Size(bw, bh), cornerRadius = r, style = Stroke(width = 2.dp.toPx()))
        // светящаяся полоса внизу
        val sy = tl.y + bh * 0.84f
        drawLine(accent.copy(alpha = 0.35f * pulse), Offset(cx - bw * 0.30f, sy), Offset(cx + bw * 0.30f, sy), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
        drawLine(accent.copy(alpha = pulse), Offset(cx - bw * 0.30f, sy), Offset(cx + bw * 0.30f, sy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        // логотип «N»
        val ls = bw * 0.30f
        val lx = cx - ls / 2; val ly = tl.y + bh * 0.26f
        val n = Path().apply { moveTo(lx, ly + ls); lineTo(lx, ly); lineTo(lx + ls, ly + ls); lineTo(lx + ls, ly) }
        drawPath(n, accent.copy(alpha = 0.30f * pulse), style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(n, Brush.linearGradient(listOf(accent, G.Blue), start = Offset(lx, ly), end = Offset(lx + ls, ly + ls)),
                 style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Большая светящаяся кнопка-«таблетка» (как «ПУСК»). */
@Composable
fun GlowButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, colors: List<Color> = listOf(G.Cyan, G.Blue), onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(60.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                if (enabled) drawRoundRect(Brush.horizontalGradient(colors.map { it.copy(alpha = 0.45f) }), topLeft = Offset(-6.dp.toPx(), 4.dp.toPx()),
                                           size = Size(size.width + 12.dp.toPx(), size.height + 6.dp.toPx()), cornerRadius = CornerRadius(size.height))
            }
            .clip(shape)
            .background(Brush.horizontalGradient(colors))
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF041022), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
    }
}

/** Полупрозрачная карточка мастера. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(G.Card2.copy(alpha = 0.92f), G.Card.copy(alpha = 0.92f))))
            .border(1.dp, G.Line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}
