package dev.foldprobe.fold

import android.os.SystemClock
import java.util.Timer
import java.util.TimerTask
import kotlin.system.exitProcess

/** Runs only inside the shell UserService. Never overrides the physical base state. */
class EarlyDisplayRequest {
    private val policy = EarlyDisplayPolicy()
    private var cyclePolicy = AutomaticDisplayPolicy()
    private var automaticCycle = false
    private var foregroundLeased = false
    private val foregroundLease = ForegroundDisplayLease()
    private var manager: Any? = null
    @Volatile private var requestOwned = false
    private var hardDeadline: Timer? = null
    private var lastAngle: Int? = null
    private var lastSample = 0L
    private var error: String? = null
    private var stateId = 4
    private val physicalState = PhysicalDeviceStateReader()
    private var reportedBaseReadFailure = false

    @Synchronized fun armAutomatic(angle: Int, keepOpen: Boolean = false) {
        close(); automaticCycle = true; error = null
        foregroundLeased = keepOpen
        cyclePolicy = AutomaticDisplayPolicy(keepOpen, initialOpenEntry = keepOpen, foregroundLeased = keepOpen)
        reportedBaseReadFailure = false
        onAngle(angle)
    }

    @Synchronized fun arm(angle: Int, outerFirst: Boolean = false) {
        require(angle in 0..75) { "Stäng till mindre än 75° först" }
        close()
        stateId = if (outerFirst) 5 else 4
        error = null
        reportedBaseReadFailure = false
        policy.arm(SystemClock.elapsedRealtime(), keepOpen = outerFirst)
        onAngle(angle)
    }
    @Synchronized fun onAngle(angle: Int) {
        lastAngle = angle; lastSample = SystemClock.elapsedRealtime()
        updatePolicy(angle, lastSample)
    }
    @Synchronized fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (foregroundLeased && requestOwned && !foregroundLease.valid(now)) {
            close()
            return
        }
        updatePolicy(lastAngle.takeIf { now - lastSample <= 1000 }, now)
    }
    @Synchronized fun renewForegroundLease() {
        if (automaticCycle && foregroundLeased && requestOwned) foregroundLease.renew(SystemClock.elapsedRealtime())
    }
    private fun updatePolicy(angle: Int?, now: Long) {
        val base = if ((automaticCycle && cyclePolicy.active || stateId == 5 && policy.phase == EarlyDisplayPolicy.Phase.REQUESTED) &&
            angle != null && angle <= 5) {
            try { physicalState.readBaseId() }
            catch (e: Exception) {
                if (!reportedBaseReadFailure) android.util.Log.w("FoldEarlyDisplay", "baseReadFailed; using angle release", e)
                reportedBaseReadFailure = true
                null
            }
        } else null
        if (automaticCycle) {
            val action = cyclePolicy.update(angle, now, base)
            when (action) {
                AutomaticDisplayPolicy.Action.NONE -> Unit
                AutomaticDisplayPolicy.Action.REQUEST_OUTER_FIRST, AutomaticDisplayPolicy.Action.REQUEST_INNER_FIRST -> {
                    stateId = if (action == AutomaticDisplayPolicy.Action.REQUEST_OUTER_FIRST) 5 else 4
                    android.util.Log.i("FoldEarlyDisplay", "automatic request state=$stateId angle=$angle")
                    apply(EarlyDisplayPolicy.Action.REQUEST)
                }
                AutomaticDisplayPolicy.Action.RELEASE -> {
                    android.util.Log.i("FoldEarlyDisplay", "automatic release state=$stateId angle=$angle base=$base")
                    apply(EarlyDisplayPolicy.Action.RELEASE)
                }
            }
            return
        }
        val action = policy.update(angle, now, base)
        if (action == EarlyDisplayPolicy.Action.RELEASE) android.util.Log.i("FoldEarlyDisplay",
            "release state=$stateId angle=$angle base=$base baseReadFailed=$reportedBaseReadFailure")
        apply(action)
    }
    @Synchronized fun status(): String = error ?: if (automaticCycle) {
        if (cyclePolicy.active) "Tidig skärmstart begärd · läge $stateId · " +
            if (foregroundLeased) "så länge hemskärmen är aktiv" else "högst 45 sekunder"
        else "Automatik redo · ${cyclePolicy.phase}"
    } else when (policy.phase) {
        EarlyDisplayPolicy.Phase.OFF -> "Av"
        EarlyDisplayPolicy.Phase.ARMED -> "Redo · öppna långsamt inom två minuter"
        EarlyDisplayPolicy.Phase.REQUESTED -> "Tidig skärmstart begärd · läge $stateId · högst 45 sekunder"
        EarlyDisplayPolicy.Phase.FINISHED -> "Test avslutat · normalt skärmläge"
    }
    private fun apply(action: EarlyDisplayPolicy.Action) {
        try {
            when (action) {
                EarlyDisplayPolicy.Action.NONE -> Unit
                EarlyDisplayPolicy.Action.RELEASE -> release()
                EarlyDisplayPolicy.Action.REQUEST -> {
                    check(android.os.Build.MODEL == "SM-F971B") { "Display mode is verified only on SM-F971B" }
                    val type = Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal")
                    val client = type.getMethod("getInstance").invoke(null).also { manager = it }
                    val requestType = Class.forName("android.hardware.devicestate.DeviceStateRequest")
                    val builder = requestType.getMethod("newBuilder", Int::class.javaPrimitiveType).invoke(null, stateId)
                    // SM-F971B firmware dereferences a cancelled request when flag 1 and
                    // base OPENED coincide. Keep flags clear; our policy/watchdogs own release.
                    // Exact firmware control flow and crash evidence: SHARED_SCENE_VALIDATION.md.
                    builder.javaClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, 0)
                    val request = builder.javaClass.getMethod("build").invoke(builder)
                    val callback = Class.forName("android.hardware.devicestate.DeviceStateRequest\$Callback")
                    // This timer does not take the controller lock, so even blocked Binder
                    // request/cancel calls cannot keep the display request alive indefinitely.
                    if (hardDeadline == null) hardDeadline = Timer("early-display-deadline", true).apply {
                        if (foregroundLeased) {
                            foregroundLease.begin(SystemClock.elapsedRealtime())
                            schedule(object : TimerTask() {
                                override fun run() {
                                    if (requestOwned && !foregroundLease.valid(SystemClock.elapsedRealtime())) exitProcess(2)
                                }
                            }, 200, 200)
                        } else schedule(object : TimerTask() { override fun run() { exitProcess(2) } }, 46_500)
                    }
                    requestOwned = true
                    type.getMethod("requestState", requestType, java.util.concurrent.Executor::class.java, callback)
                        .invoke(client, request, null, null)
                }
            }
        } catch (e: Exception) {
            automaticCycle = false; cyclePolicy.reset()
            policy.cancel()
            error = "Tidig start misslyckades · ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}"
            release()
        }
    }
    private fun release() {
        if (requestOwned) {
            try { manager!!.javaClass.getMethod("cancelStateRequest").invoke(manager) }
            catch (_: Exception) { exitProcess(2) } // Binder death releases our request.
            requestOwned = false
        }
        hardDeadline?.cancel(); hardDeadline = null
        foregroundLease.clear()
    }
    @Synchronized fun close() { automaticCycle = false; cyclePolicy.reset(); policy.cancel(); release(); foregroundLeased = false }
}
