package com.example.dsh.dsh

import net.shantu.kuiklysqlite.ColumnType
import net.shantu.kuiklysqlite.DatabaseManager
import net.shantu.kuiklysqlite.SqlDriver
import net.shantu.kuiklysqlite.SqlSchema
import net.shantu.kuiklysqlite.SqlStatement

internal actual fun createDshLocalStore(path: String, legacyProfile: DshLegacyRemoteProfile?): DshLocalStore = DshSqliteStore(path)

private class DshSqliteStore(path: String) : DshLocalStore {
    private val driver: SqlDriver by lazy { DatabaseManager(path, DshSchema).driver }

    override fun loadApiKey(): String = query("SELECT value FROM dsh_settings WHERE key = ?", listOf("deepseek_api_key")) {
        it.getColumnString(0)
    }.firstOrNull().orEmpty()

    override fun saveApiKey(apiKey: String) = execute(
        "INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)",
        listOf("deepseek_api_key", apiKey),
    )

    override fun loadLastConnectionMode(): DshConnectionMode = DshConnectionMode.LOCAL
    override fun saveLastConnectionMode(mode: DshConnectionMode) = Unit
    override fun loadRemoteProfile(): DshRemoteProfile? = null
    override fun saveRemoteProfile(profile: DshRemoteProfile) = Unit
    override fun loadRelayProfile(): DshRelayProfile? = null
    override fun saveRelayProfile(profile: DshRelayProfile) = Unit
    override fun clearRelayProfile() = Unit
    override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile): Boolean = false

    override fun loadSessions(connectionId: String): List<DshSession> = query(
        "SELECT id, title, workspace, updated_label, running FROM dsh_sessions ORDER BY updated_at DESC",
        emptyList(),
    ) { s -> DshSession(s.getColumnString(0), s.getColumnString(1), s.getColumnString(2), s.getColumnString(3), s.getColumnLong(4) != 0L) }

    override fun replaceSessions(connectionId: String, sessions: List<DshSession>) = driver.transaction {
        driver.execute("DELETE FROM dsh_sessions")
        sessions.forEachIndexed { index, s ->
            execute("INSERT OR REPLACE INTO dsh_sessions (id, title, workspace, updated_label, running, updated_at) VALUES (?, ?, ?, ?, ?, ?)", listOf(s.id, s.title, s.workspace, s.updatedLabel, if (s.running) "1" else "0", (-index).toString()))
        }
    }

    override fun loadMessages(connectionId: String, sessionId: String): List<DshMessage> = query(
        "SELECT id, role, content, streaming, tool_name, hidden FROM dsh_messages WHERE session_id = ? ORDER BY seq ASC",
        listOf(sessionId),
    ) { s -> DshMessage(s.getColumnString(0), DshMessageRole.valueOf(s.getColumnString(1)), s.getColumnString(2), s.getColumnLong(3) != 0L, nullableString(s, 4), s.getColumnLong(5) != 0L) }

    override fun replaceMessages(connectionId: String, sessionId: String, messages: List<DshMessage>) = driver.transaction {
        execute("DELETE FROM dsh_messages WHERE session_id = ?", listOf(sessionId))
        messages.forEachIndexed { index, m ->
            execute("INSERT OR REPLACE INTO dsh_messages (id, session_id, role, content, streaming, tool_name, hidden, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", listOf(m.id, sessionId, m.role.name, m.content, if (m.streaming) "1" else "0", m.toolName, if (m.hidden) "1" else "0", index.toString()))
        }
    }

    override fun clearScope(scopeId: String) = driver.transaction {
        execute("DELETE FROM dsh_messages", emptyList())
        execute("DELETE FROM dsh_sessions", emptyList())
    }

    private fun execute(sql: String, args: List<String?>) {
        val s = driver.prepare(sql)
        try { bind(s, args); s.step() } finally { s.close() }
    }

    private fun <T> query(sql: String, args: List<String?>, mapper: (SqlStatement) -> T): List<T> {
        val s = driver.prepare(sql)
        return try { bind(s, args); buildList { while (s.step()) add(mapper(s)) } } finally { s.close() }
    }

    private fun bind(s: SqlStatement, args: List<String?>) = args.forEachIndexed { i, value -> s.bindString(i + 1, value) }
    private fun nullableString(s: SqlStatement, index: Int): String? = if (s.getColumnType(index) == ColumnType.NULL) null else s.getColumnString(index)
}

private object DshSchema : SqlSchema {
    override val version: Int = 2
    override fun create(driver: SqlDriver) {
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, workspace TEXT NOT NULL, updated_label TEXT NOT NULL, running INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_messages (id TEXT NOT NULL, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, streaming INTEGER NOT NULL, tool_name TEXT, hidden INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (session_id, id))")
        driver.execute("CREATE INDEX IF NOT EXISTS idx_dsh_messages_session ON dsh_messages(session_id, seq)")
    }
    override fun migrate(driver: SqlDriver, oldVersion: Int, newVersion: Int) {
        if (oldVersion >= 2) return
        driver.execute("DROP INDEX IF EXISTS idx_dsh_messages_session")
        driver.execute("ALTER TABLE dsh_messages RENAME TO dsh_messages_v1")
        driver.execute("CREATE TABLE dsh_messages (id TEXT NOT NULL, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, streaming INTEGER NOT NULL, tool_name TEXT, hidden INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (session_id, id))")
        driver.execute("INSERT OR REPLACE INTO dsh_messages (id, session_id, role, content, streaming, tool_name, hidden, seq) SELECT id, session_id, role, content, streaming, tool_name, hidden, seq FROM dsh_messages_v1")
        driver.execute("DROP TABLE dsh_messages_v1")
        driver.execute("CREATE INDEX idx_dsh_messages_session ON dsh_messages(session_id, seq)")
    }
}
