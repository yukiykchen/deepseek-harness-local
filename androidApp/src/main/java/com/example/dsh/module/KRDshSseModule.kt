package com.example.dsh.module

import android.util.Log
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class KRDshSseModule : KuiklyRenderBaseModule() {
    private val connections = ConcurrentHashMap<String, SseConnection>()

    override fun onDestroy() {
        connections.values.forEach(SseConnection::close)
        connections.clear()
        super.onDestroy()
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val value = runCatching { JSONObject(params ?: "{}") }.getOrDefault(JSONObject())
        return when (method) {
            "connect" -> {
                val connectionId = value.optString("connectionId")
                val url = value.optString("url")
                Log.i(TAG, "connect requested url=$url")
                if (connectionId.isEmpty() || url.isEmpty() || callback == null) return null
                connections.remove(connectionId)?.close()
                SseConnection(
                    url = url,
                    token = value.optString("token"),
                    emit = { event -> activity?.runOnUiThread { callback.invoke(event) } },
                    onFinished = { connections.remove(connectionId) },
                ).also {
                    connections[connectionId] = it
                    it.start()
                }
                null
            }
            "disconnect" -> {
                connections.remove(value.optString("connectionId"))?.close()
                null
            }
            else -> null
        }
    }

    private class SseConnection(
        private val url: String,
        private val token: String,
        private val emit: (Map<String, Any>) -> Unit,
        private val onFinished: () -> Unit,
    ) {
        @Volatile private var closed = false
        @Volatile private var connection: HttpURLConnection? = null
        @Volatile private var webSocket: WebSocket? = null
        private val finished = AtomicBoolean(false)

        fun start() {
            Thread({ readStream() }, "dsh-sse").start()
        }

        fun close() {
            closed = true
            connection?.disconnect()
            webSocket?.close(1000, "client closed")
            webSocket = null
            finish()
        }

        private fun readStream() {
            try {
                val activeConnection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = 0
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                    if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                }
                connection = activeConnection
                val statusCode = activeConnection.responseCode
                Log.i(TAG, "events.mux HTTP status=$statusCode")
                if (statusCode == HTTP_UPGRADE_REQUIRED) {
                    Log.i(TAG, "events.mux requested WebSocket upgrade")
                    activeConnection.disconnect()
                    connection = null
                    startWebSocket()
                    return
                }
                if (statusCode !in 200..299) {
                    emitError("events.mux failed with HTTP $statusCode")
                    return
                }
                emit(mapOf("kind" to "OPEN"))
                BufferedReader(InputStreamReader(activeConnection.inputStream, Charsets.UTF_8)).use { reader ->
                    val data = StringBuilder()
                    while (!closed) {
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> flushData(data)
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substringAfter("data:").trimStart())
                            }
                        }
                    }
                    flushData(data)
                }
                if (!closed) emit(mapOf("kind" to "CLOSED"))
            } catch (error: Throwable) {
                if (!closed) {
                    Log.e(TAG, "events.mux connection failed", error)
                    emitError(error.message ?: "SSE connection failed")
                }
            } finally {
                connection?.disconnect()
                connection = null
                if (webSocket == null) finish()
            }
        }

        private fun startWebSocket() {
            if (closed) return
            val webSocketUrl = when {
                url.startsWith("https://") -> "wss://${url.removePrefix("https://")}"
                url.startsWith("http://") -> "ws://${url.removePrefix("http://")}"
                else -> url
            }
            val request = Request.Builder()
                .url(webSocketUrl)
                .apply {
                    if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                }
                .build()
            webSocket = WEB_SOCKET_CLIENT.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "events.mux WebSocket opened")
                    if (!closed) emit(mapOf("kind" to "OPEN"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!closed) emit(mapOf("kind" to "FRAME", "data" to text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    this@SseConnection.webSocket = null
                    if (!closed) emit(mapOf("kind" to "CLOSED"))
                    finish()
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    this@SseConnection.webSocket = null
                    if (!closed) {
                        Log.e(TAG, "events.mux WebSocket failed", error)
                        emitError(error.message ?: "WebSocket connection failed")
                    }
                    finish()
                }
            })
        }

        private fun flushData(data: StringBuilder) {
            if (data.isEmpty()) return
            emit(mapOf("kind" to "FRAME", "data" to data.toString()))
            data.setLength(0)
        }

        private fun emitError(message: String) {
            emit(mapOf("kind" to "ERROR", "message" to message))
        }

        private fun finish() {
            if (finished.compareAndSet(false, true)) onFinished()
        }
    }

    companion object {
        const val MODULE_NAME = "DshSseModule"
        private const val TAG = "DshEventStream"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HTTP_UPGRADE_REQUIRED = 426
        private val WEB_SOCKET_CLIENT = OkHttpClient.Builder().build()
    }
}
