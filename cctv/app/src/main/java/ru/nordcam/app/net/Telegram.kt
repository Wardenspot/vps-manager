package ru.nordcam.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Уведомления о тревогах в Telegram через своего бота (необязательно). */
object Telegram {
    fun send(token: String, chat: String, text: String, jpeg: ByteArray?): Boolean {
        if (token.isBlank() || chat.isBlank()) return false
        return try {
            val req = if (jpeg != null) {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chat)
                    .addFormDataPart("caption", text)
                    .addFormDataPart("photo", "shot.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
                    .build()
                Request.Builder().url("https://api.telegram.org/bot$token/sendPhoto").post(body).build()
            } else {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chat)
                    .addFormDataPart("text", text)
                    .build()
                Request.Builder().url("https://api.telegram.org/bot$token/sendMessage").post(body).build()
            }
            Http.client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
