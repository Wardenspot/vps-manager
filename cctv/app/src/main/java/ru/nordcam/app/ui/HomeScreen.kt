package ru.nordcam.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ru.nordcam.app.Nav
import ru.nordcam.app.app

@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val events by ctx.app.store.events.collectAsState()
    val unseen = events.count { !it.seen }
    var tab by nav.tab
    val screen = rememberWinSize()
    val home by ctx.app.store.home.collectAsState()
    val settings by ctx.app.store.settings.collectAsState()
    val showHome = home.isNotEmpty() || settings.haReady
    // Вкладка «Дом» появляется, когда подключён Home Assistant или добавлены свои кнопки
    val all = listOf(
        Tab4("live", Icons.Default.GridView, "Просмотр", 0),
        Tab4("home", Icons.Default.Home, "Дом", 0),
        Tab4("archive", Icons.Default.History, "Архив", 0),
        Tab4("events", Icons.Default.Notifications, "События", unseen),
        Tab4("cameras", Icons.Default.Videocam, "Камеры", 0),
        Tab4("more", Icons.Default.Menu, "Ещё", 0),
    ).filter { it.key != "home" || showHome }
    if (all.none { it.key == tab }) tab = "live"

    @Composable
    fun TabIcon(t: Tab4) {
        if (t.badge > 0) BadgedBox(badge = { Badge { Text(if (t.badge > 99) "99+" else "${t.badge}") } }) { Icon(t.icon, null) }
        else Icon(t.icon, null)
    }

    @Composable
    fun Content() {
        when (tab) {
            "live" -> LiveTab(nav)
            "home" -> Centered(1100.dp) { HomeTab(nav) }
            "archive" -> Centered { ArchiveTab(nav) }
            "events" -> Centered { EventsTab(nav) }
            "cameras" -> Centered { DevicesTab(nav) }
            else -> Centered { MoreTab(nav) }
        }
    }

    if (screen.wide || (screen.short && screen.landscape)) {
        // Развёрнутый складной, планшет или телефон боком: меню слева, экран не теряет высоту
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = C.surface) {
                val colors = NavigationRailItemDefaults.colors(indicatorColor = C.surface2, selectedIconColor = C.accent2, selectedTextColor = C.accent2)
                Spacer(Modifier.weight(1f))
                all.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t.key, onClick = { tab = t.key }, icon = { TabIcon(t) },
                        label = if (screen.short) null else ({ Text(t.label) }), colors = colors,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { Content() }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { Content() }
        NavigationBar(containerColor = C.surface) {
            val colors = NavigationBarItemDefaults.colors(indicatorColor = C.surface2, selectedIconColor = C.accent2, selectedTextColor = C.accent2)
            all.forEach { t ->
                NavigationBarItem(
                    selected = tab == t.key, onClick = { tab = t.key }, icon = { TabIcon(t) },
                    label = { Text(t.label, maxLines = 1, fontSize = if (all.size > 5) 10.sp else 12.sp) },
                    alwaysShowLabel = !screen.tiny && screen.widthDp >= 360, colors = colors,
                )
            }
        }
    }
}

private data class Tab4(val key: String, val icon: ImageVector, val label: String, val badge: Int)
