package ru.nordkey.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/** Открыть ссылку: t.me — сразу в Telegram, если он установлен. */
fun openUrl(ctx: Context, url: String) {
    val tg = if (url.startsWith("https://t.me/")) {
        val rest = url.removePrefix("https://t.me/")
        val (name, q) = rest.split("?", limit = 2).let { it[0] to it.getOrNull(1) }
        Uri.parse("tg://resolve?domain=$name" + (q?.let { "&$it" } ?: ""))
    } else null
    val intents = listOfNotNull(tg, Uri.parse(url)).map { Intent(Intent.ACTION_VIEW, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    for (i in intents) {
        try {
            ctx.startActivity(i); return
        } catch (_: Exception) {
        }
    }
}

fun shareText(ctx: Context, text: String, title: String = "Поделиться") {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Поделиться файлом (Telegram, WhatsApp, почта…). */
fun shareFile(ctx: Context, name: String, data: ByteArray, mime: String = "application/octet-stream", title: String = "Отправить файл") {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, name).apply { writeBytes(data) }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Сохранить файл в «Загрузки». Возвращает текст для пользователя. */
fun saveToDownloads(ctx: Context, name: String, data: ByteArray, mime: String = "application/octet-stream"): String {
    return if (Build.VERSION.SDK_INT >= 29) {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: return "Не удалось сохранить файл"
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
        "Сохранено в «Загрузки»: $name"
    } else {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        try {
            File(dir, name).writeBytes(data); "Сохранено в «Загрузки»: $name"
        } catch (e: Exception) {
            shareFile(ctx, name, data, mime); "Выберите, куда сохранить файл"
        }
    }
}

fun qr(text: String, size: Int = 720): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                                  mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) -0x1000000 else -0x1)
    return bmp.asImageBitmap()
}
