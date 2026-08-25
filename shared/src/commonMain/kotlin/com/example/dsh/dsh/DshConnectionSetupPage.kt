package com.example.dsh.dsh

import com.example.dsh.base.BasePager
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuikly.core.base.attr.ImageUri

/** Local-only launcher: start the on-device Agent, never scan or SSH. */
@Page("connection_setup")
internal class DshConnectionSetupPage : BasePager() {
    private var busy by observable(false)
    private var localStore: DshLocalStore? = null

    override fun created() {
        super.created()
        val databaseDir = pageData.params.optString("databaseDir")
        localStore = if (databaseDir.isEmpty()) null else runCatching {
            createDshLocalStore("$databaseDir/dsh.db", null)
        }.getOrNull()
        runCatching { localStore?.saveLastConnectionMode(DshConnectionMode.LOCAL) }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    paddingTop(pagerData.statusBarHeight)
                    backgroundColor(Color(0xFFF7F9FA))
                }
                View {
                    attr {
                        height(58f)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(20f)
                        paddingRight(20f)
                        backgroundColor(Color.WHITE)
                        borderBottom(Border(1f, BorderStyle.SOLID, Color(0xFFE5E8EB)))
                    }
                    Image { attr { src(ImageUri.commonAssets("wordmark.svg")); width(118f); height(24f) } }
                }
                View {
                    attr {
                        flex(1f)
                        paddingLeft(20f)
                        paddingRight(20f)
                        paddingTop(40f)
                        flexDirectionColumn()
                    }
                    Text { attr { text("DSH Local"); fontSize(28f); fontWeightBold(); color(Color(0xFF1F2933)) } }
                    Text {
                        attr {
                            text("在这台手机上运行内嵌 Agent。扫码连接电脑或 SSH 请使用 DSH Mobile。")
                            marginTop(10f)
                            fontSize(15f)
                            lineHeight(22f)
                            color(Color(0xFF68737D))
                        }
                    }
                    Text {
                        attr {
                            text("使用前请先安装并启动 Shizuku。首次启动会解压约 115 MB 的 Node.js / Harness 运行时。")
                            marginTop(16f)
                            fontSize(14f)
                            lineHeight(21f)
                            color(Color(0xFF68737D))
                        }
                    }
                    View { attr { flex(1f) } }
                    Button {
                        attr {
                            height(48f)
                            marginBottom(24f)
                            borderRadius(10f)
                            backgroundColor(Color(if (ctx.busy) 0xFFB7C8FE else 0xFF4176E6))
                            titleAttr { text(if (ctx.busy) "处理中..." else "进入本地 Agent"); fontSize(15f); color(Color.WHITE) }
                        }
                        event { click { if (!ctx.busy) ctx.continueToHost() } }
                    }
                }
            }
        }
    }

    private fun continueToHost() {
        busy = true
        runCatching { localStore?.saveLastConnectionMode(DshConnectionMode.LOCAL) }
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("home", JSONObject().apply {
            put("pageName", "home")
            put("connectionMode", "local")
            put("profileId", DshSessionScope.DEFAULT_REMOTE_PROFILE_ID)
        })
    }
}
