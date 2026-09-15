package dev.foldprobe.fold

import android.os.SystemClock
import dev.foldprobe.debug.EventLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** Foreground-only diagnostic source. The USB receiver binds only to loopback. */
class DiagnosticAngleSource(context: android.content.Context, private val scope: CoroutineScope, private val log: EventLog) {
    private val mutable = MutableStateFlow(DiagnosticReading())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val shizuku = ShizukuAngleClient(context, scope, log, ::publish) { message ->
        mutable.value = mutable.value.copy(live = false, source = message)
    }
    val shizukuAction = shizuku.action
    fun requestShizuku() = shizuku.requestOrConnect()
    val earlyDisplayStatus = shizuku.earlyStatus
    val canRequestEarlyDisplay get() = shizuku.canRequestEarlyDisplay
    suspend fun renewForegroundLease() = shizuku.renewForegroundLease()
    fun automaticDisplay(steadyTrial: Boolean = false) =
        shizuku.setEarlyDisplay(true, automatic = true, cycle = true, steadyTrial = steadyTrial)
    fun earlyDisplay(enabled: Boolean, outerFirst: Boolean = false, automatic: Boolean = false): Job =
        shizuku.setEarlyDisplay(enabled, outerFirst, automatic)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    fun start(token: String?) {
        stop()
        job = scope.launch {
            launch {
                while (isActive) {
                    delay(200)
                    val reading = mutable.value
                    if (reading.live && SystemClock.elapsedRealtime() - reading.receivedMs > 1000) {
                        mutable.compareAndSet(reading, reading.copy(live = false, source = "Signalen pausad · senaste vinkeln visas"))
                        log.record("referenceAngle", "stale")
                    }
                }
            }
            launch(Dispatchers.IO) reader@{
                val first = runCatching { readDirect() }
                if (!isActive) return@reader
                if (first.isSuccess) {
                    log.record("referenceAngle", "directAccess", mapOf("success" to true))
                    publish(first.getOrThrow().first, "Direkt från telefonen", first.getOrThrow().second)
                    while (isActive) {
                        delay(60)
                        try {
                            val (raw, elapsed) = readDirect()
                            if (isActive) publish(raw, "Direkt från telefonen", elapsed)
                        } catch (e: Exception) {
                            if (!isActive) return@reader
                            mutable.value = mutable.value.copy(live = false, source = "Direktläsningen avbröts")
                            log.failure("referenceAngle", "directRead", e)
                            break
                        }
                    }
                } else {
                    if (!isActive) return@reader
                    log.failure("referenceAngle", "directAccessDenied", first.exceptionOrNull()!!)
                    if (token != null && token.matches(Regex("[a-f0-9]{64}"))) {
                        mutable.value = DiagnosticReading(source = "Anslut USB-bryggan")
                        serve(token)
                    } else withContext(Dispatchers.Main) { shizuku.start() }
                }
            }
        }
    }

    private fun readDirect(): Pair<String, Long> {
        val began = SystemClock.elapsedRealtime()
        val raw = File(ReferenceAngle.PATH).bufferedReader().use { it.readLine() ?: "" }
        require(ReferenceAngle.parse(raw) != null) { "Invalid reference-angle record" }
        return raw to (SystemClock.elapsedRealtime() - began)
    }

    private fun publish(raw: String, source: String, readMs: Long) {
        val angle = ReferenceAngle.parse(raw) ?: return
        mutable.value = DiagnosticReading(angle, source, SystemClock.elapsedRealtime(), readMs, true)
        log.record("referenceAngle", "sample", mapOf("angle" to angle, "raw" to raw,
            "transport" to source, "readMs" to readMs), sample = true)
    }

    private suspend fun serve(token: String) {
        val socket = ServerSocket()
        server = socket
        try {
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
            socket.soTimeout = 1000
            log.record("referenceAngle", "usbListening", mapOf("port" to PORT))
            val expires = SystemClock.elapsedRealtime() + 600_000
            while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < expires) {
                val connection = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
                client = connection
                connection.use {
                    try {
                        it.soTimeout = 1500
                        it.tcpNoDelay = true
                        val input = it.getInputStream()
                        if (line(input) != token) return@use
                        it.getOutputStream().write("OK\n".toByteArray(Charsets.US_ASCII))
                        it.getOutputStream().flush()
                        var lastSequence = -1L
                        while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < expires) {
                            val parts = (line(input) ?: break).split('|')
                            if (parts.size != 3) break
                            val sequence = parts[0].toLongOrNull() ?: break
                            val readMs = parts[2].toLongOrNull() ?: break
                            if (sequence <= lastSequence || readMs !in 0..3000 || ReferenceAngle.parse(parts[1]) == null) break
                            lastSequence = sequence
                            publish(parts[1], "Via USB · datorn behövs", readMs)
                        }
                    } catch (e: Exception) {
                        if (currentCoroutineContext().isActive) log.record("referenceAngle", "usbDisconnected",
                            mapOf("reason" to e.javaClass.simpleName))
                    }
                }
                client = null
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) log.failure("referenceAngle", "usbServer", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** No unbounded readLine allocation on the socket, including before authentication. */
    private fun line(input: InputStream): String? {
        val bytes = StringBuilder()
        repeat(128) {
            val next = input.read()
            if (next < 0) return null
            if (next == 10) return bytes.toString()
            require(next in 32..126) { "Non-ASCII bridge frame" }
            bytes.append(next.toChar())
        }
        throw IllegalArgumentException("Bridge frame too long")
    }

    fun stop() {
        shizuku.stop()
        job?.cancel(); job = null
        runCatching { client?.close() }; client = null
        runCatching { server?.close() }; server = null
        mutable.value = mutable.value.copy(live = false, source = "Vinkelmätningen är pausad")
    }

    companion object { const val PORT = 39768 }
}
