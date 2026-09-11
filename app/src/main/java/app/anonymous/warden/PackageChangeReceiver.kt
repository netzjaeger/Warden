package app.anonymous.warden

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.anonymous.warden.dpm.enforceStrictWhitelist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class PackageChangeReceiver : BroadcastReceiver() {

    companion object {
        private val recentInstalls = ConcurrentHashMap<String, Long>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return

        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return

        if (!Privilege.status.value.device || !SP.protectInstall) return

        val isWhitelisted = AppWhitelist.isWhitelistedSync(pkg)

        if (!isWhitelisted) {
            runCatching {
                Privilege.DPM.setPackagesSuspended(Privilege.DAR, arrayOf(pkg), true)
                Log.d("PackageChangeReceiver", "Suspended a non-whitelisted package.")
            }
        }

        val now = System.currentTimeMillis()
        if (recentInstalls.size > 50) recentInstalls.clear()
        val alreadyHandledRecently = now - (recentInstalls[pkg] ?: 0L) < 2000L
        recentInstalls[pkg] = now
        if (alreadyHandledRecently) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pm = context.packageManager
                val info = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).applicationInfo
                    } else {
                        @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
                    }
                }.getOrNull() ?: return@launch

                if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                    if (!isWhitelisted) Privilege.DPM.setPackagesSuspended(Privilege.DAR, arrayOf(pkg), false)
                    return@launch
                }

                if (!isWhitelisted) {
                    enforceStrictWhitelist(context)
                } else {
                    Privilege.DPM.setPackagesSuspended(Privilege.DAR, arrayOf(pkg), false)
                }
            } catch (e: Exception) {
                runCatching { enforceStrictWhitelist(context) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
