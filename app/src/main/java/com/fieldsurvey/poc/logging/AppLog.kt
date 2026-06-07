package com.fieldsurvey.poc.logging

import android.content.Context
import com.fieldsurvey.poc.tracking.DateKeys
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lightweight append-only activity logger. One plain-text file per calendar
 * date (`logs/YYYY-MM-DD.log`) inside the app's private `filesDir`, so log
 * files are bucketed exactly like tracking data and can be shown / shared /
 * purged per date.
 *
 * Writes are serialized on a single background thread; reads synchronize on
 * the same lock to avoid torn lines. Safe to call from any thread.
 */
object AppLog {

    private const val DIR = "logs"

    private val writer = Executors.newSingleThreadExecutor()
    private val lineTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var logsDir: File? = null

    /** Must be called once (FieldSurveyApp.onCreate) before any [log] call. */
    fun init(context: Context) {
        if (logsDir != null) return
        val dir = File(context.applicationContext.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        logsDir = dir
    }

    fun log(tag: String, message: String) {
        val dir = logsDir ?: return
        // Capture wall-clock + date on the caller thread (both thread-safe:
        // currentTimeMillis is atomic and DateKeys builds a fresh formatter),
        // but defer the SimpleDateFormat.format() to the single writer thread —
        // SimpleDateFormat is NOT thread-safe and was previously formatted on
        // arbitrary caller threads, which could corrupt output or crash.
        val whenMs = System.currentTimeMillis()
        val dateKey = DateKeys.today()
        writer.execute {
            synchronized(lock) {
                runCatching {
                    val ts = lineTime.format(Date(whenMs))
                    File(dir, "$dateKey.log").appendText("$ts [$tag] $message\n")
                }
            }
        }
    }

    fun fileFor(dateKey: String): File =
        File(logsDir ?: File(DIR), "$dateKey.log")

    fun read(dateKey: String): String = synchronized(lock) {
        val f = fileFor(dateKey)
        if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
    }

    fun hasLog(dateKey: String): Boolean = fileFor(dateKey).exists()

    /** Deletes the log file for a single [dateKey] (used by the per-day reset). */
    fun deleteForDate(dateKey: String) {
        synchronized(lock) {
            val f = fileFor(dateKey)
            if (f.exists()) runCatching { f.delete() }
        }
    }

    /** Deletes every log file whose date is strictly older than [cutoffDateKey]. */
    fun deleteOlderThan(cutoffDateKey: String) {
        val dir = logsDir ?: return
        synchronized(lock) {
            dir.listFiles()?.forEach { f ->
                val name = f.name.removeSuffix(".log")
                if (name < cutoffDateKey) runCatching { f.delete() }
            }
        }
    }
}
