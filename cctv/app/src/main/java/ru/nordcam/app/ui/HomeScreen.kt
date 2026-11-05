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
    val items = listOf(
        Triple(Icons.Default.GridView, "Просмотр", 0),
        Triple(Icons.Default.History, "Архив", 0),
        Triple(Icons.Default.Notifications, "События", unseen),
        Triple(Icons.Default.Videocam, "Камеры", 0),
        Triple(Icons.Default.Menu, "Ещё", 0),
    )

    @Composable
    fun TabIcon(i: Int) {
        val (icon, _, badge) = items[i]
        if (badge > 0) BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else "$badge") } }) { Icon(icon, null) }
        else Icon(icon, null)
    }

    @Composable
    fun Content() {
        when (tab) {
            0 -> LiveTab(nav)
            1 -> Centered { ArchiveTab(nav) }
            2 -> Centered { EventsTab(nav) }
            3 -> Centered { DevicesTab(nav) }
            else -> Centered { MoreTab(nav) }
        }
    }

    if (screen.wide || (screen.short && screen.landscape)) {
        // Развёрнутый складной, планшет или телефон боком: меню слева, экран не теряет высоту
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = C.surface) {
                val colors = NavigationRailItemDefaults.colors(indicatorColor = C.surface2, selectedIconColor = C.accent2, selectedTextColor = C.accent2)
                Spacer(Modifier.weight(1f))
                items.forEachIndexed { i, (_, label, _) ->
                    NavigationRailItem(
                        selected = tab == i, onClick = { tab = i }, icon = { TabIcon(i) },
                        label = if (screen.short) null else ({ Text(label) }), colors = colors,
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
            items.forEachIndexed { i, (_, label, _) ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i }, icon = { TabIcon(i) },
                    label = { Text(label, maxLines = 1) }, alwaysShowLabel = !screen.tiny, colors = colors,
                )
            }
        }
    }
}
