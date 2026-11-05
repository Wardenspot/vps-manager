package ru.nordcam.app.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ru.nordcam.app.app

fun biometricAvailable(a: FragmentActivity) =
    BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun askBiometric(a: FragmentActivity, onOk: () -> Unit) {
    val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("NordCam")
            .setSubtitle("Подтвердите вход")
            .setNegativeButtonText("Ввести PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build(),
    )
}

@Composable
fun LockScreen(activity: FragmentActivity, onUnlock: () -> Unit) {
    val s = activity.app.store.settings.value
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val bio = s.biometric && biometricAvailable(activity)
    LaunchedEffect(Unit) { if (bio) askBiometric(activity, onUnlock) }

    fun press(d: String) {
        error = false
        entered += d
        if (entered.length >= s.pin.length) {
            if (entered == s.pin) onUnlock() else { error = true; entered = "" }
        }
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, null, tint = C.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(if (error) "Неверный PIN" else "Введите PIN", color = if (error) C.red else C.text, fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(s.pin.length) { i -> Dot(if (i < entered.length) C.accent2 else C.line, 14) }
        }
        Spacer(Modifier.height(32.dp))
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del"))
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(72.dp).background(if (k.length == 1) C.surface else C.bg, CircleShape)
                            .clickable {
                                when (k) {
                                    "del" -> entered = entered.dropLast(1)
                                    "bio" -> if (bio) askBiometric(activity, onUnlock)
                                    else -> press(k)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (k) {
                            "del" -> Icon(Icons.AutoMirrored.Filled.Backspace, "Стереть", tint = C.muted)
                            "bio" -> if (bio) Icon(Icons.Default.Fingerprint, "Отпечаток", tint = C.accent2)
                            else -> Text(k, fontSize = 26.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
