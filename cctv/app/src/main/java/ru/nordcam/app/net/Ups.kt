package ru.nordcam.app.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Как читать ИБП. */
enum class UpsType(val title: String, val port: Int, val hint: String) {
    NUT(
        "NUT / Synology", 3493,
        "Synology: Панель управления → Оборудование и питание → ИБП → «Включить сетевой ИБП-сервер» и добавьте IP телефона " +
            "(или VPN-адрес) в «Разрешённые устройства». Имя ИБП — ups, логин monuser, пароль secret.",
    ),
    APCUPSD(
        "apcupsd (APC)", 3551,
        "Программа apcupsd на Windows/Linux, к которой ИБП APC подключён по USB. В apcupsd.conf: NETSERVER on, NISIP 0.0.0.0.",
    );

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: NUT
    }
}

data class UpsDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: UpsType = UpsType.NUT,
    val host: String = "",
    val port: Int = 3493,
    val upsName: String = "ups",
    val user: String = "",
    val pass: String = "",
) {
    val title: String get() = name.ifBlank { "ИБП $host" }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type.name).put("host", host)
        .put("port", port).put("upsName", upsName).put("user", user).put("pass", pass)

    companion object {
        fun from(o: JSONObject) = UpsDevice(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            type = UpsType.of(o.optString("type")),
            host = o.optString("host"),
            port = o.optInt("port", 3493),
            upsName = o.optString("upsName", "ups"),
            user = o.optString("user"),
            pass = o.optString("pass"),
        )
    }
}

data class UpsStatus(
    val ok: Boolean,
    val error: String = "",
    val onBattery: Boolean = false,
    val lowBattery: Boolean = false,
    val replaceBattery: Boolean = false,
    val overload: Boolean = false,
    val charging: Boolean = false,
    /** Заряд, %; -1 — неизвестно. */
    val charge: Int = -1,
    /** Нагрузка, %; -1 — неизвестно. */
    val load: Int = -1,
    /** Сколько осталось работать от батареи, сек; -1 — неизвестно. */
    val runtimeSec: Int = -1,
    val inputVolt: Float = -1f,
    val model: String = "",
    val time: Long = System.currentTimeMillis(),
) {
    /** Кратко для полосы внизу экрана. */
    val stateText: String
        get() = when {
            !ok -> "нет связи"
            lowBattery -> "батарея разряжена!"
            onBattery -> "от батареи"
            overload -> "перегрузка!"
            replaceBattery -> "замените батарею"
            charging -> "заряжается"
            else -> "от сети"
        }

    val alarm: Boolean get() = !ok || onBattery || lowBattery || overload || replaceBattery
}

/** Чтение ИБП по протоколам NUT (upsd, порт 3493) и apcupsd NIS (порт 3551). Блокирующие вызовы. */
object Ups {
    fun query(d: UpsDevice): UpsStatus = try {
        when (d.type) {
            UpsType.NUT -> nut(d)
            UpsType.APCUPSD -> apc(d)
        }
    } catch (e: java.net.ConnectException) {
        UpsStatus(false, "Порт ${d.port} закрыт — включён ли сетевой сервер ИБП?")
    } catch (e: java.net.SocketTimeoutException) {
        UpsStatus(false, "${d.host} не отвечает")
    } catch (e: java.net.UnknownHostException) {
        UpsStatus(false, "Не найден адрес ${d.host}")
    } catch (e: Exception) {
        UpsStatus(false, e.message ?: "Ошибка связи с ИБП")
    }

    private fun open(d: UpsDevice): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(d.host.trim(), d.port), 5_000)
        s.soTimeout = 8_000
        return s
    }

    // ---------------------------------------------------------------- NUT

    private fun nut(d: UpsDevice): UpsStatus {
        open(d).use { s ->
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val w = s.getOutputStream()
            fun send(c: String) { w.write((c + "\n").toByteArray()); w.flush() }
            fun check(line: String?) {
                if (line == null) throw Exception("ИБП-сервер закрыл соединение")
                if (line.startsWith("ERR")) throw Exception(nutErr(line))
            }
            if (d.user.isNotBlank()) {
                send("USERNAME ${d.user}"); check(r.readLine())
                send("PASSWORD ${d.pass}"); check(r.readLine())
            }
            var name = d.upsName.ifBlank { "ups" }
            send("LIST VAR $name")
            var first = r.readLine()
            if (first != null && first.startsWith("ERR UNKNOWN-UPS")) {
                // Имя ИБП не угадали — берём первый из списка сервера
                send("LIST UPS")
                val lines = readList(r)
                name = lines.firstOrNull { it.startsWith("UPS ") }?.split(' ')?.getOrNull(1) ?: throw Exception("На сервере нет ИБП")
                send("LIST VAR $name")
                first = r.readLine()
            }
            check(first)
            val vars = HashMap<String, String>()
            readList(r).forEach { l ->
                // VAR ups battery.charge "100"
                val m = Regex("""^VAR \S+ (\S+) "(.*)"$""").find(l) ?: return@forEach
                vars[m.groupValues[1]] = m.groupValues[2]
            }
            try { send("LOGOUT") } catch (_: Exception) {}
            val st = vars["ups.status"].orEmpty().split(' ').toSet()
            return UpsStatus(
                ok = true,
                onBattery = "OB" in st,
                lowBattery = "LB" in st,
                replaceBattery = "RB" in st,
                overload = "OVER" in st,
                charging = "CHRG" in st,
                charge = vars["battery.charge"]?.toFloatOrNull()?.toInt() ?: -1,
                load = vars["ups.load"]?.toFloatOrNull()?.toInt() ?: -1,
                runtimeSec = vars["battery.runtime"]?.toFloatOrNull()?.toInt() ?: -1,
                inputVolt = vars["input.voltage"]?.toFloatOrNull() ?: -1f,
                model = (vars["ups.model"] ?: vars["device.model"]).orEmpty().trim(),
            )
        }
    }

    private fun readList(r: BufferedReader): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val l = r.readLine() ?: break
            if (l.startsWith("END LIST")) break
            if (l.startsWith("ERR")) throw Exception(nutErr(l))
            if (!l.startsWith("BEGIN LIST")) out += l
        }
        return out
    }

    private fun nutErr(l: String) = when {
        l.contains("ACCESS-DENIED") -> "Доступ запрещён — добавьте IP телефона в разрешённые на сервере ИБП"
        l.contains("INVALID-PASSWORD") || l.contains("INVALID-USERNAME") -> "Неверный логин или пароль ИБП-сервера"
        l.contains("UNKNOWN-UPS") -> "Сервер не знает ИБП с таким именем"
        l.contains("DATA-STALE") || l.contains("DRIVER-NOT-CONNECTED") -> "Сервер не видит ИБП (проверьте USB-кабель)"
        else -> "ИБП-сервер: $l"
    }

    // ---------------------------------------------------------------- apcupsd NIS

    private fun apc(d: UpsDevice): UpsStatus {
        open(d).use { s ->
            val out = s.getOutputStream()
            val cmd = "status".toByteArray()
            out.write(byteArrayOf(0, cmd.size.toByte())); out.write(cmd); out.flush()
            val inp = DataInputStream(s.getInputStream())
            val vars = HashMap<String, String>()
            while (true) {
                val len = inp.readUnsignedShort()
                if (len == 0) break
                val buf = ByteArray(len)
                inp.readFully(buf)
                val line = String(buf).trim()
                val i = line.indexOf(':')
                if (i > 0) vars[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
            fun num(k: String) = vars[k]?.split(' ')?.firstOrNull()?.toFloatOrNull()
            val status = vars["STATUS"].orEmpty().uppercase()
            val timeLeftMin = num("TIMELEFT")
            return UpsStatus(
                ok = vars.isNotEmpty(),
                error = if (vars.isEmpty()) "apcupsd не прислал данные" else "",
                onBattery = status.contains("ONBATT"),
                lowBattery = status.contains("LOWBATT"),
                replaceBattery = status.contains("REPLACEBATT"),
                overload = status.contains("OVERLOAD"),
                charging = status.contains("CHARGING"),
                charge = num("BCHARGE")?.toInt() ?: -1,
                load = num("LOADPCT")?.toInt() ?: -1,
                runtimeSec = timeLeftMin?.let { (it * 60).toInt() } ?: -1,
                inputVolt = num("LINEV") ?: -1f,
                model = vars["MODEL"].orEmpty(),
            )
        }
    }
}
