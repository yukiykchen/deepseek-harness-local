package com.example.dsh.dsh

import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal enum class DshSseEventKind { OPEN, FRAME, ERROR, CLOSED }

internal data class DshSseEvent(
    val kind: DshSseEventKind,
    val data: String = "",
    val message: String = "",
)

internal interface DshSseHandle { fun close() }

/** Legacy local-mode transport. Remote mode uses DshWebSocketModule instead. */
internal class DshSseModule : Module() {
    private var sequence = 0

    override fun moduleName(): String = MODULE_NAME

    fun connect(url: String, token: String = "", onEvent: (DshSseEvent) -> Unit): DshSseHandle {
        val connectionId = "dsh-sse-${++sequence}"
        val params = JSONObject().apply {
            put("connectionId", connectionId)
            put("url", url)
            put("token", token)
        }
        KLog.i(TAG, "connect requested")
        toNative(
            keepCallbackAlive = true,
            methodName = "connect",
            param = params.toString(),
            callback = { value ->
                val kind = runCatching { DshSseEventKind.valueOf(value?.optString("kind").orEmpty()) }
                    .getOrDefault(DshSseEventKind.ERROR)
                onEvent(DshSseEvent(kind, value?.optString("data").orEmpty(), value?.optString("message").orEmpty()))
            },
            syncCall = false,
        )
        return object : DshSseHandle {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                toNative(
                    keepCallbackAlive = false,
                    methodName = "disconnect",
                    param = JSONObject().apply { put("connectionId", connectionId) }.toString(),
                    callback = null,
                    syncCall = false,
                )
            }
        }
    }

    companion object {
        const val MODULE_NAME = "DshSseModule"
        private const val TAG = "DshEventStream"
    }
}
