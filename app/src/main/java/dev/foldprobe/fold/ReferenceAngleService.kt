package dev.foldprobe.fold

import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Instantiated by Shizuku as shell, never an Android manifest service. */
class ReferenceAngleService : IReferenceAngleService.Stub() {
    private val reader = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val early = EarlyDisplayRequest()
    @Volatile private var lastRequest = SystemClock.elapsedRealtime()

    init {
        check(Process.myUid() == 2000) { "Only non-root Shizuku is supported" }
        // Also bounds lifetime if the client disappears without unbinding.
        watchdog.scheduleWithFixedDelay({
            early.tick()
            if (SystemClock.elapsedRealtime() - lastRequest > 30_000) destroy()
        }, 200, 200, TimeUnit.MILLISECONDS)
    }

    override fun getServiceUid() = Process.myUid()

    @Synchronized override fun readAngle(): String {
        lastRequest = SystemClock.elapsedRealtime()
        val task = reader.submit<String> {
            File(ReferenceAngle.PATH).bufferedReader().use { it.readLine() ?: "" }.also {
                require(ReferenceAngle.parse(it) != null) { "Invalid angle record" }
            }
        }
        return try {
            task.get(1000, TimeUnit.MILLISECONDS).also { early.onAngle(ReferenceAngle.parse(it)!!.toInt()) }
        } catch (e: Exception) {
            task.cancel(true)
            watchdog.schedule({ destroy() }, 100, TimeUnit.MILLISECONDS)
            throw IllegalStateException("Angle read failed", e)
        }
    }

    override fun destroy() {
        early.close()
        reader.shutdownNow()
        watchdog.shutdownNow()
        exitProcess(0)
    }
    override fun armEarlyDisplay() { early.arm(ReferenceAngle.parse(readAngle())!!.toInt()) }
    override fun armOuterFirstDisplay() { early.arm(ReferenceAngle.parse(readAngle())!!.toInt(), outerFirst = true) }
    override fun armAutomaticDisplay() { early.armAutomatic(ReferenceAngle.parse(readAngle())!!.toInt()) }
    override fun armSteadyDisplayTrial() { early.armAutomatic(ReferenceAngle.parse(readAngle())!!.toInt(), keepOpen = true) }
    override fun getEarlyDisplayStatus() = early.status()
    override fun cancelEarlyDisplay() { early.close() }
    override fun renewForegroundLease() { early.renewForegroundLease() }
}
