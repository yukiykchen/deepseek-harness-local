package com.example.dsh.dsh

import com.tencent.kuiklybase.streaming.MarkdownBlock
import com.tencent.kuiklybase.streaming.MarkdownStreamingState

/**
 * Streaming markdown helpers modeled on:
 * - Flutter streamdown: append-only trailing block, stable keys for sealed blocks,
 *   provisional rendering of an unclosed fence as a code block
 * - CMP compose-markdown / llm-typewriter: prefix-stable snapshots, live last block
 * - Cherry Studio: ~1 frame (16ms) coalescing after the first paint
 */
internal object DshStreamingMarkdown {
    const val PLACEHOLDER = "正在生成..."
    const val FRAME_MS = 16
    const val CURSOR = "▍"

    fun displayText(raw: String, streaming: Boolean): String {
        if (raw.isEmpty()) return if (streaming) PLACEHOLDER else raw
        return if (streaming) closeOpenFence(raw) else raw
    }

    /**
     * An unclosed ``` / ~~~ fence would otherwise flash as a paragraph.
     * Close it for parse only so the tail renders as a code block immediately.
     */
    internal fun closeOpenFence(text: String): String {
        var openMarker: String? = null
        text.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            } ?: return@forEach
            if (openMarker == null) {
                openMarker = marker
            } else if (trimmed.startsWith(openMarker!!)) {
                openMarker = null
            }
        }
        val marker = openMarker ?: return text
        return if (text.endsWith("\n")) text + marker else text + "\n" + marker
    }

    fun applyBlocks(
        blockList: MutableList<MarkdownBlock>,
        next: List<MarkdownBlock>,
        streaming: Boolean,
    ) {
        while (blockList.size > next.size) {
            blockList.removeAt(blockList.lastIndex)
        }
        next.forEachIndexed { index, block ->
            if (index < blockList.size) {
                val openTail = streaming && index == next.lastIndex
                if (openTail || blockList[index].id != block.id) {
                    blockList[index] = block
                }
            } else {
                blockList.add(block)
            }
        }
    }
}

internal fun MarkdownStreamingState.renderStreaming(
    raw: String,
    streaming: Boolean,
    force: Boolean,
): List<MarkdownBlock>? {
    val text = DshStreamingMarkdown.displayText(raw, streaming)
    return update(text, force = force || !streaming)
}
