package com.example.dsh.dsh

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout

/** Local-only legacy Host client. Its live transport remains the original SSE path. */
internal class DshHostRepository(
    private val network: NetworkModule,
    private val sse: DshSseModule,
    private val connection: DshHostConnection,
    private val pagerId: String,
) : DshRepository {
    private var rpcSequence = 0L

    override fun loadCredentialSetup(onSuccess: (DshCredentialSetup) -> Unit, onError: (String) -> Unit) {
        request(DshHostProtocol.LLM_PROVIDERS, JSONObject()) { providers, providerError ->
            if (providerError != null || providers == null) {
                onError(providerError ?: "llm.providers 返回为空")
                return@request
            }
            val active = (providers.optJSONArray("providers") ?: JSONArray()).let { list ->
                (0 until list.length()).any { index ->
                    val provider = list.optJSONObject(index) ?: return@any false
                    provider.optString("provider") == "deepseek-official" &&
                        provider.optString("settingsNs") == "llm-deepseek" &&
                        provider.optBoolean("active")
                }
            }
            if (!active) {
                onSuccess(DshCredentialSetup(false, false, false))
                return@request
            }
            request(DshHostProtocol.SETTINGS_DESCRIBE, JSONObject()) { settings, settingsError ->
                if (settingsError != null || settings == null) {
                    onError(settingsError ?: "settings.describe 返回为空")
                    return@request
                }
                val namespaces = settings.optJSONArray("namespaces") ?: JSONArray()
                var credentialRef = "DEEPSEEK_API_KEY"
                var namespaceFound = false
                for (index in 0 until namespaces.length()) {
                    val namespace = namespaces.optJSONObject(index) ?: continue
                    if (namespace.optString("ns") != "llm-deepseek") continue
                    namespaceFound = true
                    credentialRef = namespace.optJSONObject("value")?.optString("apiKeyEnv")
                        ?.takeIf { it.isNotEmpty() } ?: credentialRef
                    break
                }
                request(DshHostProtocol.CREDENTIALS_DESCRIBE, JSONObject().apply {
                    put("refs", JSONArray().apply { put(credentialRef) })
                }) { credentials, credentialsError ->
                    if (credentialsError != null || credentials == null) {
                        onError(credentialsError ?: "credentials.describe 返回为空")
                        return@request
                    }
                    val value = credentials.optJSONObject("credentials")?.optJSONObject(credentialRef)
                    onSuccess(DshCredentialSetup(
                        providerAvailable = true,
                        configured = value?.optBoolean("configured") == true,
                        writable = settings.optBoolean("writable") && namespaceFound && value?.optBoolean("writable") == true,
                        credentialRef = credentialRef,
                    ))
                }
            }
        }
    }

    override fun saveDeepSeekApiKey(apiKey: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        request(DshHostProtocol.CREDENTIALS_SET, JSONObject().apply {
            put("ref", "DEEPSEEK_API_KEY")
            put("value", apiKey)
        }) { _, error -> if (error == null) onSuccess() else onError(error) }
    }

    override fun loadModels(sessionId: String, onSuccess: (DshSessionModels) -> Unit, onError: (String) -> Unit) {
        request(DshHostProtocol.SESSION_MODELS, JSONObject().apply { put("sessionId", sessionId) }) { value, error ->
            if (error != null || value == null) {
                onError(error ?: "session.models 返回为空")
                return@request
            }
            val current = value.optJSONObject("current") ?: JSONObject()
            val provider = current.optString("provider")
            val model = current.optString("model")
            val effort = current.optString("reasoningEffort").takeIf { it.isNotEmpty() }
            val options = mutableListOf<DshModelOption>()
            val groups = value.optJSONArray("groups") ?: JSONArray()
            for (groupIndex in 0 until groups.length()) {
                val group = groups.optJSONObject(groupIndex) ?: continue
                val groupId = group.optString("id")
                val groupName = group.optString("name").ifEmpty { groupId }
                val models = group.optJSONArray("models") ?: JSONArray()
                for (modelIndex in 0 until models.length()) {
                    val item = models.optJSONObject(modelIndex) ?: continue
                    val id = item.optString("id")
                    if (groupId.isEmpty() || id.isEmpty()) continue
                    val selected = groupId == provider && id == model
                    options += DshModelOption(
                        provider = groupId,
                        providerName = groupName,
                        model = id,
                        name = item.optString("name").ifEmpty { id },
                        description = item.optString("description"),
                        reasoningEffort = if (selected) effort else item.optJSONObject("reasoning")?.optString("defaultEffort"),
                        selected = selected,
                    )
                }
            }
            val selected = options.firstOrNull { it.selected } ?: DshModelOption(provider, provider, model, model.ifEmpty { "选择模型" }, reasoningEffort = effort, selected = true)
            onSuccess(DshSessionModels(selected, options, value.optBoolean("routable")))
        }
    }

    override fun selectModel(sessionId: String, option: DshModelOption, onSuccess: (DshModelOption) -> Unit, onError: (String) -> Unit) {
        request(DshHostProtocol.SESSION_SELECT_MODEL, JSONObject().apply {
            put("sessionId", sessionId)
            put("provider", option.provider)
            put("model", option.model)
            option.reasoningEffort?.let { put("reasoningEffort", it) }
        }) { value, error ->
            if (error != null || value == null) {
                onError(error ?: "session.selectModel 返回为空")
                return@request
            }
            val selected = value.optJSONObject("selected") ?: JSONObject()
            onSuccess(option.copy(
                provider = selected.optString("provider").ifEmpty { option.provider },
                model = selected.optString("model").ifEmpty { option.model },
                reasoningEffort = selected.optString("reasoningEffort").takeIf { it.isNotEmpty() },
                selected = true,
            ))
        }
    }

    override fun loadSessions(onSuccess: (List<DshSession>) -> Unit, onError: (String) -> Unit) {
        request(DshHostProtocol.SESSION_LIST, JSONObject()) { value, error ->
            if (error != null || value == null) {
                onError(error ?: "session.list 返回为空")
                return@request
            }
            val items = value.optJSONArray("items") ?: JSONArray()
            val sessions = buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (item.optBoolean("blank")) continue
                    val id = item.optString("sessionId")
                    if (id.isEmpty()) continue
                    val cwd = item.optString("cwd")
                    val projections = item.optJSONObject("projections")?.optJSONObject("values")
                    val title = projections?.optString("title")?.takeIf { it.isNotEmpty() }
                        ?: cwd.substringAfterLast('/').ifEmpty { id }
                    add(DshSession(id, title, cwd.substringAfterLast('/').ifEmpty { "Host" }, "", running = item.optBoolean("running"), cwd = cwd))
                }
            }
            onSuccess(sessions)
        }
    }

    override fun createSession(workspaceId: String?, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        request(DshHostProtocol.SESSION_CREATE, JSONObject()) { value, error ->
            val id = value?.optString("sessionId").orEmpty()
            if (error != null || id.isEmpty()) onError(error ?: "session.create 返回为空") else onSuccess(id)
        }
    }

    override fun loadHistory(sessionId: String, onSuccess: (List<DshMessage>) -> Unit, onError: (String) -> Unit) {
        request(DshHostProtocol.SESSION_HISTORY, JSONObject().apply {
            put("sessionId", sessionId)
            put("maxMessages", 50)
        }) { value, error ->
            if (error != null || value == null) onError(error ?: "session.history 返回为空")
            else onSuccess(parseLegacyHistory(value.optJSONArray("events") ?: JSONArray()))
        }
    }

    override fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle {
        var closed = false
        var promptSent = false
        var promptAccepted = false
        var fallbackRequested = false
        var fallbackStarted = false
        var observed = false
        var accumulated = ""
        var finalMessage = ""
        var eventHandle: DshSseHandle? = null
        val handle = LegacyStreamHandle {
            closed = true
            eventHandle?.close()
            request(DshHostProtocol.SESSION_CANCEL, JSONObject().apply { put("sessionId", sessionId) }) { _, _ -> }
        }
        fun finish(error: String? = null) {
            if (closed || handle.cancelled) return
            closed = true
            eventHandle?.close()
            if (error == null) onComplete(accumulated.ifEmpty { finalMessage }) else onError(error)
        }
        fun startPolling() {
            if (!promptAccepted || fallbackStarted || closed || handle.cancelled) return
            fallbackStarted = true
            eventHandle?.close()
            pollLegacyReply(sessionId, handle, accumulated, onDelta, onComplete, onError)
        }
        fun sendPrompt() {
            if (promptSent || closed || handle.cancelled) return
            promptSent = true
            request(DshHostProtocol.SESSION_PROMPT, JSONObject().apply {
                put("sessionId", sessionId)
                put("mode", "queue")
                put("content", JSONArray().apply { put(JSONObject().apply { put("type", "text"); put("text", prompt) }) })
            }) { value, error ->
                if (error != null) {
                    finish(error)
                } else {
                    promptAccepted = true
                    if (value?.optJSONObject("command")?.optString("kind") == "success") {
                        finish(value.optJSONObject("command")?.optString("text"))
                    } else if (fallbackRequested) {
                        startPolling()
                    }
                }
            }
        }
        fun requestFallback(message: String) {
            if (closed || handle.cancelled || fallbackStarted) return
            fallbackRequested = true
            eventHandle?.close()
            if (!promptSent) sendPrompt()
            if (promptAccepted) startPolling()
        }
        eventHandle = sse.connect("${connection.baseUrl.trimEnd('/')}${DshHostProtocol.MUX_EVENTS_PATH}", connection.token) { event ->
            if (closed) return@connect
            when (event.kind) {
                DshSseEventKind.OPEN -> sendPrompt()
                DshSseEventKind.ERROR, DshSseEventKind.CLOSED -> requestFallback(event.message.ifEmpty { "本地事件流已断开" })
                DshSseEventKind.FRAME -> {
                    val payload = runCatching { JSONObject(event.data).optJSONObject("payload") }.getOrNull() ?: return@connect
                    if (payload.optString("type") == "stream/error") {
                        requestFallback(payload.optJSONObject("error")?.optString("message") ?: "本地事件流失败")
                        return@connect
                    }
                    if (payload.optString("type") != "session/event" || payload.optString("sessionId") != sessionId) return@connect
                    val record = payload.optJSONObject("event") ?: return@connect
                    val data = record.optJSONObject("data") ?: JSONObject()
                    when (record.optString("type")) {
                        "user/message" -> if (data.optJSONObject("source")?.optString("kind") == "user") observed = true
                        "assistant/chunk" -> if (observed) {
                            val chunk = data.optJSONObject("chunk") ?: return@connect
                            when (chunk.optString("type")) {
                                "text-delta" -> chunk.optString("text").takeIf { it.isNotEmpty() }?.let { accumulated += it; onDelta(it, false) }
                                "finish" -> if (chunk.optJSONObject("reason")?.optString("kind") == "error") finish(chunk.optJSONObject("reason")?.optJSONObject("failure")?.optString("message"))
                            }
                        }
                        "assistant/message" -> if (observed) finalMessage = textFromBlocks(data.optJSONObject("message")?.optJSONArray("content"))
                        "turn/end" -> if (observed) finish(record.optJSONObject("data")?.optJSONObject("reason")?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() })
                    }
                }
            }
        }
        setTimeout(pagerId, SSE_OPEN_TIMEOUT_MS) {
            if (!promptSent && !closed && !handle.cancelled) requestFallback("本地事件流打开超时")
        }
        return handle
    }

    private fun pollLegacyReply(
        sessionId: String,
        handle: LegacyStreamHandle,
        previous: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (handle.cancelled) return
        request(DshHostProtocol.SESSION_HISTORY, JSONObject().apply {
            put("sessionId", sessionId)
            put("maxMessages", 50)
        }) { value, error ->
            if (handle.cancelled) return@request
            if (error != null || value == null) {
                onError(error ?: "session.history 返回为空")
                return@request
            }
            val events = value.optJSONArray("events") ?: JSONArray()
            val snapshot = parseLegacyHistory(events)
            val latest = snapshot.lastOrNull { it.role == DshMessageRole.ASSISTANT }?.content.orEmpty()
            if (latest.length > previous.length && latest.startsWith(previous)) {
                onDelta(latest.substring(previous.length), false)
            } else if (latest != previous && latest.isNotEmpty()) {
                onDelta(latest, false)
            }
            var lastUserSeq = -1
            var completed = false
            var failure = ""
            for (index in 0 until events.length()) {
                val record = events.optJSONObject(index)?.optJSONObject("event") ?: continue
                val seq = record.optInt("seq", index)
                when (record.optString("type")) {
                    "user/message" -> if (record.optJSONObject("data")?.optJSONObject("source")?.optString("kind") == "user") lastUserSeq = seq
                    "turn/end" -> if (seq > lastUserSeq) {
                        completed = true
                        failure = record.optJSONObject("data")?.optJSONObject("reason")?.optJSONObject("error")?.optString("message").orEmpty()
                    }
                }
            }
            when {
                completed && failure.isNotEmpty() -> onError(failure)
                completed -> onComplete(latest)
                else -> setTimeout(pagerId, POLL_INTERVAL_MS) {
                    pollLegacyReply(sessionId, handle, latest, onDelta, onComplete, onError)
                }
            }
        }
    }

    private fun request(method: String, payload: JSONObject, callback: (JSONObject?, String?) -> Unit) {
        val body = JSONObject().apply {
            put("type", "client-request")
            put("rpcId", "dsh-local-${++rpcSequence}")
            put("method", method)
            put("payload", payload)
        }
        val headers = JSONObject().apply {
            put("Content-Type", "application/json")
            if (connection.token.isNotEmpty()) put("Authorization", "Bearer ${connection.token}")
        }
        network.httpRequest("${connection.baseUrl.trimEnd('/')}${DshHostProtocol.API_PREFIX}/$method", true, body, headers, null, 30) { data, success, errorMsg, response ->
            if (!success) { callback(null, "$method failed (${response.statusCode ?: 0}): $errorMsg"); return@httpRequest }
            val result = data.optJSONObject("result")
            if (result == null) { callback(null, "$method 返回了非法 RPC 信封"); return@httpRequest }
            if (!result.optBoolean("ok")) { callback(null, result.optJSONObject("error")?.optString("message") ?: "$method 失败"); return@httpRequest }
            callback(result.optJSONObject("value"), null)
        }
    }
}

private class LegacyStreamHandle(private val cancelAction: () -> Unit) : DshStreamHandle {
    var cancelled: Boolean = false
        private set

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        cancelAction()
    }
}

private const val SSE_OPEN_TIMEOUT_MS = 3_000
private const val POLL_INTERVAL_MS = 450

private fun parseLegacyHistory(events: JSONArray): List<DshMessage> {
    val result = mutableListOf<DshMessage>()
    val partials = mutableMapOf<String, StringBuilder>()
    for (index in 0 until events.length()) {
        val entry = events.optJSONObject(index) ?: continue
        val event = entry.optJSONObject("event") ?: entry
        val seq = event.optInt("seq", index)
        val data = event.optJSONObject("data") ?: continue
        when (event.optString("type")) {
            "user/message" -> textFromBlocks(data.optJSONArray("content")).takeIf { it.isNotEmpty() }?.let { result += DshMessage("user-$seq", DshMessageRole.USER, it) }
            "assistant/chunk" -> {
                val key = "${data.optInt("turn")}:${data.optInt("step")}"; val text = data.optJSONObject("chunk")?.optString("text").orEmpty()
                if (text.isNotEmpty()) partials.getOrPut(key) { StringBuilder() }.append(text)
            }
            "assistant/message" -> {
                val key = "${data.optInt("turn")}:${data.optInt("step")}"; val message = data.optJSONObject("message") ?: data
                val text = textFromBlocks(message.optJSONArray("content")).ifEmpty { partials[key]?.toString().orEmpty() }
                if (text.isNotEmpty()) result += DshMessage("assistant-$seq", DshMessageRole.ASSISTANT, text)
                partials.remove(key)
            }
            "tool/call" -> result += DshMessage("tool-$seq", DshMessageRole.TOOL, "正在执行 ${data.optString("name").ifEmpty { "工具" }}", toolName = data.optString("name"))
            "turn/end" -> data.optJSONObject("reason")?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }?.let { result += DshMessage("turn-error-$seq", DshMessageRole.ERROR, it) }
        }
    }
    partials.forEach { (key, text) -> if (text.isNotEmpty()) result += DshMessage("partial-$key", DshMessageRole.ASSISTANT, text.toString(), streaming = true) }
    return result
}
