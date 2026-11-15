package ru.nordkey.app.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.nordkey.app.net.Keenetic
import ru.nordkey.app.ui.Nav
import ru.nordkey.app.util.openUrl
import ru.nordkey.app.util.shareText
import ru.nordkey.app.util.shareToTelegram

enum class Phase { Idle, Running, Done, Failed }

/** Что видно на экране мастера (без логики — удобно для снимков). */
data class WizardView(
    val phase: Phase = Phase.Idle,
    val wifi: String? = null,          // «Wi-Fi: роутер 192.168.1.1» или null, если Wi-Fi нет
    val routerFound: Boolean? = null,  // null — ещё ищем
    val steps: Map<Step, Pair<StepStatus, String?>> = emptyMap(),
    val serverLine: String? = null,
    val result: SetupResult? = null,
    val error: String? = null,
)

fun clientMessage(link: String) = "Здравствуйте! Ваш роутер подключён к NordKey 🎉\n\n" +
    "Откройте ссылку, чтобы привязать роутер к своему Telegram и оформить подписку — первые сутки бесплатно:\n$link"

@Composable
fun SetupWizardScreen(nav: Nav) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var v by remember { mutableStateOf(WizardView()) }
    var login by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var gw by remember { mutableStateOf<String?>(null) }
    var net by remember { mutableStateOf<android.net.Network?>(null) }
    var scan by remember { mutableIntStateOf(0) }

    LaunchedEffect(scan) {
        val (n, g) = Keenetic.wifi(ctx)
        net = n; gw = g
        v = v.copy(wifi = g?.let { "Wi-Fi подключён · роутер $it" }, routerFound = null)
        v = v.copy(routerFound = g != null && Keenetic(g, n).probe())
    }
    DisposableEffect(v.phase) {
        view.keepScreenOn = v.phase == Phase.Running
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = v.phase == Phase.Running) { }

    SetupContent(
        v, login, pass, name, onLogin = { login = it }, onPass = { pass = it }, onName = { name = it },
        onBack = nav::back, onRescan = { scan++ },
        onStart = {
            val host = gw ?: return@SetupContent
            v = v.copy(phase = Phase.Running, steps = emptyMap(), serverLine = null, error = null, result = null)
            scope.launch {
                val setup = RouterSetup(Keenetic(host, net), name.trim(),
                                        event = { s, st, d -> v = v.copy(steps = v.steps + (s to (st to d))) },
                                        serverMsg = { m -> v = v.copy(serverLine = m) })
                v = try {
                    val r = setup.run(login.trim(), pass)
                    v.copy(phase = Phase.Done, result = r)
                } catch (e: Exception) {
                    v.copy(phase = Phase.Failed, error = e.message ?: "ошибка")
                }
            }
        },
        onRetry = { v = v.copy(phase = Phase.Idle); scan++ },
        onShareTg = { link -> shareToTelegram(ctx, clientMessage(link)) },
        onShare = { link -> shareText(ctx, clientMessage(link), "Отправить клиенту") },
        onCopy = { link -> ru.nordkey.app.ui.staff.copy(ctx, link); ru.nordkey.app.ui.toast(ctx, "Ссылка скопирована") },
        onOpenRouter = { gw?.let { openUrl(ctx, "http://$it/") } },
    )
}

@Composable
fun SetupContent(
    v: WizardView, login: String, pass: String, name: String,
    onLogin: (String) -> Unit, onPass: (String) -> Unit, onName: (String) -> Unit,
    onBack: () -> Unit, onRescan: () -> Unit, onStart: () -> Unit, onRetry: () -> Unit,
    onShareTg: (String) -> Unit, onShare: (String) -> Unit, onCopy: (String) -> Unit, onOpenRouter: () -> Unit,
) {
    Column(Modifier.fillMaxSize().glowBackground().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (v.phase != Phase.Running) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = G.Text) }
            else Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text("NordKey Setup", color = G.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("подключение роутера Keenetic", color = G.Muted, fontSize = 12.sp)
            }
            if (v.phase == Phase.Idle) IconButton(onClick = onRescan) { Icon(Icons.Default.Refresh, "Искать роутер", tint = G.Muted) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
               horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RouterArt(when (v.phase) { Phase.Idle -> 0; Phase.Running -> 1; Phase.Done -> 2; Phase.Failed -> 3 }, Modifier.fillMaxWidth(0.78f))
            AnimatedContent(v.phase, label = "phase") { ph ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (ph) {
                        Phase.Idle -> IdlePart(v, login, pass, name, onLogin, onPass, onName, onStart)
                        Phase.Running -> RunningPart(v)
                        Phase.Done -> DonePart(v, onShareTg, onShare, onCopy, onBack)
                        Phase.Failed -> FailedPart(v, onRetry, onOpenRouter)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = G.Text, unfocusedTextColor = G.Text, focusedBorderColor = G.Cyan, unfocusedBorderColor = G.Line,
    focusedLabelColor = G.Cyan, unfocusedLabelColor = G.Muted, cursorColor = G.Cyan, focusedPlaceholderColor = G.Muted,
    unfocusedPlaceholderColor = G.Muted, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    focusedTrailingIconColor = G.Muted, unfocusedTrailingIconColor = G.Muted, focusedSupportingTextColor = G.Muted, unfocusedSupportingTextColor = G.Muted)

@Composable
private fun IdlePart(v: WizardView, login: String, pass: String, name: String, onLogin: (String) -> Unit, onPass: (String) -> Unit,
                     onName: (String) -> Unit, onStart: () -> Unit) {
    when {
        v.wifi == null && v.routerFound != null -> StatusPill("Телефон не подключён к Wi-Fi", G.Warn)
        v.routerFound == null -> StatusPill("Ищу роутер…", G.Muted)
        v.routerFound == true -> StatusPill(v.wifi!!.replace("Wi-Fi подключён · роутер", "Keenetic найден ·"), G.Ok)
        else -> StatusPill("По Wi-Fi нет роутера Keenetic", G.Warn)
    }
    Text(if (v.routerFound == true) "Введите пароль администратора роутера и нажмите «Пуск» — дальше всё сделается само"
         else "Подключите телефон к Wi-Fi роутера клиента и нажмите ⟳ вверху",
         color = G.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    GlassCard {
        var show by remember { mutableStateOf(false) }
        OutlinedTextField(login, onLogin, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Логин роутера") }, colors = fieldColors())
        OutlinedTextField(pass, onPass, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пароль роутера") }, colors = fieldColors(),
                          visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                          trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } })
        OutlinedTextField(name, onName, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Имя клиента (необязательно)") },
                          placeholder = { Text("например, Иван — дача") }, colors = fieldColors(),
                          supportingText = { Text("Для нового клиента. Потом его можно поменять.") })
    }
    GlowButton("ПУСК", enabled = v.routerFound == true && pass.isNotBlank(), modifier = Modifier.fillMaxWidth(0.7f), onClick = onStart)
}

@Composable
private fun RunningPart(v: WizardView) {
    Text("Настраиваю роутер", color = G.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Не закрывайте приложение и не отключайтесь от Wi-Fi.\nДля нового клиента это займёт до 20 минут.",
         color = G.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    StepsCard(v)
}

@Composable
private fun StepsCard(v: WizardView) {
    GlassCard {
        Step.entries.forEach { s ->
            val (st, detail) = v.steps[s] ?: (StepStatus.Wait to null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    when (st) {
                        StepStatus.Run -> CircularProgressIndicator(Modifier.size(20.dp), color = G.Cyan, strokeWidth = 2.5.dp)
                        StepStatus.Done -> Icon(Icons.Default.CheckCircle, null, tint = G.Ok)
                        StepStatus.Fail -> Icon(Icons.Default.Error, null, tint = G.Bad)
                        StepStatus.Wait -> Icon(Icons.Default.RadioButtonUnchecked, null, tint = G.Line)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = if (st == StepStatus.Wait) G.Muted else G.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (!detail.isNullOrBlank()) Text(detail, color = if (st == StepStatus.Fail) G.Bad else G.Muted, fontSize = 12.sp)
                }
            }
        }
        v.serverLine?.let {
            HorizontalDivider(color = G.Line)
            Text("Сервер: $it", color = G.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun DonePart(v: WizardView, onShareTg: (String) -> Unit, onShare: (String) -> Unit, onCopy: (String) -> Unit, onBack: () -> Unit) {
    val r = v.result ?: return
    Text(if (r.note == null) "Роутер подключён" else "Роутер подключён, но есть замечание", color = G.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
         textAlign = TextAlign.Center)
    StatusPill("адрес в VPN ${r.ip}", if (r.note == null) G.Ok else G.Warn)
    r.note?.let { Text(it, color = G.Warn, fontSize = 13.sp, textAlign = TextAlign.Center) }
    if (r.link != null) {
        GlassCard {
            Text(if (r.newClient) "Ссылка для клиента" else "Ссылка на бота", color = G.Muted, fontSize = 13.sp)
            Text(r.link, color = G.Cyan, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            Text("По ней клиент привяжет роутер к своему Telegram и оформит подписку. Первые сутки бесплатно.", color = G.Muted, fontSize = 12.sp)
        }
        GlowButton("ОТПРАВИТЬ В TELEGRAM", modifier = Modifier.fillMaxWidth(), onClick = { onShareTg(r.link) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onCopy(r.link) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = G.Text)) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Копировать")
            }
            OutlinedButton(onClick = { onShare(r.link) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = G.Text)) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Другое")
            }
        }
    } else {
        Text("Роутер уже был клиентом NordKey — ссылка не нужна, всё работает как прежде.", color = G.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
    TextButton(onClick = onBack) { Text("Готово", color = G.Muted) }
}

@Composable
private fun FailedPart(v: WizardView, onRetry: () -> Unit, onOpenRouter: () -> Unit) {
    Text("Не получилось", color = G.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    GlassCard { Text(v.error ?: "ошибка", color = G.Bad, fontSize = 14.sp) }
    if (v.steps.isNotEmpty()) StepsCard(v)
    GlowButton("ПОВТОРИТЬ", modifier = Modifier.fillMaxWidth(0.7f), onClick = onRetry)
    OutlinedButton(onClick = onOpenRouter, colors = ButtonDefaults.outlinedButtonColors(contentColor = G.Text)) {
        Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Открыть настройки роутера")
    }
}

/** Карточка на главной: быстрый вход в мастер. */
@Composable
fun SetupHeroCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(Modifier.fillMaxWidth().clip(shape).glowBackground().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RouterArt(0, Modifier.width(96.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Подключить роутер", color = G.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Телефон в Wi-Fi клиента → пароль роутера → «Пуск». Ссылка для клиента — в конце.", color = G.Muted, fontSize = 13.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = G.Cyan)
    }
}
