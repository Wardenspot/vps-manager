package ru.nordcam.app.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Минимальный клиент SNMP v1 (только GET) — для чтения сетевых карт ИБП без сторонних библиотек.
 * Значения: Long для чисел (INTEGER, Gauge32, Counter32, TimeTicks), String для строк, null — нет данных.
 */
object Snmp {
    private class Tlv(val tag: Int, val value: ByteArray, val children: List<Tlv>)

    fun get(sock: DatagramSocket, addr: InetAddress, port: Int, community: String, oid: String): Any? {
        repeat(2) {
            val reqId = Random.nextInt(1, Int.MAX_VALUE)
            val msg = seq(
                0x30,
                int(0) + tlv(0x04, community.toByteArray()) +
                    tlv(0xA0, int(reqId.toLong()) + int(0) + int(0) + seq(0x30, seq(0x30, oidBytes(oid) + byteArrayOf(0x05, 0x00)))),
            )
            sock.send(DatagramPacket(msg, msg.size, addr, port))
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            val deadline = System.currentTimeMillis() + sock.soTimeout
            while (System.currentTimeMillis() < deadline) {
                try {
                    sock.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val root = try { parse(pkt.data.copyOf(pkt.length), 0, pkt.length).firstOrNull() } catch (e: Exception) { null } ?: continue
                val pdu = root.children.getOrNull(2) ?: continue
                if (pdu.tag != 0xA2) continue
                if (toLong(pdu.children.getOrNull(0)?.value) != reqId.toLong()) continue
                if ((toLong(pdu.children.getOrNull(1)?.value) ?: 0L) != 0L) return null
                val value = pdu.children.getOrNull(3)?.children?.firstOrNull()?.children?.getOrNull(1) ?: return null
                return when (value.tag) {
                    0x02 -> toLong(value.value)
                    0x41, 0x42, 0x43, 0x46 -> toUnsigned(value.value)
                    0x04 -> String(value.value, Charsets.UTF_8)
                    else -> null
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- кодирование BER

    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, v: ByteArray) = byteArrayOf(tag.toByte()) + len(v.size) + v
    private fun seq(tag: Int, v: ByteArray) = tlv(tag, v)

    private fun int(v: Long): ByteArray {
        var bytes = java.math.BigInteger.valueOf(v).toByteArray()
        if (bytes.isEmpty()) bytes = byteArrayOf(0)
        return tlv(0x02, bytes)
    }

    private fun oidBytes(oid: String): ByteArray {
        val parts = oid.trim('.').split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var x = p
            stack += (x and 0x7F).toInt()
            x = x shr 7
            while (x > 0) { stack += ((x and 0x7F) or 0x80).toInt(); x = x shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    // ---------------------------------------------------------------- разбор BER

    private fun parse(b: ByteArray, from: Int, to: Int): List<Tlv> {
        val out = ArrayList<Tlv>()
        var i = from
        while (i < to) {
            val tag = b[i].toInt() and 0xFF
            i++
            var l = b[i].toInt() and 0xFF
            i++
            if (l and 0x80 != 0) {
                val n = l and 0x7F
                l = 0
                repeat(n) { l = (l shl 8) or (b[i].toInt() and 0xFF); i++ }
            }
            val end = i + l
            if (end > to) throw IllegalArgumentException("bad length")
            val constructed = tag and 0x20 != 0
            out += Tlv(tag, b.copyOfRange(i, end), if (constructed) parse(b, i, end) else emptyList())
            i = end
        }
        return out
    }

    private fun toLong(v: ByteArray?): Long? {
        if (v == null || v.isEmpty()) return null
        return java.math.BigInteger(v).toLong()
    }

    private fun toUnsigned(v: ByteArray): Long {
        var r = 0L
        v.forEach { r = (r shl 8) or (it.toLong() and 0xFF) }
        return r
    }
}
