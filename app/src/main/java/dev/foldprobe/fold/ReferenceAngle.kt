package dev.foldprobe.fold

/** Validates the diagnostic record; secondary -1 fields are normal on this device. */
object ReferenceAngle {
    const val PATH = "/sys/devices/virtual/sensors/sub_accelerometer_sensor/read_angle_data"
    fun parse(raw: String): Float? {
        val parts = raw.trim().split(',')
        if (parts.size != 9) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        return values[0].takeIf { it in 0..180 }?.toFloat()
    }
}

data class DiagnosticReading(
    val angle: Float? = null,
    val source: String = "Väntar på vinkelmätning",
    val receivedMs: Long = 0,
    val readMs: Long = 0,
    val live: Boolean = false
)
