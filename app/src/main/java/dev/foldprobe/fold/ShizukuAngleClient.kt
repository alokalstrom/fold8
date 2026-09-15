package dev.foldprobe.fold

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import dev.foldprobe.BuildConfig
import dev.foldprobe.debug.EventLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

class ShizukuAngleClient(
    context: Context, private val scope: CoroutineScope, private val log: EventLog,
    private val publish: (String, String, Long) -> Unit,
    private val status: (String) -> Unit
) {
    private val mutable = MutableStateFlow("Öppna Shizuku")
    val action = mutable.asStateFlow()
    private val earlyMutable = MutableStateFlow("Av")
    val earlyStatus = earlyMutable.asStateFlow()
    @Volatile private var remote: IReferenceAngleService? = null
    val canRequestEarlyDisplay get() = active && remote != null
    private val commandMutex = Mutex()
    private val owner = "${android.os.Process.myPid()}-${NEXT_OWNER.incrementAndGet()}"
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ReferenceAngleService::class.java))
        // Activity instances can overlap during recreation; one must not destroy the other's reader.
        .tag("reference-angle-$owner").version(BuildConfig.VERSION_CODE).daemon(false)
        .processNameSuffix("angle_reader_$owner").debuggable(BuildConfig.DEBUG)
    private var active = false
    private var bound = false
    private var polling: Job? = null
    private var bindingTimeout: Job? = null
    private val received = Shizuku.OnBinderReceivedListener { connect() }
    private val dead = Shizuku.OnBinderDeadListener {
        disconnect(); status("Shizuku har stannat · öppna Shizuku"); mutable.value = "Öppna Shizuku"
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST && active) {
            log.record("shizuku", "permission", mapOf("granted" to (result == PackageManager.PERMISSION_GRANTED)))
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else { status("Shizuku-behörighet saknas"); mutable.value = "Tillåt Shizuku" }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!active || !bound) { disconnect(); return }
            bindingTimeout?.cancel()
            polling?.cancel()
            val service = IReferenceAngleService.Stub.asInterface(binder)
            polling = scope.launch(Dispatchers.IO) {
                try {
                    val uid = service.serviceUid
                    check(uid == 2000) { "Unexpected service UID: $uid" }
                    remote = service
                    log.record("shizuku", "connected", mapOf("uid" to uid))
                    mutable.value = "Shizuku ansluten"
                    while (isActive) {
                        val began = SystemClock.elapsedRealtime()
                        val raw = service.readAngle()
                        ensureActive()
                        require(ReferenceAngle.parse(raw) != null) { "Invalid angle record" }
                        publish(raw, "På telefonen · Shizuku", SystemClock.elapsedRealtime() - began)
                        val early = service.earlyDisplayStatus
                        if (early != earlyMutable.value) {
                            earlyMutable.value = early
                            log.record("earlyDisplay", "status", mapOf("status" to early,
                                "angle" to ReferenceAngle.parse(raw)))
                        }
                        delay(80)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (active) {
                            log.failure("shizuku", "readFailed", e)
                            disconnect(); status("Vinkelläsningen avbröts · anslut igen")
                            mutable.value = "Anslut Shizuku"
                        }
                    }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remote = null; earlyMutable.value = "Av · tjänsten är frånkopplad"
            bindingTimeout?.cancel(); bindingTimeout = null
            polling?.cancel(); polling = null
            bound = false
            if (active) { status("Vinkeltjänsten stannade · anslut igen"); mutable.value = "Anslut Shizuku" }
        }
    }

    fun start() {
        if (active) return
        active = true
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(received)
        if (!Shizuku.pingBinder()) status("Starta Shizuku för vinkel utan dator")
    }

    private fun connect() {
        if (!active || bound) return
        try {
            if (!Shizuku.pingBinder()) { status("Starta Shizuku för vinkel utan dator"); mutable.value = "Öppna Shizuku"; return }
            if (Shizuku.isPreV11() || Shizuku.getUid() != 2000) {
                status("Starta Shizuku via felsökning utan root"); mutable.value = "Öppna Shizuku"; return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                status("Tillåt Fold Probe att använda Shizuku"); mutable.value = "Tillåt Shizuku"; return
            }
            status("Ansluter vinkelläsning på telefonen…")
            bound = true
            Shizuku.bindUserService(args, connection)
            bindingTimeout = scope.launch {
                delay(10_000)
                disconnect(); status("Anslutningen tog för lång tid · försök igen"); mutable.value = "Anslut Shizuku"
            }
        } catch (e: Exception) {
            disconnect(); log.failure("shizuku", "connectFailed", e)
            status("Kunde inte ansluta Shizuku"); mutable.value = "Anslut Shizuku"
        }
    }

    /** Called only by the visible permission button. Never grants permission through ADB. */
    fun requestOrConnect(): Boolean {
        if (!Shizuku.pingBinder()) return false
        return try {
            if (Shizuku.isPreV11() || Shizuku.getUid() != 2000) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect()
            else if (Shizuku.shouldShowRequestPermissionRationale()) return false
            else Shizuku.requestPermission(REQUEST)
            true
        } catch (e: Exception) { log.failure("shizuku", "permissionRequestFailed", e); false }
    }

    private fun disconnect() {
        remote = null; earlyMutable.value = "Av"
        bindingTimeout?.cancel(); bindingTimeout = null
        polling?.cancel(); polling = null
        val wasBound = bound
        bound = false
        if (wasBound) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    fun setEarlyDisplay(enabled: Boolean, outerFirst: Boolean = false, automatic: Boolean = false,
        cycle: Boolean = false, steadyTrial: Boolean = false): Job {
        val expectedService = remote
        return scope.launch {
          commandMutex.withLock {
           withContext(Dispatchers.IO) {
            // Commands queued before a lifecycle disconnect must not reach a new service.
            if (!active || remote !== expectedService) return@withContext
            val service = expectedService
            if (service == null) { earlyMutable.value = "Anslut Shizuku först"; return@withContext }
            try {
                if (!enabled) service.cancelEarlyDisplay()
                else if (steadyTrial) service.armSteadyDisplayTrial()
                else if (cycle) service.armAutomaticDisplay()
                else if (outerFirst) service.armOuterFirstDisplay() else service.armEarlyDisplay()
                log.record("earlyDisplay", if (automatic) {
                    if (enabled) "armedAutomatically" else "cancelledAutomatically"
                } else if (enabled) "armedByButton" else "cancelledByButton",
                    mapOf("outerFirst" to outerFirst, "requestFlags" to 0, "steadyTrial" to steadyTrial))
            } catch (e: Exception) {
                earlyMutable.value = "Kunde inte starta · håll vinkeln under 75°"
                log.failure("earlyDisplay", "commandFailed", e)
            }
           }
          }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        disconnect()
        log.record("shizuku", "stopped")
    }

    /** Caller checks foreground eligibility on the main thread; polling alone cannot renew. */
    suspend fun renewForegroundLease() {
        val service = remote ?: return
        withContext(Dispatchers.IO) {
            if (active && remote === service) runCatching { service.renewForegroundLease() }
                .onFailure { log.failure("earlyDisplay", "leaseRenewFailed", it) }
        }
    }

    companion object {
        private const val REQUEST = 7401
        private val NEXT_OWNER = java.util.concurrent.atomic.AtomicInteger()
    }
}
