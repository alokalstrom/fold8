package dev.foldprobe.fold

/** Read-only physical base state, from the non-root shell UserService only. */
class PhysicalDeviceStateReader {
    private val api by lazy { Class.forName("android.hardware.devicestate.IDeviceStateManager") }
    private val service by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "device_state")
        checkNotNull(binder) { "Device state service unavailable" }
        Class.forName("android.hardware.devicestate.IDeviceStateManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
    }
    private val readInfo by lazy { api.getMethod("getDeviceStateInfo") }
    private val baseField by lazy { Class.forName("android.hardware.devicestate.DeviceStateInfo").getField("baseState") }
    private val identifier by lazy { Class.forName("android.hardware.devicestate.DeviceState").getMethod("getIdentifier") }

    fun readBaseId(): Int {
        val info = readInfo.invoke(service)
        return identifier.invoke(baseField.get(info)) as Int
    }
}
