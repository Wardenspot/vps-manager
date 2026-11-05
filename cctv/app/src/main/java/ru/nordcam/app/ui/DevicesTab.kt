package ru.nordcam.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.MotionMode
import ru.nordcam.app.data.RecordMode
import ru.nordcam.app.data.Vendors

@Composable
fun DevicesTab(nav: Nav) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val cams by store.cameras.collectAsState()
    var del by remember { mutableStateOf<Camera?>(null) }
    val grouped = cams.groupBy { it.group }.toSortedMap(compareBy<String> { it.ifBlank { "\uFFFF" } })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Bar("Камеры") {
                IconButton(onClick = { nav.go(Screen.Import()) }) { Icon(Icons.Default.QrCodeScanner, "Добавить по коду доступа") }
            }
            if (cams.isEmpty()) {
                Empty("Камер пока нет", "Нажмите «Добавить». Подойдёт любая камера или регистратор с RTSP или ONVIF.")
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (g, list) ->
                    item(key = "g$g") {
                        Text(
                            (g.ifBlank { "Без объекта" }).uppercase(), color = C.muted, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { c ->
                        CamRow(c,
                            onOpen = { nav.go(Screen.Live(c.id)) },
                            onEdit = { nav.go(Screen.EditCamera(c.id)) },
                            onUp = { store.move(c.id, -1) },
                            onDown = { store.move(c.id, 1) },
                            onDelete = { del = c },
                        )
                        HorizontalDivider(color = C.line)
                    }
                }
                item { Spacer(Modifier.size(96.dp)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { nav.go(Screen.EditCamera(null)) },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Добавить") },
            containerColor = C.accent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    del?.let { c ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("Удалить «${c.title}»?") },
            text = { Text("Камера пропадёт из приложения. Записи на телефоне останутся в папке приложения до очистки.") },
            confirmButton = { TextButton(onClick = { store.delete(c.id); ctx.app.recorder.stop(c.id); del = null }) { Text("Удалить", color = C.red) } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun CamRow(c: Camera, onOpen: () -> Unit, onEdit: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (c.shared) Icons.Default.People else Icons.Default.Videocam, null, tint = if (c.perms.expired) C.muted else C.accent2)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title)
            Text(
                if (c.shared) (if (c.perms.expired) "Доступ истёк" else "Доступ от другого пользователя")
                else "${Vendors.of(c.vendor).title.substringBefore(" /")} · ${c.host}${if (c.channel > 1) " · к${c.channel}" else ""}",
                color = C.muted, fontSize = 13.sp, maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.ptz) Badge(Icons.Default.OpenWith, "PTZ")
                if (c.motion != MotionMode.OFF) Badge(Icons.Default.DirectionsRun, "Движение")
                if (c.record != RecordMode.OFF) Badge(Icons.Default.FiberManualRecord, if (c.record == RecordMode.CONTINUOUS) "Запись 24/7" else "Запись")
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню", tint = C.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Настройки") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Выше") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, onClick = { menu = false; onUp() })
                DropdownMenuItem(text = { Text("Ниже") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, onClick = { menu = false; onDown() })
                DropdownMenuItem(text = { Text("Удалить", color = C.red) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = C.red) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, t: String) {
    Row(Modifier.padding(end = 10.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.muted, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(t, color = C.muted, fontSize = 11.sp)
    }
}
