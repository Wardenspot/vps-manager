package ru.nordkey.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Подсказки: на экранах — только кружок «?», текст — по нажатию и в разделе «Помощь». */
object Help {
    data class Topic(val id: String, val title: String, val text: String, val staff: Boolean = false)

    val all = listOf(
        Topic("login", "Как войти", "Вход — через Telegram-бота NordKey, пароль не нужен. Бот покажет три числа — нажмите то, что видите в приложении. " +
              "Так никто не войдёт в ваш аккаунт по чужой ссылке."),
        Topic("setup", "Подключить роутер", "Подключите телефон к Wi-Fi роутера, введите пароль от его настроек (обычно на наклейке снизу — «Пароль администратора») " +
              "и нажмите «Пуск». Не закрывайте приложение, пока идёт настройка: новый роутер настраивается до 20 минут. " +
              "В конце откроется Telegram — нажмите «Запустить», и роутер привяжется к вам. Первые сутки бесплатно."),
        Topic("no_routers", "Не вижу свой роутер", "Если роутер настроил NordKey — откройте персональную ссылку из бота. " +
              "Если роутер оформлен на другой аккаунт Telegram — получите там код: «🛜 Мой роутер» → «👨‍👩‍👧 Семейный доступ», и введите его здесь."),
        Topic("family", "Семейный доступ", "Родные видят роутер со своего Telegram бесплатно: добавляют сайты и пишут в поддержку. " +
              "Подписку оплачивает владелец. Пригласить: «🛜 Мой роутер» → «👨‍👩‍👧 Семейный доступ»."),
        Topic("devices", "VPN на телефоне и компьютере", "1. Установите приложение AmneziaWG.\n2. Нажмите «+» → «Сканировать QR» и наведите на код — " +
              "или «Поделиться файлом» и откройте его в AmneziaWG.\n3. Включите VPN.\n\nКонфигурации выдаёт бот: «📄 Мой конфиг». " +
              "Для роутера отдельный конфиг не нужен. Не показывайте QR-код посторонним — по нему можно подключиться к вашему VPN."),
        Topic("subscription", "Оплата и подписка", "Оплата — в Telegram-боте NordKey, после оплаты срок здесь обновится сам. " +
              "Ещё одно устройство (телефон, компьютер) — в боте: «📄 Мой конфиг» → «➕ Добавить устройство»."),
        Topic("support", "Поддержка", "Опишите проблему своими словами — например «не открывается YouTube на телевизоре». " +
              "Поддержка сразу проверит ваш роутер и ответит здесь и в Telegram."),
        Topic("router_web", "Настройки роутера", "Открываются из любого места, даже когда VPN не работает: облачная ссылка и пароль. " +
              "Пароль показывается после кода из Telegram."),
        Topic("game", "Игровой режим", "Если игра через VPN идёт с большим пингом, подскажет пустить её сервер напрямую."),
        Topic("old_server", "Старый сервер", "Роутер ещё на старом сервере — часть функций недоступна, пока его не перенесут."),
        Topic("autopilot", "Автопилот", "Сам чинит простое: перезапуск служб сервера, чистка диска, DNS на роутере, WARP. " +
              "Остальное собирает в запрос — скопируйте его и отправьте Claude.", staff = true),
        Topic("roles", "Роли", "Админ — всё, кроме ролей.\nТехник — роутеры, новые клиенты, батники (без денег и рассылок).\n" +
              "Поддержка — смотреть клиентов и отвечать.", staff = true),
        Topic("bat", "Батник", "Запускается на компьютере, подключённом к роутеру Keenetic: старых клиентов переносит на новый сервер, новых — подключает. " +
              "Адрес роутера нужен, только если он нестандартный.", staff = true),
        Topic("newclient", "Новый клиент", "Проще всего — мастер «Подключить роутер»: телефон в Wi-Fi клиента → пароль роутера → «Пуск», в конце ссылка клиенту. " +
              "Без телефона рядом — батник (Ещё → Батник).", staff = true),
        Topic("agent", "ИИ-помощник", "Найдёт клиента по @нику, имени или адресу, проверит и починит роутер, сайт, DNS, перенесёт подписку. " +
              "Если нужно спросить роутер — до минуты.", staff = true),
    )

    fun of(id: String) = all.first { it.id == id }
}

/** Кружок «?» — по нажатию открывает подсказку. */
@Composable
fun HelpDot(id: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.size(22.dp).clip(CircleShape).background(NK.Link.copy(alpha = 0.14f)).clickable { open = true },
        contentAlignment = Alignment.Center) {
        Text("?", color = NK.Link, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    if (open) {
        val t = Help.of(id)
        AlertDialog(onDismissRequest = { open = false }, containerColor = NK.Surface,
                    title = { Text(t.title, color = NK.Text) }, text = { Text(t.text, color = NK.Text, fontSize = 15.sp) },
                    confirmButton = { TextButton(onClick = { open = false }) { Text("Понятно") } })
    }
}

/** Заголовок с кружком «?». */
@Composable
fun TitleHelp(text: String, help: String, modifier: Modifier = Modifier, size: Int = 16) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = NK.Text, fontSize = size.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        HelpDot(help)
    }
}

@Composable
fun HelpScreen(me: Me?, nav: Nav) {
    val staff = me?.can("clients.view") == true
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Помощь", onBack = nav::back)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Help.all.filter { !it.staff || staff }) { t ->
                NkCard(onClick = { open = if (open == t.id) null else t.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, color = NK.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Icon(if (open == t.id) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = NK.Muted)
                    }
                    if (open == t.id) Text(t.text, color = NK.Text, fontSize = 15.sp)
                }
            }
        }
    }
}

/** Ссылка-подсказка: «? текст» — открывает ту же подсказку. */
@Composable
fun HelpLink(text: String, id: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).clickable { open = true }.padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(NK.Link.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("?", color = NK.Link, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, color = NK.Link, fontSize = 14.sp)
    }
    if (open) {
        val t = Help.of(id)
        AlertDialog(onDismissRequest = { open = false }, containerColor = NK.Surface,
                    title = { Text(t.title, color = NK.Text) }, text = { Text(t.text, color = NK.Text, fontSize = 15.sp) },
                    confirmButton = { TextButton(onClick = { open = false }) { Text("Понятно") } })
    }
}
