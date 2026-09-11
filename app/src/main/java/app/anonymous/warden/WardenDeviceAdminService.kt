package app.anonymous.warden

import android.app.admin.DeviceAdminService
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import app.anonymous.warden.dpm.applyStrictRestrictions
import app.anonymous.warden.dpm.enforceManagedSettings
import app.anonymous.warden.dpm.enforceNightLight
import app.anonymous.warden.dpm.managedSettingUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WardenDeviceAdminService : DeviceAdminService() {

    private val packageReceiver = PackageChangeReceiver()
    private var isReceiverRegistered = false
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private val policyObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            serviceScope.launch { enforceDaemonState() }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
            priority = 999
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        isReceiverRegistered = true

        runCatching {
            val cr = contentResolver
            val fixed = listOf(
                Settings.Global.getUriFor("low_power"),
                Settings.Global.getUriFor("private_dns_mode"),
                Settings.Global.getUriFor("private_dns_specifier"),
                Settings.System.getUriFor("system_locales"),
                Settings.Secure.getUriFor("night_display_auto_mode"),
                Settings.Secure.getUriFor("night_display_color_temperature"),
                Settings.Secure.getUriFor("night_display_custom_start_time"),
                Settings.Secure.getUriFor("night_display_custom_end_time"),
                Settings.Secure.getUriFor("night_display_activated"),
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED)
            )
            (fixed + managedSettingUris()).distinct()
                .forEach { uri -> cr.registerContentObserver(uri, false, policyObserver) }

            Log.d("AdminService", "Service bound; observing managed settings.")
        }.onFailure { Log.e("AdminService", "Failed to bind ContentObservers", it) }
    }

    private fun enforceDaemonState() {
        val cr = contentResolver

        runCatching {
            if (Settings.Global.getInt(cr, "low_power", 0) == 0) {
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                val isUnplugged = plugged == 0 || plugged == -1

                if (isUnplugged) {
                    Settings.Global.putInt(cr, "low_power", 1)
                    Settings.Global.putInt(cr, "low_power_sticky", 1)
                    Settings.Global.putInt(cr, "low_power_sticky_auto_disable_enabled", 0)
                    Settings.Global.putInt(cr, "automatic_power_save_mode", 0)
                    Settings.Global.putInt(cr, "low_power_trigger_level", 100)
                }
            }
        }.onFailure { Log.e("AdminService", "Battery Saver write failed", it) }

        enforceManagedSettings(this).onFailure { Log.e("AdminService", "Managed settings write failed", it) }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mode = Settings.Global.getString(cr, "private_dns_mode")
                val specifier = Settings.Global.getString(cr, "private_dns_specifier")
                if (mode != "hostname" || specifier != BuildConfig.PRIVATE_DNS) {
                    Settings.Global.putString(cr, "private_dns_mode", "hostname")
                    Settings.Global.putString(cr, "private_dns_specifier", BuildConfig.PRIVATE_DNS)
                    Privilege.DPM.setGlobalSetting(Privilege.DAR, "private_dns_mode", "hostname")
                    Privilege.DPM.setGlobalSetting(Privilege.DAR, "private_dns_specifier", BuildConfig.PRIVATE_DNS)
                }
            }
        }.onFailure { Log.e("AdminService", "DNS write failed", it) }

        enforceNightLight(this).onFailure { Log.e("AdminService", "Night light write failed", it) }

        if (SP.hasRebootedSinceSetup) {
            runCatching {
                if (Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0) {
                    Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
                    Log.d("AdminService", "Developer Options re-disabled.")
                }
                if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 0) {
                    Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0)
                    Log.d("AdminService", "ADB re-disabled.")
                }
            }.onFailure { Log.e("AdminService", "Failed to disable Developer Options", it) }
        }

        applyStrictRestrictions(this)
    }

    override fun onDestroy() {
        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(packageReceiver) }
            isReceiverRegistered = false
        }
        runCatching { contentResolver.unregisterContentObserver(policyObserver) }
        super.onDestroy()
    }
}
