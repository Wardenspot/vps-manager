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
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import ru.nordkey.app.data.Session
import ru.nordkey.app.net.Api
import ru.nordkey.app.ui.HomeScreen
import ru.nordkey.app.ui.LoginScreen
import ru.nordkey.app.ui.NK
import ru.nordkey.app.ui.NordKeyTheme
import ru.nordkey.app.ui.ThemePref

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = Session(applicationContext)
        Api.token = session.token
        ThemePref.load(applicationContext)
        setContent {
            NordKeyTheme {
              // строка состояния и панель навигации — в цвет темы, значки тёмные на светлой
              SideEffect {
                  window.statusBarColor = NK.Bg.toArgb()
                  window.navigationBarColor = NK.Bar.toArgb()
                  WindowCompat.getInsetsController(window, window.decorView).apply {
                      isAppearanceLightStatusBars = !NK.dark
                      isAppearanceLightNavigationBars = !NK.dark
                  }
              }
              // Surface задаёт цвет текста по умолчанию — иначе текст вне Scaffold рисуется не тем цветом
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
