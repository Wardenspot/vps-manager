package ru.nordcam.app.share

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.MotionMode
import ru.nordcam.app.data.Perms
import ru.nordcam.app.data.RecordMode
import ru.nordcam.app.data.mapObjects
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Код доступа к камерам: список камер с правами, сжатый и зашифрованный паролем
 * (PBKDF2 + AES-256-GCM). Без пароля код бесполезен, поэтому его можно пересылать в мессенджере,
 * а пароль сообщить отдельно.
 */
object ShareCodec {
    private const val PREFIX = "NC1."
    private const val ITER = 120_000

    class BadCode(msg: String) : Exception(msg)

    fun encode(cams: List<Camera>, perms: Perms, password: String, from: String): String {
        val arr = JSONArray()
        cams.forEach { c ->
            val shared = c.copy(
                id = UUID.randomUUID().toString(),
                shared = true,
                perms = perms,
                motion = MotionMode.OFF,
                record = RecordMode.OFF,
                order = 0,
            )
            arr.put(shared.toJson())
        }
        val json = JSONObject().put("v", 1).put("from", from).put("cams", arr).toString().toByteArray()
        val packed = deflate(json)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(packed)
        val out = salt + iv + enc
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    data class Decoded(val from: String, val cams: List<Camera>)

    fun decode(codeIn: String, password: String): Decoded {
        val code = extract(codeIn) ?: throw BadCode("Это не код доступа NordCam")
        val raw = try {
            Base64.decode(code.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) {
            throw BadCode("Код повреждён — скопируйте его целиком")
        }
        if (raw.size < 40) throw BadCode("Код повреждён — скопируйте его целиком")
        val salt = raw.copyOfRange(0, 16)
        val iv = raw.copyOfRange(16, 28)
        val enc = raw.copyOfRange(28, raw.size)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            inflate(cipher.doFinal(enc))
        } catch (e: Exception) {
            throw BadCode("Неверный пароль")
        }
        val o = JSONObject(String(plain))
        val cams = o.optJSONArray("cams")?.mapObjects { Camera.from(it).copy(shared = true) }.orEmpty()
        return Decoded(o.optString("from"), cams)
    }

    /** Достаёт код из текста сообщения или ссылки nordcam://share?c=... */
    fun extract(text: String): String? {
        val t = text.trim()
        val m = Regex("""NC1\.[A-Za-z0-9_\-]+""").find(t) ?: return null
        return m.value
    }

    fun link(code: String) = "nordcam://share?c=$code"

    fun qr(text: String, size: Int = 720): Bitmap? = try {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(password.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray {
        val i = Inflater()
        i.setInput(b)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
        }
        i.end()
        return out.toByteArray()
    }
}
