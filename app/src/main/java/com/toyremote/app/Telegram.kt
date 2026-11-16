package com.toyremote.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TelegramError(val code: Int, message: String) : Exception(message)

/** A tiny Telegram Bot API client. No server of our own: the phone talks to Telegram directly. */
class Telegram(private val token: String) {

    @Volatile private var longCall: HttpURLConnection? = null

    fun call(method: String, params: JSONObject = JSONObject(), readTimeoutMs: Int = 20000): JSONObject {
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        val isLong = readTimeoutMs > 30000
        if (isLong) longCall = c
        try {
            c.connectTimeout = 15000
            c.readTimeout = readTimeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(params.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = (if (code in 200..299) c.inputStream else c.errorStream)
                ?: throw TelegramError(code, "error $code")
            val body = stream.bufferedReader().use { it.readText() }
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw TelegramError(code, "unexpected reply (error $code)")
            }
            if (!json.optBoolean("ok")) {
                throw TelegramError(json.optInt("error_code", code), json.optString("description", "error $code"))
            }
            return json
        } finally {
            if (isLong) longCall = null
            c.disconnect()
        }
    }

    /** Ends a waiting getUpdates call right away (used when the app stops). */
    fun cancel() {
        try {
            longCall?.disconnect()
        } catch (ignored: Exception) {
        }
    }

    fun send(chatId: Long, text: String, keyboard: JSONArray? = null): Long {
        val p = JSONObject().put("chat_id", chatId).put("text", text)
        if (keyboard != null) p.put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
        return call("sendMessage", p).optJSONObject("result")?.optLong("message_id") ?: 0L
    }

    fun edit(chatId: Long, messageId: Long, text: String, keyboard: JSONArray?) {
        val p = JSONObject().put("chat_id", chatId).put("message_id", messageId).put("text", text)
        if (keyboard != null) p.put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
        call("editMessageText", p)
    }

    fun answer(callbackId: String, text: String) {
        val p = JSONObject().put("callback_query_id", callbackId)
        if (text.isNotEmpty()) p.put("text", text)
        call("answerCallbackQuery", p)
    }

    companion object {
        /** style: null (plain), "primary" (blue), "success" (green) or "danger" (red). */
        fun button(label: String, data: String, style: String? = null): JSONObject {
            val b = JSONObject().put("text", label).put("callback_data", data)
            if (style != null) b.put("style", style)
            return b
        }

        fun displayName(from: JSONObject): String {
            val first = from.optString("first_name", "")
            val last = from.optString("last_name", "")
            val user = from.optString("username", "")
            val name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Someone" }
            return if (user.isNotBlank()) "$name (@$user)" else name
        }
    }
}
