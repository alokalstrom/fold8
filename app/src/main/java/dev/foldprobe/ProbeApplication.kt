package dev.foldprobe

import android.app.Application
import android.os.Build
import dev.foldprobe.debug.EventLog
import dev.foldprobe.fold.FoldStateRepository
import dev.foldprobe.fold.SamsungSensorFoldProvider

class ProbeApplication : Application() {
    lateinit var log: EventLog; private set
    lateinit var folds: FoldStateRepository; private set
    val sensors by lazy { SamsungSensorFoldProvider(this, log, folds) }
    private val visibleOwners = mutableSetOf<Any>()

    /** Activities overlap during navigation; the departing one must not stop its successor's sensors. */
    fun startMonitoring(owner: Any) {
        if (visibleOwners.add(owner) && visibleOwners.size == 1) {
            folds.setForeground(true)
            sensors.start()
        }
    }

    fun stopMonitoring(owner: Any) {
        if (visibleOwners.remove(owner) && visibleOwners.isEmpty()) {
            sensors.stopAfterDisplaySwitchGrace()
            folds.setForeground(false)
        }
    }
    override fun onCreate() {
        super.onCreate()
        log = EventLog(this)
        folds = FoldStateRepository(log)
        log.record("app", "start", mapOf("version" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE,
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "device" to Build.DEVICE,
            "sdk" to Build.VERSION.SDK_INT, "release" to Build.VERSION.RELEASE, "buildDisplay" to Build.DISPLAY,
            "fingerprint" to Build.FINGERPRINT, "securityPatch" to Build.VERSION.SECURITY_PATCH))
    }
}
