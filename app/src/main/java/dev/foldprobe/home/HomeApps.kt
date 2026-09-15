package dev.foldprobe.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.Collator

data class HomeApp(val component: String, val label: String, val icon: ImageBitmap?)
data class HomeAppsState(val apps: List<HomeApp> = emptyList(),
    val slots: List<String?> = List(HomeLayout.SIZE) { null }, val loaded: Boolean = false) {
    fun appAt(index: Int) = apps.firstOrNull { it.component == slots.getOrNull(index) }
}

/** Personal-profile launcher activities only. Catalog and layout never leave the device. */
class HomeApps(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("home_layout", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(HomeAppsState())
    val state = mutable.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pm = context.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val collator = Collator.getInstance()
            val apps = pm.queryIntentActivities(query, 0).mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                if (!info.exported || !info.enabled || !info.applicationInfo.enabled || info.packageName == context.packageName) return@mapNotNull null
                HomeApp(ComponentName(info.packageName, info.name).flattenToString(),
                    result.loadLabel(pm).toString(),
                    runCatching { result.loadIcon(pm).toBitmap(160, 160).asImageBitmap() }.getOrNull())
            }.distinctBy { it.component }.sortedWith { a, b ->
                collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.component.compareTo(b.component)
            }
            val stored = preferences.getString("slots_v1", null)
            val slots = if (stored == null) initialSlots(apps) else {
                val array = JSONArray(stored)
                HomeLayout.normalize(List(array.length()) { i -> if (array.isNull(i)) null else array.getString(i) })
            }
            if (stored == null) save(slots)
            mutable.value = HomeAppsState(apps, slots, loaded = true)
        }
    }

    suspend fun assign(index: Int, component: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(component == null || mutable.value.apps.any { it.component == component })
            val slots = HomeLayout.assign(mutable.value.slots, index, component)
            save(slots)
            mutable.value = mutable.value.copy(slots = slots)
        }
    }

    private fun save(slots: List<String?>) {
        val array = JSONArray(); slots.forEach { array.put(it ?: org.json.JSONObject.NULL) }
        check(preferences.edit().putString("slots_v1", array.toString()).commit()) { "Could not save home layout" }
    }

    private fun initialSlots(apps: List<HomeApp>): List<String?> {
        val chosen = mutableSetOf<String>()
        fun takePackage(vararg packages: String): String? = packages.firstNotNullOfOrNull { pkg ->
            apps.firstOrNull { ComponentName.unflattenFromString(it.component)?.packageName == pkg && it.component !in chosen }
        }?.component?.also { chosen.add(it) }
        val dock = listOf(
            takePackage("com.samsung.android.dialer", "com.google.android.dialer"),
            takePackage("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            takePackage("com.android.chrome", "com.sec.android.app.sbrowser"),
            takePackage("com.sec.android.app.camera", "com.google.android.GoogleCamera"))
        val preferred = listOf("com.sec.android.gallery3d", "com.samsung.android.calendar", "com.android.settings",
            "com.sec.android.app.clockpackage", "com.google.android.apps.maps", "com.google.android.gm",
            "com.sec.android.app.myfiles", "com.sec.android.app.popupcalculator", "com.google.android.youtube",
            "com.spotify.music", "com.samsung.android.app.notes", "moe.shizuku.privileged.api")
        val grid = preferred.mapNotNull { takePackage(it) }.toMutableList()
        apps.filter { it.component !in chosen }.take(16 - grid.size).forEach { grid.add(it.component) }
        return List(16) { grid.getOrNull(it) } + dock
    }
}
