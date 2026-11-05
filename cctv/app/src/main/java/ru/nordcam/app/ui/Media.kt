package ru.nordcam.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import ru.nordcam.app.app
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun toast(ctx: Context, t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

private val fileFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

/** Сохраняет снимок в папку приложения и в галерею телефона (Pictures/NordCam). */
fun saveSnapshot(ctx: Context, camName: String, bmp: Bitmap?, jpeg: ByteArray? = null): File? {
    val safe = camName.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40)
    val name = "${safe}_${fileFmt.format(Date())}.jpg"
    val f = File(ctx.app.store.snapshotsDir, name)
    try {
        if (bmp != null) FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        else if (jpeg != null) f.writeBytes(jpeg)
        else return null
    } catch (e: Exception) {
        return null
    }
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NordCam")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        } catch (_: Exception) {
        }
    }
    return f
}

/** Сохранить видеозапись в общую папку Movies/NordCam, чтобы её видели другие приложения. */
fun exportVideo(ctx: Context, f: File): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return try {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/x-matroska")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NordCam")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        true
    } catch (e: Exception) {
        false
    }
}

fun shareFile(ctx: Context, f: File, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Отправить").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(ctx, "Не удалось отправить файл")
    }
}

fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "Отправить").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f ГБ".format(b / (1L shl 30).toFloat())
    b >= 1L shl 20 -> "%.0f МБ".format(b / (1L shl 20).toFloat())
    else -> "${b / 1024} КБ"
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
