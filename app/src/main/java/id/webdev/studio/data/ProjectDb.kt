package id.webdev.studio.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Penyimpanan daftar proyek (SQLite bawaan Android, tanpa dependency tambahan). */
class ProjectDb(ctx: Context) : SQLiteOpenHelper(ctx, "webdev.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects(
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              source_type TEXT NOT NULL,
              source_uri TEXT NOT NULL,
              techs TEXT NOT NULL,
              hosting_mode TEXT NOT NULL,
              port INTEGER NOT NULL,
              dev_script TEXT,
              backend_entry TEXT,
              static_root TEXT NOT NULL DEFAULT '',
              notes TEXT NOT NULL DEFAULT '',
              created_at INTEGER NOT NULL,
              last_opened INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Belum ada migrasi; versi 1.
    }

    fun all(): List<Project> =
        readableDatabase.query("projects", null, null, null, null, null, "last_opened DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toProject()) }
        }

    fun upsert(p: Project) {
        val v = ContentValues().apply {
            put("id", p.id)
            put("name", p.name)
            put("source_type", p.sourceType.name)
            put("source_uri", p.sourceUri)
            put("techs", p.techs.joinToString("|"))
            put("hosting_mode", p.hostingMode.name)
            put("port", p.port)
            put("dev_script", p.devScript)
            put("backend_entry", p.backendEntry)
            put("static_root", p.staticRoot)
            put("notes", p.notes.joinToString("\n"))
            put("created_at", p.createdAt)
            put("last_opened", p.lastOpened)
        }
        writableDatabase.insertWithOnConflict("projects", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(id: String) {
        writableDatabase.delete("projects", "id = ?", arrayOf(id))
    }

    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col))
    private fun Cursor.strOrNull(col: String): String? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.toProject() = Project(
        id = str("id"),
        name = str("name"),
        sourceType = SourceType.valueOf(str("source_type")),
        sourceUri = str("source_uri"),
        techs = str("techs").split('|').filter { it.isNotBlank() },
        hostingMode = runCatching { HostingMode.valueOf(str("hosting_mode")) }
            .getOrDefault(HostingMode.UNSUPPORTED),
        port = getInt(getColumnIndexOrThrow("port")),
        devScript = strOrNull("dev_script"),
        backendEntry = strOrNull("backend_entry"),
        staticRoot = str("static_root"),
        notes = str("notes").split('\n').filter { it.isNotBlank() },
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        lastOpened = getLong(getColumnIndexOrThrow("last_opened"))
    )
}
