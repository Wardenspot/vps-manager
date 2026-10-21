package ru.nordkey.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import ru.nordkey.app.data.Session
import ru.nordkey.app.net.Api
import ru.nordkey.app.ui.HomeScreen
import ru.nordkey.app.ui.LoginScreen
import ru.nordkey.app.ui.NK
import ru.nordkey.app.ui.NordKeyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = Session(applicationContext)
        Api.token = session.token
        setContent {
            NordKeyTheme {
              // Surface задаёт светлый цвет текста по умолчанию — иначе текст вне Scaffold рисуется чёрным
              Surface(Modifier.fillMaxSize(), color = NK.Bg, contentColor = NK.Text) {
                var token by remember { mutableStateOf(session.token) }
                DisposableEffect(Unit) {
                    val main = Handler(Looper.getMainLooper())
                    Api.onUnauthorized = { main.post { session.token = null; Api.token = null; token = null } }
                    onDispose { Api.onUnauthorized = null }
                }
                if (token == null) {
                    LoginScreen(onLoggedIn = { t -> session.token = t; Api.token = t; token = t })
                } else {
                    HomeScreen(onLogout = { session.token = null; Api.token = null; token = null })
                }
              }
            }
        }
    }
}
