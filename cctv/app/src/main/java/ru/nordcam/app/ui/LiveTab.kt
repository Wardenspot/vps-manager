package ru.nordcam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.data.Camera
import ru.nordcam.app.service.RecKind
import kotlin.math.max
import kotlin.math.sqrt

@Composable
fun LiveTab(nav: Nav) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val cams by store.cameras.collectAsState()
    val settings by store.settings.collectAsState()
    val rec by ctx.app.recorder.recording.collectAsState()
    val groups = cams.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    var group by remember { mutableStateOf(settings.lastGroup) }
    if (group.isNotBlank() && group !in groups) group = ""
    val list = cams.filter { group.isBlank() || it.group == group }
    val size = settings.gridSize.coerceIn(1, 16)
    val cols = sqrt(size.toDouble()).toInt().coerceAtLeast(1)
    val pages = max(1, (list.size + size - 1) / size)

    Column(Modifier.fillMaxSize()) {
        Bar("Просмотр") {
            listOf(1, 4, 9, 16).forEach { n ->
                TextButton(onClick = { store.updateSettings { it.copy(gridSize = n) } }) {
                    Text("$n", color = if (n == size) C.accent2 else C.muted, fontWeight = if (n == size) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (groups.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                item {
                    Chip("Все", group.isBlank()) { group = ""; store.updateSettings { it.copy(lastGroup = "") } }
                }
                items(groups) { g ->
                    Chip(g, group == g) { group = g; store.updateSettings { it.copy(lastGroup = g) } }
                }
            }
        }
        if (list.isEmpty()) {
            Empty("Камер пока нет", "Добавьте камеру — найдём её в сети автоматически или по адресу.") {
                Button(onClick = { nav.go(Screen.EditCamera(null)) }) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить камеру")
                }
            }
            return@Column
        }
        val pager = rememberPagerState(pageCount = { pages })
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), key = { "$size-$group-$it" }) { page ->
            val slice = list.drop(page * size).take(size)
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until cols) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0 until cols) {
                            val cam = slice.getOrNull(r * cols + c)
                            Box(Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(if (cols >= 4) 4.dp else 8.dp))) {
                                if (cam != null) Tile(cam, hd = size == 1, recKind = rec[cam.id], small = cols >= 4) { nav.go(Screen.Live(cam.id)) }
                                else Box(Modifier.fillMaxSize().background(C.surface))
                            }
                        }
                    }
                }
            }
        }
        if (pages > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages) { i ->
                    Dot(if (i == pager.currentPage) C.accent2 else C.line, 7)
                    if (i < pages - 1) Spacer(Modifier.width(6.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Нажмите на камеру — полный экран, запись, поворот и архив. Листайте вбок для следующих камер.",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun Chip(t: String, sel: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = sel, onClick = onClick, label = { Text(t) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.accent, selectedLabelColor = Color.White),
    )
}

@Composable
fun Tile(cam: Camera, hd: Boolean, recKind: RecKind?, small: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (!cam.perms.live || cam.perms.expired) {
            Column(Modifier.fillMaxSize().background(C.surface), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Block, null, tint = C.muted)
                Text(if (cam.perms.expired) "Доступ истёк" else "Нет доступа", color = C.muted, fontSize = 12.sp)
            }
        } else {
            CamVideo(cam, Modifier.fillMaxSize(), sub = !hd, audio = false, fill = !hd)
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .padding(horizontal = 6.dp, vertical = if (small) 2.dp else 4.dp),
        ) {
            Text(cam.title, color = Color.White, fontSize = if (small) 9.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (recKind != null) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(C.red, 6)
                if (!small) { Spacer(Modifier.width(4.dp)); Text("REC", color = Color.White, fontSize = 10.sp) }
            }
        }
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}
