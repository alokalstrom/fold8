package dev.foldprobe.fold

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.foldprobe.debug.EventLog
import java.util.Locale

class SamsungSensorFoldProvider(context: Context, private val log: EventLog, private val folds: FoldStateRepository) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    val sensors: List<Sensor> = manager.getSensorList(Sensor.TYPE_ALL)
    private val registered = mutableSetOf<Sensor>()
    private val triggers = mutableMapOf<Sensor, TriggerEventListener>()
    private val stats = mutableMapOf<Sensor, SampleStats>()
    private val latest = mutableMapOf<Sensor, FloatArray>()
    private val statuses = mutableMapOf<Sensor, String>()
    private var startedNs = 0L
    private var lastStandard: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val delayedStop = Runnable { stop() }
    private val manuallySelected = mutableSetOf<Sensor>()

    // 65686/65695 are research leads from SM-F966N, not asserted Fold 8 identifiers.
    fun relevant(sensor: Sensor): Boolean = sensor.type in listOf(Sensor.TYPE_HINGE_ANGLE, 65686, 65695) ||
        Regex("fold|hinge|hall|posture|display|flip|lid", RegexOption.IGNORE_CASE).containsMatchIn("${sensor.name} ${sensor.stringType}")

    init {
        log.record("sensors", "inventory", mapOf("count" to sensors.size))
        sensors.forEachIndexed { index, s ->
            log.record("sensors", "descriptor", mapOf("index" to index, "id" to s.id, "name" to s.name,
                "vendor" to s.vendor, "version" to s.version, "type" to s.type, "stringType" to s.stringType,
                "reportingMode" to s.reportingMode, "wakeUp" to s.isWakeUpSensor, "resolution" to s.resolution,
                "minDelayUs" to s.minDelay, "maxDelayUs" to s.maxDelay, "maximumRange" to s.maximumRange,
                "powerMa" to s.power, "fifoMax" to s.fifoMaxEventCount, "relevant" to relevant(s),
                "requiredPermission" to "Not in public Sensor SDK; see opt-in metadata probe or adb dumpsys sensorservice"))
        }
        log.record("sensors", "standardHinge", mapOf("default" to manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.toString()))
    }

    fun start() {
        handler.removeCallbacks(delayedStop)
        if (active) stop()
        active = true; startedNs = SystemClock.elapsedRealtimeNanos(); lastStandard = null
        sensors.filter { relevant(it) || it in manuallySelected }.forEach { register(it) }
    }
    fun stopAfterDisplaySwitchGrace() {
        log.record("sensors", "backgroundGrace", mapOf("durationMs" to 3000, "note" to "OS may suppress delivery in background"))
        handler.postDelayed(delayedStop, 3000)
    }
    fun stop() {
        active = false
        manager.unregisterListener(this)
        triggers.forEach { (s, listener) -> manager.cancelTriggerSensor(listener, s) }
        triggers.clear(); registered.clear()
        log.record("sensors", "unregistered")
    }

    fun probe(sensor: Sensor) { manuallySelected.add(sensor); if (active) register(sensor) }

    private fun register(s: Sensor) {
        if (s in registered || s in triggers) return
        try {
            val success: Boolean
            val method: String
            if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
                val listener = object : TriggerEventListener() {
                    override fun onTrigger(event: TriggerEvent) {
                        handler.post {
                            accept(event.sensor, event.values.copyOf(), event.timestamp, null)
                            triggers.remove(s)
                            if (active) register(s)
                        }
                    }
                }
                method = "requestTriggerSensor"
                success = manager.requestTriggerSensor(listener, s)
                if (success) triggers[s] = listener
            } else {
                method = "registerListener"
                success = manager.registerListener(this, s, 10_000, 0, handler)
                if (success) registered.add(s)
            }
            statuses[s] = if (success) "registered; awaiting samples" else "registration returned false"
            log.record("sensors", "registration", mapOf("sensor" to s.toString(), "method" to method,
                "success" to success, "requestedPeriodUs" to 10_000, "batchLatencyUs" to 0))
        } catch (e: Exception) { statuses[s] = e.toString(); log.failure("sensors", "registration:${s.name}", e) }
    }

    override fun onSensorChanged(event: SensorEvent) = accept(event.sensor, event.values.copyOf(), event.timestamp, event.accuracy)
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { log.record("sensors", "accuracy", mapOf("sensor" to sensor.toString(), "accuracy" to accuracy)) }

    private fun accept(sensor: Sensor, values: FloatArray, timestamp: Long, accuracy: Int?) {
        if (!active) return
        latest[sensor] = values
        statuses[sensor] = "receiving"
        val count = stats.getOrPut(sensor) { SampleStats() }
        count.add(values.firstOrNull() ?: Float.NaN, timestamp)
        log.record("sensors", "sample", mapOf("id" to sensor.id, "name" to sensor.name, "type" to sensor.type,
            "stringType" to sensor.stringType, "values" to values.toList(), "sensorTimestampNs" to timestamp,
            "deliveryLatencyNs" to SystemClock.elapsedRealtimeNanos() - timestamp, "accuracy" to accuracy,
            "sampleIndex" to count.count, "deltaNs" to count.lastDeltaNs), sample = true)
        val raw = values.firstOrNull() ?: return
        if (sensor.type == Sensor.TYPE_HINGE_ANGLE && raw.isFinite() && raw in -1f..181f) {
            // Keep one physical source; do not alternate between wake-up and non-wake-up twins.
            if (lastStandard == null) lastStandard = sensor
            if (lastStandard == sensor) folds.onAngle(raw, AngleMath.normalize(raw)!!, "TYPE_HINGE_ANGLE: ${sensor.name}", false, count.distinctCount)
        }
        // Vendor values remain raw. A sensor's name alone does not establish units or semantics.
    }

    fun summary(): String = buildString {
        append("${sensors.size} exposed sensors • ${registered.size + triggers.size} registered\n")
        append("Requested 100 Hz; actual rate below. On-change rates include stationary holds.\n")
        sensors.filter { relevant(it) || it in manuallySelected }.forEach { s ->
            val st = stats[s]
            append("\n${s.name} [${s.type}]\n${s.stringType}\n${statuses[s] ?: "not registered"}\n")
            latest[s]?.let { append("raw = ${it.joinToString()}\n") }
            if (st != null) append(String.format(Locale.US, "n=%d  distinct(0.1)=%d  avg=%.1f Hz  Δ=%.1f ms  turns=%d\n",
                st.count, st.distinctCount, st.hz, st.lastDeltaNs / 1e6, st.reversals))
            if (st != null) append("Sample age: ${(SystemClock.elapsedRealtimeNanos() - st.lastNs) / 1_000_000} ms (quiet on-change sensors can still be valid)\n")
            else if (active) append("No sample after ${(SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000_000}s\n")
        }
    }

    fun descriptor(s: Sensor) = "${s.name}\nVendor: ${s.vendor}, version ${s.version}\nType: ${s.type} (${s.stringType})\n" +
        "Reporting mode: ${s.reportingMode}, wake-up: ${s.isWakeUpSensor}\nResolution: ${s.resolution}, range: ${s.maximumRange}\n" +
        "Min/max delay: ${s.minDelay}/${s.maxDelay} µs\nRequired permission: not exposed in public SDK\nRegistration and current readings are shown below."

    fun probeNonSdkPermissionMetadata() {
        sensors.forEach { s ->
            try {
                val permission = Sensor::class.java.getMethod("getRequiredPermission").invoke(s)
                log.record("nonSdkSensor", "permission", mapOf("sensor" to s.toString(), "permission" to permission))
            } catch (e: Exception) { log.failure("nonSdkSensor", "permission:${s.name}", e) }
        }
    }
}
