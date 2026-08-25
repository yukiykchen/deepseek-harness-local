package com.example.dsh.dsh

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal enum class DshEnginePhase {
    IDLE,
    PREPARING,
    STARTING,
    READY,
    ERROR,
    STOPPED,
    UNSUPPORTED,
}

internal data class DshEngineState(
    val phase: DshEnginePhase,
    val progress: Int = 0,
    val message: String = "",
)

/** Cross-platform owner of the native embedded-Harness bridge. */
internal class DshEngineModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun start(onState: (DshEngineState) -> Unit) {
        toNative(
            keepCallbackAlive = true,
            methodName = "start",
            param = null,
            callback = { value ->
                val phase = runCatching {
                    DshEnginePhase.valueOf(value?.optString("phase") ?: "ERROR")
                }.getOrDefault(DshEnginePhase.ERROR)
                onState(DshEngineState(
                    phase = phase,
                    progress = value?.optInt("progress") ?: 0,
                    message = value?.optString("message").orEmpty(),
                ))
            },
            syncCall = false,
        )
    }

    fun startSsh(config: DshSshConfig, onState: (DshSshState) -> Unit) {
        val params = JSONObject().apply {
            put("host", config.host)
            put("port", config.port)
            put("username", config.username)
            put("remoteDshPort", config.remoteDshPort)
            put("keyId", config.keyId)
            put("hostFingerprint", config.hostFingerprint)
            put("keyPassphrase", config.keyPassphrase)
        }
        toNative(
            keepCallbackAlive = true,
            methodName = "startSsh",
            param = params.toString(),
            callback = { value ->
                onState(DshSshState(
                    phase = runCatching { DshSshPhase.valueOf(value?.optString("phase") ?: "ERROR") }
                        .getOrDefault(DshSshPhase.ERROR),
                    message = value?.optString("message").orEmpty(),
                    localPort = value?.optInt("localPort") ?: 0,
                    generation = value?.optLong("generation") ?: 0L,
                ))
            },
            syncCall = false,
        )
    }

    fun trustSshFingerprint(fingerprint: String) {
        toNative(false, "trustSshFingerprint", JSONObject().apply {
            put("fingerprint", fingerprint)
        }.toString(), null, false)
    }

    fun stopSsh() {
        toNative(false, "stopSsh", null, null, false)
    }

    fun status(): DshEngineState {
        val raw = toNative(false, "status", null, null, true).toString()
        val value = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val phase = runCatching {
            DshEnginePhase.valueOf(value.optString("phase", "ERROR"))
        }.getOrDefault(DshEnginePhase.ERROR)
        return DshEngineState(phase, value.optInt("progress"), value.optString("message"))
    }

    fun stop() {
        toNative(false, "stop", null, null, false)
    }

    companion object {
        const val MODULE_NAME = "DshEngineModule"
    }
}

internal enum class DshSshPhase {
    IDLE, CONNECTING, AUTHENTICATING, FINGERPRINT_REQUIRED, FORWARDING, READY,
    RECONNECTING, ERROR, STOPPED,
}

internal data class DshSshState(
    val phase: DshSshPhase,
    val message: String = "",
    val localPort: Int = 0,
    val generation: Long = 0,
)

internal data class DshSshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val remoteDshPort: Int = 3080,
    val keyId: String,
    val hostFingerprint: String = "",
    val keyPassphrase: String = "",
)
