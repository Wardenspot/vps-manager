package ru.nordcam.app.ui

import androidx.compose.foundation.layout.Column
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
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            when (tab) {
                0 -> LiveTab(nav)
                1 -> ArchiveTab(nav)
                2 -> EventsTab(nav)
                3 -> DevicesTab(nav)
                else -> MoreTab(nav)
            }
        }
        NavigationBar(containerColor = C.surface) {
            val colors = NavigationBarItemDefaults.colors(indicatorColor = C.surface2, selectedIconColor = C.accent2, selectedTextColor = C.accent2)
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.GridView, null) }, label = { Text("Просмотр") }, colors = colors)
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.History, null) }, label = { Text("Архив") }, colors = colors)
            NavigationBarItem(tab == 2, { tab = 2 }, {
                BadgedBox(badge = { if (unseen > 0) Badge { Text(if (unseen > 99) "99+" else "$unseen") } }) {
                    Icon(Icons.Default.Notifications, null)
                }
            }, label = { Text("События") }, colors = colors)
            NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Videocam, null) }, label = { Text("Камеры") }, colors = colors)
            NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Default.Menu, null) }, label = { Text("Ещё") }, colors = colors)
        }
    }
}
