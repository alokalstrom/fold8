package dev.foldprobe.fold

import android.content.Context
import dev.foldprobe.debug.EventLog
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Non-SDK, read-only experiment. No exemption, binder spoofing, or state override. */
class DeviceStateFoldProvider(private val context: Context, private val log: EventLog) {
    private var service: Any? = null
    private var callback: Any? = null
    private var callbackClass: Class<*>? = null
    var summary = "Not in public SDK 36. Optional non-SDK probe is off."; private set
    fun start() {
        if (callback != null) return
        try {
            val type = Class.forName("android.hardware.devicestate.DeviceStateManager")
            val cb = Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback")
            val manager = context.getSystemService(type) ?: error("Device-state service unavailable")
            log.record("nonSdkDeviceState", "surface", mapOf("methods" to type.methods.map { it.toGenericString() }, "callbackMethods" to cb.methods.map { it.toGenericString() }))
            val listener = Proxy.newProxyInstance(cb.classLoader, arrayOf(cb)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "FoldProbeDeviceStateCallback"
                    else -> {
                        val values = args?.map { if (it is IntArray) it.toList().toString() else it.toString() }
                        summary = "${method.name}: $values"
                        log.record("nonSdkDeviceState", method.name, mapOf("arguments" to values,
                            "meaning" to "Raw OEM state. No numeric ID assumed to mean folded."))
                        null
                    }
                }
            }
            type.getMethod("registerCallback", Executor::class.java, cb).invoke(manager, context.mainExecutor, listener)
            service = manager; callbackClass = cb; callback = listener
            log.record("nonSdkDeviceState", "registered")
            summary = "Non-SDK callback registered; waiting for state"
        } catch (e: Exception) { summary = "Blocked: $e"; log.failure("nonSdkDeviceState", "registration", e) }
    }
    fun stop() {
        val listener = callback ?: return
        try { service!!.javaClass.getMethod("unregisterCallback", callbackClass!!).invoke(service, listener) }
        catch (e: Exception) { log.failure("nonSdkDeviceState", "unregister", e) }
        callback = null; service = null
    }
}
