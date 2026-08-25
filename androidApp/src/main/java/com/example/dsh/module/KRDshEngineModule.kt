package com.example.dsh.module

import com.example.dsh.engine.DshEngineManager
import com.example.dsh.engine.EngineState
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

internal class KRDshEngineModule : KuiklyRenderBaseModule() {
    private var stateListener: ((EngineState) -> Unit)? = null

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? = when (method) {
        "start" -> {
            stateListener?.let(DshEngineManager::removeListener)
            val listener: (EngineState) -> Unit = { state ->
                activity?.runOnUiThread { callback?.invoke(state.toMap()) }
            }
            stateListener = listener
            DshEngineManager.start(requireNotNull(context), listener)
            null
        }
        "startSsh" -> {
            callback?.invoke(mapOf("phase" to "ERROR", "message" to "本 App 仅支持手机本地模式"))
            null
        }
        "trustSshFingerprint" -> null
        "stopSsh" -> null
        "sshEndpoint" -> ""
        "status" -> DshEngineManager.currentState().toMap()
        "stop" -> {
            DshEngineManager.stop()
            null
        }
        else -> null
    }

    private fun EngineState.toMap(): Map<String, Any> = mapOf(
        "phase" to phase.name,
        "progress" to progress,
        "message" to message,
    )

    companion object {
        const val MODULE_NAME = "DshEngineModule"
    }
}
