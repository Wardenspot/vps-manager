package ru.nordkey.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.nordkey.app.net.Api
import ru.nordkey.app.net.obj
import ru.nordkey.app.util.openUrl

@Composable
fun LoginScreen(onLoggedIn: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var req by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    if (setup) {
        // первичная настройка: клиент сам подключает свой роутер, в конце — Telegram
        ru.nordkey.app.ui.setup.SetupWizardScreen(onBack = { setup = false }, self = true)
        return
    }

    LaunchedEffect(req) {
        val r = req ?: return@LaunchedEffect
        openUrl(ctx, r.getString("bot_url"))
        val until = System.currentTimeMillis() + r.optInt("expires_in", 300) * 1000L
        while (System.currentTimeMillis() < until) {
            delay(2000)
            val p = try {
                Api.call("POST", "/v1/auth/poll", obj("code" to r.getString("code"), "secret" to r.getString("secret")))
            } catch (e: Exception) { continue }
            when (p.optString("status")) {
                "ok" -> { onLoggedIn(p.getString("token")); return@LaunchedEffect }
                "denied" -> { message = "Вход отклонён в Telegram. Если входили вы — попробуйте ещё раз и выберите правильное число."; req = null; return@LaunchedEffect }
                "expired", "unknown" -> { message = "Время на подтверждение вышло. Попробуйте ещё раз."; req = null; return@LaunchedEffect }
            }
        }
        message = "Время на подтверждение вышло. Попробуйте ещё раз."
        req = null
    }

    Column(
        Modifier.fillMaxSize().nkBackground().statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(NK.Grad)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Key, null, tint = NK.OnGrad, modifier = Modifier.size(46.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("NordKey", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = NK.Text)
        Text("VPN для дома и роутера", color = NK.Muted, fontSize = 15.sp)
        Spacer(Modifier.height(36.dp))

        val r = req
        if (r == null) {
            NkButton(if (busy) "Подключаюсь…" else "Войти через Telegram", enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; message = null
                    scope.launch {
                        try {
                            req = Api.call("POST", "/v1/auth/start", obj("device" to "${Build.MANUFACTURER} ${Build.MODEL}".trim()))
                        } catch (e: Exception) {
                            message = e.message
                        }
                        busy = false
                    }
                })
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { setup = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(50),
                           border = androidx.compose.foundation.BorderStroke(1.dp, NK.Line)) {
                Icon(Icons.Default.Router, null, tint = NK.Link); Spacer(Modifier.width(8.dp))
                Text("Подключить роутер", color = NK.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            HelpLink("Как это работает", "setup")
        } else {
            NkCard {
                Text("В Telegram нажмите это число:", color = NK.Muted, fontSize = 14.sp)
                Text(r.optInt("pick").toString(), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = NK.Link,
                     modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { HelpLink("Зачем число?", "login") }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NK.Link)
                Spacer(Modifier.width(10.dp))
                Text("Жду подтверждения в Telegram…", color = NK.Muted)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { openUrl(ctx, r.getString("bot_url")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть Telegram ещё раз")
            }
            TextButton(onClick = { req = null }) { Text("Отмена", color = NK.Muted) }
        }
        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = NK.WarnText, textAlign = TextAlign.Center)
        }
    }
}
