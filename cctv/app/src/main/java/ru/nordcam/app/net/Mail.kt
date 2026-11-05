package ru.nordcam.app.net

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Шифрование соединения с почтовым сервером. */
enum class MailSecurity(val title: String, val port: Int) {
    SSL("SSL/TLS", 465),
    STARTTLS("STARTTLS", 587),
    NONE("Без шифрования", 25);

    companion object {
        fun of(s: String?, port: Int) = entries.firstOrNull { it.name == s }
            ?: when (port) { 465 -> SSL; 25 -> NONE; else -> STARTTLS }
    }
}

/** Настройки отправки. [user] может быть пустым — для своего сервера, который пропускает без входа (relay). */
data class MailConfig(
    val host: String,
    val port: Int,
    val security: MailSecurity,
    val user: String,
    val pass: String,
    val from: String,
    val to: String,
    /** Принимать самоподписанный сертификат — для своего сервера без купленного сертификата. */
    val trustAll: Boolean = false,
)

/** Почтовые сервисы с готовыми настройками SMTP. */
data class MailPreset(val title: String, val host: String, val port: Int, val hint: String)

object MailPresets {
    val all = listOf(
        MailPreset("Яндекс", "smtp.yandex.ru", 465, "Нужен «пароль приложения»: id.yandex.ru → Безопасность → Пароли приложений → Почта."),
        MailPreset("Mail.ru", "smtp.mail.ru", 465, "Нужен «пароль для внешнего приложения»: настройки аккаунта → Безопасность."),
        MailPreset("Gmail", "smtp.gmail.com", 465, "Включите двухэтапную проверку и создайте «пароль приложения» в аккаунте Google."),
        MailPreset("Rambler", "smtp.rambler.ru", 465, "В настройках ящика разрешите доступ почтовым программам."),
    )
}

/**
 * Отправка письма по SMTP без сторонних библиотек: SSL (порт 465) или STARTTLS (587),
 * вход AUTH LOGIN, письмо с текстом и снимком во вложении. Вызывать из фонового потока.
 * Возвращает null при успехе или текст ошибки.
 */
object Mail {
    private class SmtpError(msg: String) : Exception(msg)

    fun send(cfg: MailConfig, subject: String, text: String, jpeg: ByteArray?): String? {
        val host = cfg.host.trim()
        val from = cfg.from.trim().ifBlank { cfg.user.trim() }
        val rcpt = cfg.to.split(',', ';', ' ').map { it.trim() }.filter { it.contains('@') }
        if (host.isBlank() || rcpt.isEmpty()) return "Заполните сервер и адрес получателя"
        if (!from.contains('@')) return "Укажите адрес отправителя (например camera@вашдомен.ru)"
        var sock: Socket? = null
        return try {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, cfg.port), 15_000)
            plain.soTimeout = 30_000
            sock = if (cfg.security == MailSecurity.SSL) tls(plain, host, cfg.port, cfg.trustAll) else plain
            var io = Io(sock)
            io.expect(220)
            var ehlo = io.cmd("EHLO nordcam", 250)
            if (cfg.security == MailSecurity.STARTTLS) {
                io.cmd("STARTTLS", 220)
                sock = tls(sock, host, cfg.port, cfg.trustAll)
                io = Io(sock)
                ehlo = io.cmd("EHLO nordcam", 250)
            }
            if (cfg.user.isNotBlank()) {
                val caps = ehlo.uppercase()
                val authLine = caps.lines().firstOrNull { it.drop(4).startsWith("AUTH") }.orEmpty()
                try {
                    if (authLine.contains("PLAIN") && !authLine.contains("LOGIN")) {
                        io.cmd("AUTH PLAIN " + b64("\u0000${cfg.user}\u0000${cfg.pass}".toByteArray()), 235)
                    } else {
                        io.cmd("AUTH LOGIN", 334)
                        io.cmd(b64(cfg.user.toByteArray()), 334)
                        io.cmd(b64(cfg.pass.toByteArray()), 235)
                    }
                } catch (e: SmtpError) {
                    throw SmtpError(
                        if (cfg.security == MailSecurity.NONE && authLine.isBlank())
                            "Сервер не разрешает вход без шифрования — выберите SSL/TLS или STARTTLS"
                        else "Почтовый сервер не принял логин или пароль. Для Яндекса, Mail.ru и Gmail нужен «пароль приложения».",
                    )
                }
            }
            io.cmd("MAIL FROM:<$from>", 250)
            rcpt.forEach { io.cmd("RCPT TO:<$it>", 250, 251) }
            io.cmd("DATA", 354)
            io.raw(message(from, rcpt, subject, text, jpeg))
            io.cmd(".", 250)
            try { io.cmd("QUIT", 221) } catch (_: Exception) {}
            null
        } catch (e: SmtpError) {
            e.message
        } catch (e: java.net.UnknownHostException) {
            "Не найден сервер $host — проверьте адрес и интернет"
        } catch (e: java.net.SocketTimeoutException) {
            "Сервер $host:${cfg.port} не отвечает — проверьте порт и шифрование"
        } catch (e: java.net.ConnectException) {
            "Сервер $host:${cfg.port} не принимает подключения — проверьте порт"
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            "Ошибка сертификата сервера. Если сервер свой с самоподписанным сертификатом — включите «Доверять сертификату сервера»"
        } catch (e: javax.net.ssl.SSLException) {
            "Ошибка шифрования: проверьте, что выбран правильный тип (SSL/TLS для 465, STARTTLS для 587)"
        } catch (e: Exception) {
            "Ошибка отправки: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun tls(s: Socket, host: String, port: Int, trustAll: Boolean): SSLSocket {
        val factory = if (trustAll) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), SecureRandom())
            ctx.socketFactory
        } else {
            SSLSocketFactory.getDefault() as SSLSocketFactory
        }
        val ssl = factory.createSocket(s, host, port, true) as SSLSocket
        ssl.startHandshake()
        if (trustAll) return ssl
        // Сокеты не проверяют имя сервера сами — проверяем, чтобы пароль не утёк подменному серверу
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            ssl.close()
            throw SmtpError("Сертификат почтового сервера не совпадает с адресом $host")
        }
        return ssl
    }

    private class Io(s: Socket) {
        private val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        private val w: OutputStream = s.getOutputStream()

        fun expect(vararg codes: Int): String {
            val sb = StringBuilder()
            var code = 0
            while (true) {
                val line = r.readLine() ?: throw SmtpError("Сервер разорвал соединение")
                sb.append(line).append('\n')
                code = line.take(3).toIntOrNull() ?: 0
                if (line.length < 4 || line[3] != '-') break
            }
            if (code !in codes) throw SmtpError("Почтовый сервер: ${sb.toString().trim().take(200)}")
            return sb.toString()
        }

        fun cmd(c: String, vararg codes: Int): String {
            raw(c + "\r\n")
            return expect(*codes)
        }

        fun raw(s: String) {
            w.write(s.toByteArray(Charsets.UTF_8))
            w.flush()
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun header(s: String) = "=?UTF-8?B?${b64(s.toByteArray())}?="

    private fun message(from: String, to: List<String>, subject: String, text: String, jpeg: ByteArray?): String {
        val boundary = "nc-" + UUID.randomUUID().toString().replace("-", "")
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("From: ${header("NordCam")} <$from>\r\n")
        sb.append("To: ${to.joinToString(", ")}\r\n")
        sb.append("Subject: ${header(subject)}\r\n")
        sb.append("Date: $date\r\n")
        sb.append("Message-ID: <${UUID.randomUUID()}@nordcam>\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
        sb.append(Base64.encodeToString(text.toByteArray(), Base64.CRLF)).append("\r\n")
        if (jpeg != null) {
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: image/jpeg; name=\"snimok.jpg\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: inline; filename=\"snimok.jpg\"\r\n\r\n")
            sb.append(Base64.encodeToString(jpeg, Base64.CRLF)).append("\r\n")
        }
        sb.append("--$boundary--\r\n")
        return sb.toString()
    }
}
