package com.example.dsh.engine

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

internal enum class EnginePhase { IDLE, PREPARING, STARTING, READY, ERROR, STOPPED }

internal data class EngineState(
    val phase: EnginePhase,
    val progress: Int = 0,
    val message: String = "",
)

/** Process-wide owner of the Android Node.js + DeepSeek Harness runtime. */
internal object DshEngineManager {
    private const val TAG = "DshEngine"
    private const val ENGINE_URL = "http://127.0.0.1:3080"
    private const val BIN_JS = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
    private const val SHIZUKU_DEX = "rish/rish_shizuku.dex"
    private const val ENGINE_REVISION = "20260819170527"
    private const val MARKER = ".prepared-$ENGINE_REVISION"

    private val listeners = CopyOnWriteArrayList<(EngineState) -> Unit>()
    @Volatile private var state = EngineState(EnginePhase.IDLE)
    @Volatile private var process: Process? = null
    @Volatile private var booting = false
    @Volatile private var watchdogStarted = false
    private var engineGeneration = 0L
    private var stopped = false
    private lateinit var appContext: Context

    @Synchronized
    fun start(context: Context, listener: (EngineState) -> Unit) {
        appContext = context.applicationContext
        stopped = false
        val generation = ++engineGeneration
        listeners += listener
        listener(state)
        if (healthOk() && process?.isAlive == true) {
            publish(EngineState(EnginePhase.READY, 100, "本地 Harness 已就绪"))
            startWatchdog(generation)
            return
        }
        if (booting) return
        booting = true
        Thread({ boot(generation) }, "dsh-engine-boot").start()
    }

    fun removeListener(listener: (EngineState) -> Unit) {
        listeners -= listener
    }

    fun currentState(): EngineState = state

    @Synchronized
    fun stop() {
        stopped = true
        ++engineGeneration
        watchdogStarted = false
        process?.destroy()
        process = null
        booting = false
        publish(EngineState(EnginePhase.STOPPED, message = "本地 Harness 已停止"))
    }

    private fun boot(myGeneration: Long) {
        try {
            waitForPortRelease(myGeneration)
            val root = File(appContext.filesDir, "dsh-engine")
            prepare(root)
            if (stopped || myGeneration != engineGeneration) return
            startProcess(root)
            waitUntilReady(myGeneration)
            if (!stopped && myGeneration == engineGeneration) startWatchdog(myGeneration)
        } catch (error: Throwable) {
            Log.e(TAG, "Engine boot failed", error)
            publish(EngineState(EnginePhase.ERROR, message = error.message ?: "本地内核启动失败"))
        } finally {
            booting = false
        }
    }

    private fun prepare(root: File) {
        val marker = File(root, MARKER)
        if (
            marker.isFile &&
            marker.readText().trim() == ENGINE_REVISION &&
            File(root, BIN_JS).isFile &&
            File(root, SHIZUKU_DEX).isFile
        ) {
            publish(EngineState(EnginePhase.PREPARING, 100, "运行时已准备"))
            applyLinks(root)
            setExecutables(root)
            return
        }
        publish(EngineState(EnginePhase.PREPARING, 0, "首次启动，正在解压 Harness 内核"))
        if (!root.exists() && !root.mkdirs()) error("无法创建运行目录")
        val total = countPayloadEntries()
        appContext.assets.open("payload.zip").use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var processed = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && !name.startsWith("META-INF/") && !name.startsWith("__MACOSX/")) {
                        val target = File(root, name)
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> zip.copyTo(output, 128 * 1024) }
                        processed += 1
                        if (processed == total || processed % 200 == 0) {
                            val progress = if (total == 0) 0 else processed * 100 / total
                            publish(EngineState(EnginePhase.PREPARING, progress, "正在解压内核 $processed/$total"))
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        applyLinks(root)
        setExecutables(root)
        marker.writeText(ENGINE_REVISION)
    }

    private fun countPayloadEntries(): Int {
        var count = 0
        appContext.assets.open("payload.zip").use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && !entry.name.startsWith("META-INF/") && !entry.name.startsWith("__MACOSX/")) count++
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return count
    }

    private fun applyLinks(root: File) {
        val libDir = File(root, "runtime/lib")
        val links = File(libDir, "LINKS.txt")
        if (!links.exists()) return
        links.forEachLine { line ->
            val parts = line.trim().split('\t')
            if (parts.size < 2 || line.trim().startsWith("#")) return@forEachLine
            val link = File(libDir, parts[0])
            val target = File(libDir, parts[1])
            if (!link.exists() && target.exists()) target.copyTo(link, overwrite = false)
        }
    }

    private fun setExecutables(root: File) {
        File(root, "runtime/bin/node").setExecutable(true, false)
        File(root, "bin/bash").setExecutable(true, false)
    }

    private fun startProcess(root: File) {
        if (healthOk()) return
        val node = File(root, "runtime/bin/node")
        val binJs = File(root, BIN_JS)
        if (!node.exists()) error("Node.js runtime 缺失")
        if (!binJs.exists()) error("DeepSeek Harness 内核缺失")
        publish(EngineState(EnginePhase.STARTING, 100, "正在启动本地 Harness"))
        val builder = ProcessBuilder(
            node.absolutePath,
            "--expose-internals",
            binJs.absolutePath,
            "web",
            "--host", "127.0.0.1",
            "--port", "3080",
        )
        builder.directory(root)
        builder.redirectErrorStream(true)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", File(root, "runtime/lib").absolutePath)
            put("PATH", listOf(File(root, "bin"), File(root, "runtime/bin")).joinToString(":") { it.absolutePath } + ":/system/bin:/system/xbin")
            put("HOME", appContext.filesDir.absolutePath)
            put("DSH_HOME", File(root, "dshhome").absolutePath)
            // The DSH Android tools launch app_process with this dex to enter
            // the Shizuku shell. Keep the path inside the prepared runtime so
            // the Node process can resolve it after every app start.
            put("SHIZUKU_DEX", File(root, SHIZUKU_DEX).absolutePath)
            put("SHIZUKU_APP_ID", appContext.packageName)
            put("TMPDIR", File(appContext.cacheDir, "dsh-tmp").apply { mkdirs() }.absolutePath)
            put("TERM", "xterm")
        }
        process = builder.start()
        val log = File(appContext.filesDir, "dsh-engine.log")
        Thread({
            process?.inputStream?.bufferedReader()?.useLines { lines ->
                FileOutputStream(log, true).bufferedWriter().use { writer ->
                    lines.forEach { line ->
                        writer.appendLine(line)
                        writer.flush()
                        Log.i(TAG, line)
                    }
                }
            }
        }, "dsh-engine-log").start()
    }

    private fun waitUntilReady(myGeneration: Long) {
        repeat(90) { second ->
            if (stopped || myGeneration != engineGeneration) return
            if (process?.isAlive == true && healthOk()) {
                publish(EngineState(EnginePhase.READY, 100, "本地 Harness 已就绪"))
                return
            }
            if (process?.isAlive == false) error("Node.js 进程已退出")
            publish(EngineState(EnginePhase.STARTING, 100, "正在启动本地 Harness（${second + 1}s）"))
            Thread.sleep(1_000)
        }
        error("本地 Harness 启动超时")
    }

    private fun waitForPortRelease(myGeneration: Long) {
        repeat(PORT_RELEASE_RETRIES) { attempt ->
            if (stopped || myGeneration != engineGeneration) return
            if (!isPortOpen()) return
            Thread.sleep(PORT_RELEASE_DELAYS_MS[attempt.coerceAtMost(PORT_RELEASE_DELAYS_MS.lastIndex)])
        }
        if (isPortOpen()) error("LOCAL_PORT_IN_USE")
    }

    private fun isPortOpen(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 3080), 200)
            true
        }
    }.getOrDefault(false)

    private fun healthOk(): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(ENGINE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 800
            connection.readTimeout = 800
            connection.responseCode in 200..499
        } catch (_: Throwable) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun startWatchdog(myGeneration: Long) {
        if (watchdogStarted) return
        watchdogStarted = true
        Thread({
            while (!stopped && myGeneration == engineGeneration) {
                Thread.sleep(5_000)
                if (stopped || myGeneration != engineGeneration) return@Thread
                if (healthOk()) continue
                if (booting) continue
                booting = true
                Thread({ boot(myGeneration) }, "dsh-engine-restart").start()
            }
            watchdogStarted = false
        }, "dsh-engine-watchdog").start()
    }

    private fun publish(next: EngineState) {
        state = next
        listeners.forEach { listener -> runCatching { listener(next) } }
    }

    private const val PORT_RELEASE_RETRIES = 10
    private val PORT_RELEASE_DELAYS_MS = longArrayOf(100, 200, 400, 800, 1_000)
}
