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
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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

    fun send(
        host: String, port: Int, user: String, pass: String, to: String,
        subject: String, text: String, jpeg: ByteArray?,
    ): String? {
        val rcpt = to.split(',', ';', ' ').map { it.trim() }.filter { it.contains('@') }
        if (host.isBlank() || user.isBlank() || pass.isBlank() || rcpt.isEmpty()) return "Заполните сервер, логин, пароль и адрес получателя"
        var sock: Socket? = null
        return try {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), 15_000)
            plain.soTimeout = 30_000
            sock = if (port == 465) tls(plain, host, port) else plain
            var io = Io(sock)
            io.expect(220)
            io.cmd("EHLO nordcam", 250)
            if (port != 465) {
                io.cmd("STARTTLS", 220)
                sock = tls(sock, host, port)
                io = Io(sock)
                io.cmd("EHLO nordcam", 250)
            }
            io.cmd("AUTH LOGIN", 334)
            io.cmd(b64(user.toByteArray()), 334)
            try {
                io.cmd(b64(pass.toByteArray()), 235)
            } catch (e: SmtpError) {
                throw SmtpError("Почтовый сервер не принял логин или пароль. Нужен «пароль приложения», а не обычный пароль от почты.")
            }
            io.cmd("MAIL FROM:<$user>", 250)
            rcpt.forEach { io.cmd("RCPT TO:<$it>", 250, 251) }
            io.cmd("DATA", 354)
            io.raw(message(user, rcpt, subject, text, jpeg))
            io.cmd(".", 250)
            try { io.cmd("QUIT", 221) } catch (_: Exception) {}
            null
        } catch (e: SmtpError) {
            e.message
        } catch (e: java.net.UnknownHostException) {
            "Не найден сервер $host — проверьте адрес и интернет"
        } catch (e: java.net.SocketTimeoutException) {
            "Сервер $host:$port не отвечает"
        } catch (e: Exception) {
            "Ошибка отправки: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun tls(s: Socket, host: String, port: Int): SSLSocket {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, port, true) as SSLSocket
        ssl.startHandshake()
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
