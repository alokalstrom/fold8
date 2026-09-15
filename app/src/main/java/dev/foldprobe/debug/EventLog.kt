package dev.foldprobe.debug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sensor producers never wait for storage; overflow is counted explicitly. */
class EventLog(private val context: Context) {
    val session = "${System.currentTimeMillis()}-${android.os.Process.myPid()}"
    private val directory = File(context.filesDir, "sessions").apply { mkdirs() }
    private var part = 0
    private var output = File(directory, "$session-$part.jsonl")
    private val dropped = AtomicLong()
    val storageError = MutableStateFlow<String?>(null)
    private val recent = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()
    private var lastPublish = 0L
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8192))

    init {
        // Keep the latest ten bounded parts, including across process restarts.
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
    }

    @Synchronized
    fun record(source: String, event: String, data: Map<String, Any?> = emptyMap(), sample: Boolean = false) {
        val now = SystemClock.elapsedRealtimeNanos()
        val json = JSONObject().put("session", session).put("wallTimeMs", System.currentTimeMillis())
            .put("elapsedRealtimeNs", now).put("source", source).put("event", event)
            .put("data", JSONObject(data.mapValues { safeValue(it.value) })).put("droppedRecordsTotal", dropped.get()).toString()
        if (!sample) Log.i("FoldProbe", json)
        recent.addFirst("${now / 1_000_000} $source/$event ${JSONObject(data.mapValues { safeValue(it.value) })}".take(600))
        while (recent.size > 150) recent.removeLast()
        if (!sample || now - lastPublish > 250_000_000) {
            _lines.value = recent.toList()
            lastPublish = now
        }
        try {
            writer.execute {
                try {
                    if (output.length() > 8 * 1024 * 1024) {
                        part++
                        output = File(directory, "$session-$part.jsonl")
                        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }
                    }
                    output.appendText(json + "\n")
                } catch (e: Exception) { storageError.value = e.toString(); Log.e("FoldProbe", "Log write failed", e) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun safeValue(value: Any?): Any? = when (value) {
        is Float -> if (value.isFinite()) value else value.toString()
        is Double -> if (value.isFinite()) value else value.toString()
        is Map<*, *> -> value.entries.associate { it.key.toString() to safeValue(it.value) }
        is Iterable<*> -> value.map { safeValue(it) }
        else -> value
    }

    fun failure(source: String, event: String, error: Throwable) = record(source, event, mapOf(
        "exception" to error.javaClass.name, "message" to error.message, "stack" to Log.getStackTraceString(error)))

    /** FIFO barrier: all accepted earlier records are written before the snapshot. */
    fun export(done: (Result<File>) -> Unit) {
        record("log", "export", mapOf("dropped" to dropped.get(), "storageError" to storageError.value))
        try {
            writer.execute {
                done(runCatching {
                    val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
                    exportDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
                    val target = File(exportDir, "fold-probe-$session-${System.currentTimeMillis()}.zip")
                    ZipOutputStream(target.outputStream()).use { zip ->
                        directory.listFiles()?.sortedBy { it.name }?.forEach { file ->
                            zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                        zip.putNextEntry(ZipEntry("export-health.json"))
                        zip.write(JSONObject(mapOf("droppedRecordsTotal" to dropped.get(), "storageError" to storageError.value)).toString().toByteArray())
                        zip.closeEntry()
                    }
                    target
                })
            }
        } catch (e: Exception) { done(Result.failure(e)) }
    }
}
