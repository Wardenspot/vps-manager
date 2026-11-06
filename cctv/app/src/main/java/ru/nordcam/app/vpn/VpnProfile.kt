package ru.nordcam.app.vpn

import org.json.JSONObject
import java.util.UUID

/** Способ подключения к дому. */
enum class VpnKind(val title: String, val short: String, val hint: String) {
    WIREGUARD(
        "WireGuard / AmneziaWG — встроенный", "WireGuard",
        "Вставьте или импортируйте конфиг (.conf) или отсканируйте QR. Подходит WireGuard с роутера Keenetic, MikroTik, OPNsense, " +
            "а также AmneziaWG (обходит блокировки). Через туннель идёт только трафик NordCam — остальной интернет не меняется.",
    ),
    IKEV2(
        "IKEv2 / IPsec — встроенный", "IKEv2",
        "Встроенный VPN Android (с Android 11). Вход по ключу (PSK), логину и паролю или сертификату (.p12). " +
            "Подходит для Keenetic, MikroTik, Synology, pfSense, Windows Server.",
    ),
    OPENVPN(
        "OpenVPN — через «OpenVPN for Android»", "OpenVPN",
        "Нужно бесплатное приложение «OpenVPN for Android» (Google Play / F-Droid). Можно выбрать профиль, уже настроенный в нём, " +
            "или импортировать .ovpn с сертификатами сюда — NordCam сам передаст его и включит.",
    ),
    APP_WIREGUARD(
        "Приложение WireGuard или AmneziaWG на телефоне", "WG-приложение",
        "Туннель уже настроен в приложении WireGuard или AmneziaWG. Впишите его имя точно как в приложении. " +
            "В настройках того приложения включите «Разрешить управление другим приложениям» (Allow remote control apps).",
    ),
    SYSTEM(
        "VPN, настроенный в телефоне", "VPN телефона",
        "Любой VPN из настроек Android или другого приложения. Android не даёт приложениям включать чужой VPN, поэтому NordCam " +
            "откроет настройки VPN, а дальше будет следить, включён ли он.",
    );

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: WIREGUARD
    }
}

/** Как IKEv2 подтверждает вход. */
enum class IkeAuth(val title: String) {
    PSK("Общий ключ (PSK)"),
    USER_PASS("Логин и пароль (EAP)"),
    CERT("Сертификат (.p12)");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: PSK
    }
}

/** Когда подключаться. */
enum class VpnAuto(val title: String, val hint: String) {
    MANUAL("Вручную", "Только кнопкой"),
    AWAY("Когда дом недоступен", "При открытии приложения NordCam проверяет, видно ли дом напрямую; если нет — включает VPN"),
    OPEN("При открытии приложения", "Всегда включать при запуске NordCam"),
    ALWAYS("Всегда", "Держать включённым, в том числе для наблюдения в фоне");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: AWAY
    }
}

data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: VpnKind = VpnKind.WIREGUARD,
    /** WireGuard/AmneziaWG: текст конфига. OpenVPN: текст .ovpn (если импортирован сюда). */
    val config: String = "",
    /** Только трафик NordCam через туннель (WireGuard). */
    val appOnly: Boolean = true,
    // ---- IKEv2 ----
    val server: String = "",
    val identity: String = "",
    val ikeAuth: IkeAuth = IkeAuth.PSK,
    val psk: String = "",
    val user: String = "",
    val pass: String = "",
    /** Сертификат пользователя .p12 (base64) и пароль от него. */
    val p12: String = "",
    val p12Pass: String = "",
    /** Корневой сертификат сервера (PEM/DER в base64), необязательно. */
    val ca: String = "",
    // ---- OpenVPN for Android / приложения WireGuard ----
    /** UUID профиля в OpenVPN for Android или имя туннеля в WireGuard/AmneziaWG. */
    val remoteId: String = "",
    val remoteName: String = "",
    /** Пакет приложения WireGuard (com.wireguard.android) или AmneziaWG (org.amnezia.awg). */
    val appPackage: String = "com.wireguard.android",
    // ---- когда подключаться ----
    val auto: VpnAuto = VpnAuto.AWAY,
    /** Адрес для проверки «дом виден напрямую»: IP:порт камеры или NAS. Пусто — первая камера. */
    val probe: String = "",
) {
    val title: String get() = name.ifBlank { kind.short }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind.name).put("config", config)
        .put("appOnly", appOnly).put("server", server).put("identity", identity).put("ikeAuth", ikeAuth.name)
        .put("psk", psk).put("user", user).put("pass", pass).put("p12", p12).put("p12Pass", p12Pass).put("ca", ca)
        .put("remoteId", remoteId).put("remoteName", remoteName).put("appPackage", appPackage)
        .put("auto", auto.name).put("probe", probe)

    companion object {
        fun from(o: JSONObject) = VpnProfile(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            kind = VpnKind.of(o.optString("kind")),
            config = o.optString("config"),
            appOnly = o.optBoolean("appOnly", true),
            server = o.optString("server"),
            identity = o.optString("identity"),
            ikeAuth = IkeAuth.of(o.optString("ikeAuth")),
            psk = o.optString("psk"),
            user = o.optString("user"),
            pass = o.optString("pass"),
            p12 = o.optString("p12"),
            p12Pass = o.optString("p12Pass"),
            ca = o.optString("ca"),
            remoteId = o.optString("remoteId"),
            remoteName = o.optString("remoteName"),
            appPackage = o.optString("appPackage", "com.wireguard.android"),
            auto = VpnAuto.of(o.optString("auto")),
            probe = o.optString("probe"),
        )
    }
}
