package com.example.dsh.dsh

import com.tencent.kuikly.core.log.KLog
import com.tencent.kuiklybase.streaming.MarkdownBlock

/** Logcat filter: `DshStream`. */
internal object DshStreamLog {
    private const val TAG = "DshStream"

    fun i(message: String) {
        KLog.i(TAG, message)
    }

    fun question(message: String) {
        KLog.i("DshQuestion", message)
        i("question.$message")
    }

    fun preview(text: String, max: Int = 96): String {
        val flat = text.replace("\r", "\\r").replace("\n", "\\n")
        return if (flat.length <= max) flat else "${flat.take(max)}…(+${flat.length - max})"
    }

    fun blocks(blocks: List<MarkdownBlock>): String {
        if (blocks.isEmpty()) return "blockCount=0"
        val items = blocks.joinToString("; ") { block ->
            val kind = blockKind(block.blockContent)
            "#${block.blockIndex} kind=$kind id=${block.id} chars=${block.blockContent.length} '${preview(block.blockContent, 48)}'"
        }
        return "blockCount=${blocks.size} [$items]"
    }

    fun blockKind(content: String): String {
        val line = content.trimStart()
        return when {
            line.startsWith("```") -> "code"
            line.startsWith("~~~") -> "code"
            line.startsWith("# ") -> "h1"
            line.startsWith("## ") -> "h2"
            line.startsWith("### ") -> "h3"
            line.startsWith("> ") -> "quote"
            line.startsWith("- ") || line.startsWith("* ") -> "list"
            line.firstOrNull()?.isDigit() == true && line.contains(". ") -> "olist"
            line.startsWith("|") -> "table"
            line.isEmpty() -> "empty"
            else -> "paragraph"
        }
    }
}
