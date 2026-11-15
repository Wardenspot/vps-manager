package ru.nordkey.app.ui.setup

import android.content.Intent
import android.provider.Settings
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
import ru.nordkey.app.ui.HelpDot
import ru.nordkey.app.util.openUrl
import ru.nordkey.app.util.shareText
import ru.nordkey.app.util.shareToTelegram

enum class Phase { Idle, Running, Done, Failed }

/** Что видно на экране мастера (без логики — удобно для снимков). */
data class WizardView(
    val phase: Phase = Phase.Idle,
    val wifi: Boolean? = null,        // подключён ли телефон к Wi-Fi (null — ещё смотрим)
    val router: String? = null,       // адрес найденного Keenetic
    val searching: Boolean = true,
    val steps: Map<Step, Pair<StepStatus, String?>> = emptyMap(),
    val serverLine: String? = null,
    val result: SetupResult? = null,
    val error: String? = null,
    val self: Boolean = false,        // клиент подключает свой роутер сам (с экрана входа)
    val vpn: Boolean = false,         // на телефоне включён VPN
    val tried: List<String> = emptyList(),  // что проверили и что ответило
)

fun clientMessage(link: String) = "Здравствуйте! Ваш роутер подключён к NordKey 🎉\n\n" +
    "Откройте ссылку, чтобы привязать роутер к своему Telegram — первые сутки бесплатно:\n$link"

/** Мастер подключения роутера. [self] — клиент сам, без входа (в конце сразу открываем Telegram). */
@Composable
fun SetupWizardScreen(onBack: () -> Unit, self: Boolean = false) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var v by remember { mutableStateOf(WizardView(self = self)) }
    var login by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var net by remember { mutableStateOf<android.net.Network?>(null) }
    var found by remember { mutableStateOf<Keenetic.Companion.Found?>(null) }
    var scan by remember { mutableIntStateOf(0) }

    LaunchedEffect(scan) {
        v = v.copy(searching = true, router = null)
        val w = Keenetic.wifi(ctx)
        net = w.network
        val hosts = if (host.isNotBlank()) listOf(host.trim().removePrefix("http://").trimEnd('/')) + w.candidates else w.candidates
        val (f, tried) = Keenetic.find(hosts, w.network)
        found = f
        if (f != null) host = f.host
        v = v.copy(wifi = w.connected, router = f?.host, searching = false, vpn = w.vpn, tried = tried)
    }
    DisposableEffect(v.phase) {
        view.keepScreenOn = v.phase == Phase.Running
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = v.phase == Phase.Running) { }

    SetupContent(
        v, login, pass, name, host, onLogin = { login = it }, onPass = { pass = it }, onName = { name = it }, onHost = { host = it },
        onBack = onBack, onRescan = { scan++ },
        onWifiSettings = { try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } },
        onVpnSettings = { try { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } },
        onStart = {
            val h = v.router ?: return@SetupContent
            v = v.copy(phase = Phase.Running, steps = emptyMap(), serverLine = null, error = null, result = null)
            scope.launch {
                val f = found
                val k = Keenetic(h, if (f?.bound == false) null else net).apply { if (f != null) scheme = f.scheme }
                val setup = RouterSetup(k, name.trim(), self = self,
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
        onOpenLink = { link -> openUrl(ctx, link) },
        onShareTg = { link -> shareToTelegram(ctx, clientMessage(link)) },
        onShare = { link -> shareText(ctx, clientMessage(link), "Отправить") },
        onCopy = { link -> ru.nordkey.app.ui.staff.copy(ctx, link); ru.nordkey.app.ui.toast(ctx, "Ссылка скопирована") },
        onOpenRouter = { v.router?.let { openUrl(ctx, "http://$it/") } },
    )
}

@Composable
fun SetupContent(
    v: WizardView, login: String, pass: String, name: String, host: String,
    onLogin: (String) -> Unit, onPass: (String) -> Unit, onName: (String) -> Unit, onHost: (String) -> Unit,
    onBack: () -> Unit, onRescan: () -> Unit, onWifiSettings: () -> Unit, onVpnSettings: () -> Unit, onStart: () -> Unit, onRetry: () -> Unit,
    onOpenLink: (String) -> Unit, onShareTg: (String) -> Unit, onShare: (String) -> Unit, onCopy: (String) -> Unit, onOpenRouter: () -> Unit,
) {
    Column(Modifier.fillMaxSize().glowBackground().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (v.phase != Phase.Running) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = G.Text) }
            else Spacer(Modifier.width(48.dp))
            Text("Подключить роутер", color = G.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            HelpDot("setup")
            Spacer(Modifier.width(4.dp))
            if (v.phase == Phase.Idle) IconButton(onClick = onRescan) { Icon(Icons.Default.Refresh, "Искать роутер", tint = G.Muted) }
            else Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
               horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RouterArt(when (v.phase) { Phase.Idle -> 0; Phase.Running -> 1; Phase.Done -> 2; Phase.Failed -> 3 }, Modifier.fillMaxWidth(0.62f))
            AnimatedContent(v.phase, label = "phase") { ph ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (ph) {
                        Phase.Idle -> IdlePart(v, login, pass, name, host, onLogin, onPass, onName, onHost, onRescan, onWifiSettings, onVpnSettings, onStart)
                        Phase.Running -> RunningPart(v)
                        Phase.Done -> DonePart(v, onOpenLink, onShareTg, onShare, onCopy, onBack)
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
private fun IdlePart(v: WizardView, login: String, pass: String, name: String, host: String, onLogin: (String) -> Unit, onPass: (String) -> Unit,
                     onName: (String) -> Unit, onHost: (String) -> Unit, onRescan: () -> Unit, onWifiSettings: () -> Unit, onVpnSettings: () -> Unit,
                     onStart: () -> Unit) {
    when {
        v.searching -> StatusPill("Ищу роутер…", G.Muted)
        v.router != null -> StatusPill("Роутер найден · ${v.router}", G.Ok)
        v.wifi == false -> StatusPill("Нет подключения к Wi-Fi", G.Warn)
        v.vpn -> StatusPill("Выключите VPN на телефоне", G.Warn)
        else -> StatusPill("Роутер не найден", G.Warn)
    }
    if (!v.searching && v.router == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (v.vpn) OutlinedButton(onClick = onVpnSettings, colors = ButtonDefaults.outlinedButtonColors(contentColor = G.Warn)) {
                Icon(Icons.Default.VpnKey, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("VPN")
            }
            else OutlinedButton(onClick = onWifiSettings, colors = ButtonDefaults.outlinedButtonColors(contentColor = G.Text)) {
                Icon(Icons.Default.Wifi, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Wi-Fi")
            }
            OutlinedButton(onClick = onRescan, colors = ButtonDefaults.outlinedButtonColors(contentColor = G.Text)) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Искать снова")
            }
        }
    }
    if (!v.searching && v.router == null && v.tried.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = !open }) { Text(if (open) "Скрыть, что проверял" else "Что проверял", color = G.Muted, fontSize = 13.sp) }
        if (open) GlassCard { v.tried.forEach { Text(it, color = G.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } }
    }
    GlassCard {
        var show by remember { mutableStateOf(false) }
        if (!v.searching && v.router == null)
            OutlinedTextField(host, onHost, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Адрес роутера") },
                              placeholder = { Text("192.168.1.1") }, colors = fieldColors(),
                              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        if (!v.self) OutlinedTextField(login, onLogin, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Логин роутера") }, colors = fieldColors())
        OutlinedTextField(pass, onPass, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пароль от настроек роутера") }, colors = fieldColors(),
                          visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                          trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } })
        OutlinedTextField(name, onName, Modifier.fillMaxWidth(), singleLine = true,
                          label = { Text(if (v.self) "Как вас зовут (необязательно)" else "Имя клиента (необязательно)") }, colors = fieldColors())
    }
    GlowButton("ПУСК", enabled = v.router != null && pass.isNotBlank(), modifier = Modifier.fillMaxWidth(0.7f), onClick = onStart)
}

@Composable
private fun RunningPart(v: WizardView) {
    Text("Настраиваю роутер", color = G.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    StatusPill("не закрывайте приложение", G.Cyan)
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
private fun DonePart(v: WizardView, onOpenLink: (String) -> Unit, onShareTg: (String) -> Unit, onShare: (String) -> Unit, onCopy: (String) -> Unit,
                     onBack: () -> Unit) {
    val r = v.result ?: return
    Text(if (r.note == null) "Роутер подключён" else "Подключён, есть замечание", color = G.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
         textAlign = TextAlign.Center)
    StatusPill("адрес в VPN ${r.ip}", if (r.note == null) G.Ok else G.Warn)
    r.note?.let { Text(it, color = G.Warn, fontSize = 13.sp, textAlign = TextAlign.Center) }
    if (r.link != null && v.self) {
        // клиент сам: сразу в Telegram — там «Запустить», и роутер привязан
        GlowButton("ОТКРЫТЬ TELEGRAM", modifier = Modifier.fillMaxWidth(), onClick = { onOpenLink(r.link) })
        TextButton(onClick = { onShare(r.link) }) { Text("Отправить ссылку другому", color = G.Muted) }
    } else if (r.link != null) {
        GlassCard {
            Text(if (r.newClient) "Ссылка для клиента" else "Ссылка на бота", color = G.Muted, fontSize = 13.sp)
            Text(r.link, color = G.Cyan, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
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
        StatusPill("роутер уже был подключён — всё работает", G.Ok)
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
            Text("Wi-Fi → пароль → Пуск", color = G.Muted, fontSize = 13.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = G.Cyan)
    }
}
