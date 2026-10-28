package com.nextmove.focus

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Small beta feedback bridge. Apps Script owns the Gmail permission, so the
 * Android client can submit a note without launching a mail app or handling
 * anyone's email credentials.
 */
object FeedbackGateway {
    private const val ENDPOINT = "https://script.google.com/macros/s/AKfycbzLEZgZMSS2kpfpg5y2LStwgNE4wFSiJTYJp-zkFfV9O8F5OGtC8Xbc3OOf1ZZZ_mEJDQ/exec"
    private const val TOKEN = "d7472aa6b4294836609c436d077f2452de8461cb5337e0da"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000

    fun send(
        type: String,
        message: String,
        username: String,
        appVersion: String,
        androidVersion: String,
        onDone: (Result<Unit>) -> Unit
    ) {
        Thread {
            onDone(runCatching {
                post(type, message, username, appVersion, androidVersion)
            })
        }.start()
    }

    private fun post(
        type: String,
        message: String,
        username: String,
        appVersion: String,
        androidVersion: String
    ) {
        val request = JSONObject().apply {
            put("token", TOKEN)
            put("type", type)
            put("message", message)
            put("username", username)
            put("appVersion", appVersion)
            put("androidVersion", androidVersion)
        }.toString()

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                output.write(request.toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val parsed = runCatching { JSONObject(response) }.getOrNull()
            if (responseCode !in 200..299) {
                throw IllegalStateException("network_error")
            }
            if (parsed?.optBoolean("ok", false) != true) {
                throw IllegalStateException(parsed?.optString("error").orEmpty().ifBlank { "server_error" })
            }
        } finally {
            connection.disconnect()
        }
    }
}
