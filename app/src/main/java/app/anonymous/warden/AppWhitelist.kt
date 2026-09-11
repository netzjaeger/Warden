package app.anonymous.warden

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object AppWhitelist {

    private val essentialApps: Set<String> = BuildConfig.APP_WHITELIST
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val cachedWhitelist = AtomicReference<Set<String>?>(null)
    private val mutex = Mutex()

    fun isWhitelistedSync(packageName: String): Boolean {
        cachedWhitelist.get()?.let { return it.contains(packageName) }
        val activeSet = SP.appWhitelist.orEmpty().split(",").filter { it.isNotEmpty() }.toSet() + essentialApps
        cachedWhitelist.compareAndSet(null, activeSet)
        return activeSet.contains(packageName)
    }

    suspend fun getWhitelist(): Set<String> = cachedWhitelist.get() ?: withContext(Dispatchers.IO) {
        mutex.withLock {
            cachedWhitelist.get() ?: (SP.appWhitelist.orEmpty().split(",").filter { it.isNotEmpty() }.toSet() + essentialApps).also {
                cachedWhitelist.set(it)
            }
        }
    }

    suspend fun setWhitelist(packages: Set<String>) = withContext(Dispatchers.IO) {
        val cleanSet = packages.filter { it.isNotEmpty() }.toSet()
        mutex.withLock {
            cachedWhitelist.set(cleanSet + essentialApps)
            SP.appWhitelist = cleanSet.joinToString(",")
        }
    }

    suspend fun addToWhitelist(packageName: String) = setWhitelist(getWhitelist() + packageName)

    suspend fun removeFromWhitelist(packageName: String) = setWhitelist(getWhitelist() - packageName)

    suspend fun getInstalledUserApps(context: Context): List<ApplicationInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES

        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledApplications(flags)
        }

        apps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
    }

    fun initializeDefaultWhitelist() {
        if (SP.appWhitelist == null) SP.appWhitelist = ""
    }
}
