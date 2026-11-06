package ru.nordcam.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Ikev2VpnProfile
import android.net.NetworkCapabilities
import android.net.VpnManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.api.IOpenVPNAPIService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Config
import ru.nordcam.app.data.Store
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.coroutines.resume

enum class VpnStatus { OFF, CONNECTING, ON, ERROR }

data class VpnState(val status: VpnStatus = VpnStatus.OFF, val message: String = "", val profileId: String = "")

/** Результат попытки подключиться: готово, нужно показать экран Android, нужно разрешение, или ошибка. */
sealed class VpnResult {
    data object Ok : VpnResult()
    data class NeedIntent(val intent: Intent, val why: String) : VpnResult()
    data class NeedPermission(val permission: String) : VpnResult()
    data class Error(val message: String) : VpnResult()
}

/**
 * Подключение к дому через VPN. Пять способов (см. [VpnKind]): встроенные WireGuard/AmneziaWG и IKEv2,
 * OpenVPN через приложение «OpenVPN for Android», туннель приложения WireGuard/AmneziaWG и любой VPN телефона.
 */
object HomeVpn {
    const val OPENVPN_PKG = "de.blinkt.openvpn"
    val wgApps = listOf("com.wireguard.android" to "WireGuard", "org.amnezia.awg" to "AmneziaWG")

    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state

    // ---------------------------------------------------------------- встроенный WireGuard / AmneziaWG

    @Volatile private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "nordcam"
        override fun onStateChange(newState: Tunnel.State) {
            val cur = _state.value
            if (newState == Tunnel.State.DOWN && cur.status == VpnStatus.ON) _state.value = cur.copy(status = VpnStatus.OFF, message = "")
        }
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    private fun backend(ctx: Context): GoBackend = backend ?: synchronized(this) {
        backend ?: GoBackend(ctx.applicationContext, NoopTunnelActionHandler()).also { backend = it }
    }

    /** Через туннель — только NordCam: добавляем IncludedApplications и убираем исключения. */
    private fun prepareConfig(ctx: Context, p: VpnProfile): String {
        val lines = p.config.replace("\r", "").lines().toMutableList()
        if (!p.appOnly) return lines.joinToString("\n")
        lines.removeAll { l ->
            val k = l.substringBefore('=').trim().lowercase()
            k == "includedapplications" || k == "excludedapplications"
        }
        val i = lines.indexOfFirst { it.trim().equals("[Interface]", true) }
        if (i >= 0) lines.add(i + 1, "IncludedApplications = ${ctx.packageName}")
        return lines.joinToString("\n")
    }

    fun parseWg(text: String): Config = Config.parse(BufferedReader(StringReader(text)))

    fun configError(e: Throwable): String = when (e) {
        is BadConfigException -> "Ошибка в конфиге: раздел ${e.section.getName()}, поле ${e.location.getName()}" +
            (e.text?.let { " = «$it»" } ?: "")
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------------------------------------------------------------- общие проверки

    /** Включён ли сейчас какой-нибудь VPN на телефоне (в этом профиле Android). */
    @Suppress("DEPRECATION")
    fun systemVpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Виден ли дом напрямую (без VPN): TCP-подключение к адресу проверки. */
    suspend fun homeReachable(store: Store, p: VpnProfile): Boolean? {
        val target = p.probe.ifBlank {
            store.cameras.value.firstOrNull { !it.shared && it.host.isNotBlank() }?.let { "${it.host}:${it.rtspPort}" }.orEmpty()
        }
        if (target.isBlank()) return null
        val host = target.substringBeforeLast(':').trim().removePrefix("[").removeSuffix("]")
        val port = target.substringAfterLast(':', "").toIntOrNull() ?: 554
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 1500) }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    // ---------------------------------------------------------------- подключение

    /**
     * Подключить профиль. [attempt] — номер повтора после показа экрана Android (разрешение, настройки):
     * помогает не зациклиться, если человек отказался.
     */
    suspend fun connect(ctx: Context, p: VpnProfile, attempt: Int = 0): VpnResult {
        _state.value = VpnState(VpnStatus.CONNECTING, "Подключаюсь…", p.id)
        val r = try {
            when (p.kind) {
                VpnKind.WIREGUARD -> connectWg(ctx, p)
                VpnKind.IKEV2 -> connectIke(ctx, p)
                VpnKind.OPENVPN -> connectOpenVpn(ctx, p)
                VpnKind.APP_WIREGUARD -> connectWgApp(ctx, p)
                VpnKind.SYSTEM -> connectSystem(ctx, attempt)
            }
        } catch (e: Throwable) {
            VpnResult.Error(e.message ?: e.javaClass.simpleName)
        }
        _state.value = when (r) {
            VpnResult.Ok -> VpnState(VpnStatus.ON, "Подключено", p.id)
            is VpnResult.Error -> VpnState(VpnStatus.ERROR, r.message, p.id)
            else -> VpnState(VpnStatus.OFF, "", p.id)
        }
        return r
    }

    suspend fun disconnect(ctx: Context, p: VpnProfile): VpnResult {
        val r = try {
            when (p.kind) {
                VpnKind.WIREGUARD -> withContext(Dispatchers.IO) { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null); VpnResult.Ok }
                VpnKind.IKEV2 -> {
                    if (Build.VERSION.SDK_INT >= 30) ctx.getSystemService(VpnManager::class.java)?.stopProvisionedVpnProfile()
                    VpnResult.Ok
                }
                VpnKind.OPENVPN -> withOpenVpn(ctx) { it.disconnect(); VpnResult.Ok } ?: VpnResult.Error("Нет связи с OpenVPN for Android")
                VpnKind.APP_WIREGUARD -> {
                    sendWgApp(ctx, p, up = false)
                    VpnResult.Ok
                }
                VpnKind.SYSTEM -> VpnResult.NeedIntent(Intent(Settings.ACTION_VPN_SETTINGS), "Отключите VPN в настройках")
            }
        } catch (e: Throwable) {
            VpnResult.Error(e.message ?: "Ошибка")
        }
        if (r == VpnResult.Ok) _state.value = VpnState(VpnStatus.OFF, "", p.id)
        return r
    }

    /** Обновить состояние (для индикатора). */
    suspend fun refresh(ctx: Context, p: VpnProfile?) {
        if (p == null) { _state.value = VpnState(); return }
        val cur = _state.value
        if (cur.status == VpnStatus.CONNECTING) return
        val on = withContext(Dispatchers.IO) {
            when (p.kind) {
                VpnKind.WIREGUARD -> try { backend?.getState(tunnel) == Tunnel.State.UP } catch (e: Exception) { false }
                else -> systemVpnActive(ctx)
            }
        }
        if (on) _state.value = VpnState(VpnStatus.ON, "Подключено", p.id)
        else if (cur.status == VpnStatus.ON || cur.profileId != p.id) _state.value = VpnState(VpnStatus.OFF, "", p.id)
    }

    private suspend fun connectWg(ctx: Context, p: VpnProfile): VpnResult {
        android.net.VpnService.prepare(ctx)?.let { return VpnResult.NeedIntent(it, "Разрешите NordCam создать VPN-подключение") }
        val cfg = try { parseWg(prepareConfig(ctx, p)) } catch (e: Exception) { return VpnResult.Error(configError(e)) }
        return withContext(Dispatchers.IO) {
            val st = backend(ctx).setState(tunnel, Tunnel.State.UP, cfg)
            if (st == Tunnel.State.UP) VpnResult.Ok else VpnResult.Error("Туннель не поднялся")
        }
    }

    // ---------------------------------------------------------------- IKEv2 (встроенный VPN Android)

    private fun cert(b64: String): X509Certificate? {
        if (b64.isBlank()) return null
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    fun p12Info(b64: String, pass: String): Pair<X509Certificate, PrivateKey> {
        val ks = KeyStore.getInstance("PKCS12")
        try {
            ks.load(ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT)), pass.toCharArray())
        } catch (e: Exception) {
            throw IllegalArgumentException("Не удалось открыть сертификат .p12 — проверьте пароль от него")
        }
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: throw IllegalArgumentException("В .p12 нет закрытого ключа")
        val key = ks.getKey(alias, pass.toCharArray()) as PrivateKey
        val crt = ks.getCertificate(alias) as X509Certificate
        return crt to key
    }

    private fun connectIke(ctx: Context, p: VpnProfile): VpnResult {
        if (Build.VERSION.SDK_INT < 30) return VpnResult.Error("Встроенный IKEv2 работает с Android 11. Выберите другой способ.")
        if (p.server.isBlank()) return VpnResult.Error("Укажите адрес сервера")
        val vm = ctx.getSystemService(VpnManager::class.java) ?: return VpnResult.Error("VPN недоступен на этом телефоне")
        val ca = try { cert(p.ca) } catch (e: Exception) { return VpnResult.Error("Не удалось прочитать сертификат сервера (CA)") }
        val b = Ikev2VpnProfile.Builder(p.server.trim(), p.identity.ifBlank { p.user.ifBlank { "nordcam" } })
        when (p.ikeAuth) {
            IkeAuth.PSK -> {
                if (p.psk.isBlank()) return VpnResult.Error("Укажите общий ключ (PSK)")
                b.setAuthPsk(p.psk.toByteArray())
            }
            IkeAuth.USER_PASS -> {
                if (p.user.isBlank()) return VpnResult.Error("Укажите логин")
                b.setAuthUsernamePassword(p.user, p.pass, ca)
            }
            IkeAuth.CERT -> {
                if (p.p12.isBlank()) return VpnResult.Error("Выберите файл сертификата .p12")
                val (crt, key) = p12Info(p.p12, p.p12Pass)
                b.setAuthDigitalSignature(crt, key, ca)
            }
        }
        b.setBypassable(true)
        val profile = b.build()
        vm.provisionVpnProfile(profile)?.let { return VpnResult.NeedIntent(it, "Разрешите NordCam управлять VPN") }
        vm.startProvisionedVpnProfile()
        return VpnResult.Ok
    }

    // ---------------------------------------------------------------- OpenVPN for Android

    /** Подключиться к сервису OpenVPN for Android, выполнить действие и отключиться. */
    suspend fun <T> withOpenVpn(ctx: Context, block: (IOpenVPNAPIService) -> T): T? {
        if (!installed(ctx, OPENVPN_PKG)) return null
        var conn: ServiceConnection? = null
        val svc = withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine<IOpenVPNAPIService?> { cont ->
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (cont.isActive) cont.resume(IOpenVPNAPIService.Stub.asInterface(binder))
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                conn = c
                val i = Intent(IOpenVPNAPIService::class.java.name).setPackage(OPENVPN_PKG)
                val ok = try { ctx.applicationContext.bindService(i, c, Context.BIND_AUTO_CREATE) } catch (e: Exception) { false }
                if (!ok && cont.isActive) cont.resume(null)
            }
        }
        return try {
            if (svc == null) null else withContext(Dispatchers.IO) { block(svc) }
        } finally {
            conn?.let { try { ctx.applicationContext.unbindService(it) } catch (_: Exception) {} }
        }
    }

    /** Профили, уже настроенные в OpenVPN for Android. Пусто — не установлено или нет доступа. */
    sealed class OvpnList {
        data class Profiles(val list: List<Pair<String, String>>) : OvpnList()
        data class NeedIntent(val intent: Intent) : OvpnList()
        data class Error(val message: String) : OvpnList()
    }

    suspend fun openVpnProfiles(ctx: Context): OvpnList {
        if (!installed(ctx, OPENVPN_PKG)) return OvpnList.Error("Приложение «OpenVPN for Android» не установлено")
        return withOpenVpn(ctx) { s ->
            s.prepare(ctx.packageName)?.let { return@withOpenVpn OvpnList.NeedIntent(it) }
            OvpnList.Profiles(s.profiles.orEmpty().map { it.mUUID to it.mName })
        } ?: OvpnList.Error("Нет связи с OpenVPN for Android")
    }

    private suspend fun connectOpenVpn(ctx: Context, p: VpnProfile): VpnResult {
        if (!installed(ctx, OPENVPN_PKG)) {
            return VpnResult.NeedIntent(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$OPENVPN_PKG")),
                "Установите «OpenVPN for Android»",
            )
        }
        val step = withOpenVpn(ctx) { s ->
            s.prepare(ctx.packageName)?.let { return@withOpenVpn VpnResult.NeedIntent(it, "Разрешите NordCam управлять OpenVPN") }
            s.prepareVPNService()?.let { return@withOpenVpn VpnResult.NeedIntent(it, "Разрешите OpenVPN создать подключение") }
            when {
                p.remoteId.isNotBlank() -> s.startProfile(p.remoteId)
                p.config.isNotBlank() -> s.startVPN(p.config)
                else -> return@withOpenVpn VpnResult.Error("Выберите профиль OpenVPN или импортируйте .ovpn")
            }
            VpnResult.Ok
        } ?: return VpnResult.Error("Нет связи с OpenVPN for Android")
        if (step != VpnResult.Ok) return step
        return waitSystemVpn(ctx, "OpenVPN не подключился — проверьте профиль в OpenVPN for Android")
    }

    // ---------------------------------------------------------------- приложения WireGuard / AmneziaWG

    private fun wgPrefix(pkg: String) = if (pkg.startsWith("org.amnezia")) "org.amnezia.awg" else "com.wireguard.android"

    fun wgPermission(pkg: String) = "$pkg.permission.CONTROL_TUNNELS"

    private fun sendWgApp(ctx: Context, p: VpnProfile, up: Boolean) {
        val action = wgPrefix(p.appPackage) + if (up) ".action.SET_TUNNEL_UP" else ".action.SET_TUNNEL_DOWN"
        ctx.sendBroadcast(Intent(action).setPackage(p.appPackage).putExtra("tunnel", p.remoteName.trim()))
    }

    private suspend fun connectWgApp(ctx: Context, p: VpnProfile): VpnResult {
        val app = wgApps.firstOrNull { it.first == p.appPackage }?.second ?: "WireGuard"
        if (!installed(ctx, p.appPackage)) return VpnResult.Error("Приложение $app не установлено")
        if (p.remoteName.isBlank()) return VpnResult.Error("Впишите имя туннеля, как в приложении $app")
        val perm = wgPermission(p.appPackage)
        if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) return VpnResult.NeedPermission(perm)
        sendWgApp(ctx, p, up = true)
        return waitSystemVpn(
            ctx,
            "$app не включил туннель «${p.remoteName}». Проверьте имя туннеля и что в настройках $app включено " +
                "«Разрешить управление другим приложениям». Если $app запускается впервые — откройте его один раз и разрешите VPN.",
        )
    }

    // ---------------------------------------------------------------- VPN телефона

    private suspend fun connectSystem(ctx: Context, attempt: Int): VpnResult {
        if (systemVpnActive(ctx)) return VpnResult.Ok
        if (attempt > 0) return VpnResult.Error("VPN не включён")
        return VpnResult.NeedIntent(Intent(Settings.ACTION_VPN_SETTINGS), "Включите VPN в настройках и вернитесь в NordCam")
    }

    private suspend fun waitSystemVpn(ctx: Context, fail: String): VpnResult {
        repeat(20) {
            if (systemVpnActive(ctx)) return VpnResult.Ok
            delay(500)
        }
        return VpnResult.Error(fail)
    }
}
