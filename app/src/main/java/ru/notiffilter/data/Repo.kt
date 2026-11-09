package ru.notiffilter.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.notiffilter.classify.AppMode
import ru.notiffilter.classify.CustomKeyword
import ru.notiffilter.classify.Sensitivity
import java.util.Calendar
import java.util.concurrent.Executors

/** Запись журнала: скрытое (blocked) или пропущенное уведомление. */
data class LogEntry(
    val id: Long,
    val token: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val channel: String,
    val time: Long,
    val score: Int,
    val reasons: String,
    val blocked: Boolean,
    /** Реально убрано из шторки (false в тестовом режиме). */
    val dismissed: Boolean,
)

private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "notif.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT, pkg TEXT, app TEXT,
               title TEXT, text TEXT, channel TEXT, time INTEGER, score INTEGER, reasons TEXT,
               blocked INTEGER, dismissed INTEGER)"""
        )
        db.execSQL("CREATE INDEX log_time ON log(blocked, time)")
        db.execSQL("CREATE TABLE app_rule(pkg TEXT PRIMARY KEY, mode TEXT)")
        db.execSQL("CREATE TABLE keyword(word TEXT PRIMARY KEY, weight INTEGER)")
        // Мессенджеры по умолчанию доверенные: там личная переписка.
        for (p in DEFAULT_TRUSTED) {
            db.insert("app_rule", null, ContentValues().apply { put("pkg", p); put("mode", AppMode.ALLOW.name) })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        val DEFAULT_TRUSTED = listOf(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
            "com.whatsapp", "com.whatsapp.w4b", "com.vk.im", "ru.oneme.app", "com.discord",
            "com.google.android.dialer", "com.android.dialer", "com.google.android.deskclock",
            "com.google.android.calendar",
        )
    }
}

/** Единая точка доступа к данным. Используется и сервисом, и UI (один процесс). */
object Repo {
    private lateinit var db: Db
    private lateinit var prefs: SharedPreferences
    private val io = Executors.newSingleThreadExecutor()

    private val _changes = MutableStateFlow(0L)
    /** Меняется при любой записи — UI перечитывает данные. */
    val changes: StateFlow<Long> = _changes

    @Volatile private var appModes: Map<String, AppMode> = emptyMap()
    @Volatile var keywords: List<CustomKeyword> = emptyList(); private set

    private const val MAX_PASSED = 300
    private const val KEEP_DAYS = 30L

    @Synchronized
    fun init(ctx: Context) {
        if (::db.isInitialized) return
        db = Db(ctx.applicationContext)
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        reloadCaches()
        io.execute { cleanup() }
    }

    private fun bump() { _changes.value = _changes.value + 1 }

    // ---------- Настройки ----------
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) { prefs.edit().putBoolean("enabled", v).apply(); bump() }

    /** Тестовый режим: только записывать в журнал, не убирать из шторки. */
    var testMode: Boolean
        get() = prefs.getBoolean("testMode", false)
        set(v) { prefs.edit().putBoolean("testMode", v).apply(); bump() }

    var sensitivity: Sensitivity
        get() = runCatching { Sensitivity.valueOf(prefs.getString("sensitivity", null) ?: "") }
            .getOrDefault(Sensitivity.MEDIUM)
        set(v) { prefs.edit().putString("sensitivity", v.name).apply(); bump() }

    // ---------- Правила приложений ----------
    fun modeOf(pkg: String): AppMode = appModes[pkg] ?: AppMode.AUTO
    fun allModes(): Map<String, AppMode> = appModes

    fun setMode(pkg: String, mode: AppMode) {
        val w = db.writableDatabase
        if (mode == AppMode.AUTO) w.delete("app_rule", "pkg=?", arrayOf(pkg))
        else w.insertWithOnConflict(
            "app_rule", null,
            ContentValues().apply { put("pkg", pkg); put("mode", mode.name) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        reloadCaches(); bump()
    }

    // ---------- Свои слова ----------
    fun addKeyword(word: String, weight: Int) {
        val w = word.trim()
        if (w.isEmpty()) return
        db.writableDatabase.insertWithOnConflict(
            "keyword", null,
            ContentValues().apply { put("word", w); put("weight", weight) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        reloadCaches(); bump()
    }

    fun removeKeyword(word: String) {
        db.writableDatabase.delete("keyword", "word=?", arrayOf(word))
        reloadCaches(); bump()
    }

    private fun reloadCaches() {
        val r = db.readableDatabase
        appModes = buildMap {
            r.rawQuery("SELECT pkg, mode FROM app_rule", null).use { c ->
                while (c.moveToNext()) {
                    runCatching { AppMode.valueOf(c.getString(1)) }.onSuccess { put(c.getString(0), it) }
                }
            }
        }
        keywords = buildList {
            r.rawQuery("SELECT word, weight FROM keyword ORDER BY word", null).use { c ->
                while (c.moveToNext()) add(CustomKeyword(c.getString(0), c.getInt(1)))
            }
        }
    }

    // ---------- Журнал ----------
    fun log(e: LogEntry) = io.execute {
        val w = db.writableDatabase
        w.insert("log", null, ContentValues().apply {
            put("token", e.token); put("pkg", e.pkg); put("app", e.app)
            put("title", e.title); put("text", e.text); put("channel", e.channel)
            put("time", e.time); put("score", e.score); put("reasons", e.reasons)
            put("blocked", if (e.blocked) 1 else 0); put("dismissed", if (e.dismissed) 1 else 0)
        })
        if (!e.blocked) {
            // Пропущенные храним только последние MAX_PASSED — для обучения, не как архив.
            w.execSQL(
                "DELETE FROM log WHERE blocked=0 AND id NOT IN " +
                    "(SELECT id FROM log WHERE blocked=0 ORDER BY id DESC LIMIT $MAX_PASSED)"
            )
        }
        bump()
    }

    fun entries(blocked: Boolean, limit: Int = 500): List<LogEntry> {
        val c = db.readableDatabase.rawQuery(
            "SELECT id, token, pkg, app, title, text, channel, time, score, reasons, blocked, dismissed " +
                "FROM log WHERE blocked=? ORDER BY time DESC LIMIT $limit",
            arrayOf(if (blocked) "1" else "0"),
        )
        return c.use {
            buildList {
                while (c.moveToNext()) add(
                    LogEntry(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                        c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getLong(7),
                        c.getInt(8), c.getString(9) ?: "", c.getInt(10) == 1, c.getInt(11) == 1,
                    )
                )
            }
        }
    }

    fun delete(id: Long) { db.writableDatabase.delete("log", "id=?", arrayOf(id.toString())); bump() }

    fun deleteByPkg(pkg: String, blocked: Boolean) {
        db.writableDatabase.delete("log", "pkg=? AND blocked=?", arrayOf(pkg, if (blocked) "1" else "0")); bump()
    }

    fun clear(blocked: Boolean) {
        db.writableDatabase.delete("log", "blocked=?", arrayOf(if (blocked) "1" else "0")); bump()
    }

    data class Stats(val today: Int, val total: Int)

    fun stats(): Stats {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val r = db.readableDatabase
        fun count(sql: String, vararg a: String) = r.rawQuery(sql, a).use { it.moveToFirst(); it.getInt(0) }
        return Stats(
            today = count("SELECT COUNT(*) FROM log WHERE blocked=1 AND time>=?", start.toString()),
            total = count("SELECT COUNT(*) FROM log WHERE blocked=1"),
        )
    }

    /** Сколько скрыто по каждому приложению — для сортировки на экране «Приложения». */
    fun blockedCountByPkg(): Map<String, Int> = db.readableDatabase
        .rawQuery("SELECT pkg, COUNT(*) FROM log WHERE blocked=1 GROUP BY pkg", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    private fun cleanup() {
        val border = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        db.writableDatabase.delete("log", "time<?", arrayOf(border.toString()))
    }
}
