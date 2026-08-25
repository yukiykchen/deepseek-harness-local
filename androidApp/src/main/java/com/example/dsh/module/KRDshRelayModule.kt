package com.example.dsh.module

import android.content.Intent
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

/** Local-only app keeps the module name so leftover shared calls do not crash. */
internal class KRDshRelayModule : KuiklyRenderBaseModule() {
    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        callback?.invoke(
            mapOf(
                "ok" to false,
                "phase" to "ERROR",
                "message" to "本 App 仅支持手机本地模式",
                "localPort" to 0,
                "localToken" to "",
                "hostId" to "",
                "hostName" to "",
                "relayOrigin" to "",
                "paired" to false,
                "generation" to 0,
            ),
        )
        return null
    }

    companion object {
        const val MODULE_NAME = "DshRelayModule"

        fun dispatchActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = Unit
    }
}
