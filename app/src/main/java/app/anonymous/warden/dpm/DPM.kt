package app.anonymous.warden.dpm

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import app.anonymous.warden.AppWhitelist
import app.anonymous.warden.BuildConfig
import app.anonymous.warden.MainActivity
import app.anonymous.warden.Privilege
import app.anonymous.warden.Privilege.DAR
import app.anonymous.warden.Privilege.DPM
import app.anonymous.warden.SP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

private val BATTERY_SAVER_FLAGS = BuildConfig.BATTERY_SAVER_FLAGS

fun retrieveSecurityLogs() {
    CoroutineScope(Dispatchers.IO).launch { runCatching { DPM.retrieveSecurityLogs(DAR) } }
}

fun setDefaultAffiliationID() {
    if (SP.isDefaultAffiliationIdSet) return
    runCatching {
        DPM.setAffiliationIds(DAR, setOf("Warden_default_affiliation_id"))
        SP.isDefaultAffiliationIdSet = true
    }
}

fun handlePrivilegeChange(context: Context) {
    val activated = Privilege.status.value.activated
    SP.shortcuts = activated
    if (!activated) {
        SP.isDefaultAffiliationIdSet = false
        return
    }
    setDefaultAffiliationID()
    if (Privilege.status.value.device) CoroutineScope(Dispatchers.IO).launch { enforceAllPolicies(context) }
}

suspend fun enforceAllPolicies(context: Context) = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        applyChromiumPolicies()
        enforcePrivateDns(context)
    }

    runCatching { DPM.setUninstallBlocked(DAR, context.packageName, true) }

    enforceBatterySaverFlags(context)
    enforceNightLight(context)
    enforceManagedSettings(context)
    disableDeveloperOptions(context)
    applyStrictRestrictions(context)
    enforceStrictWhitelist(context)
}

private fun disableDeveloperOptions(context: Context) = runCatching {
    if (!SP.hasRebootedSinceSetup) return@runCatching
    val cr = context.contentResolver

    if (Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0) {
        Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
    }
    if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 0) {
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun enforcePrivateDns(context: Context) = runCatching {
    val cr = context.contentResolver
    val mode = Settings.Global.getString(cr, "private_dns_mode")
    val specifier = Settings.Global.getString(cr, "private_dns_specifier")

    if (mode == "hostname" && specifier == BuildConfig.PRIVATE_DNS) return@runCatching

    Settings.Global.putString(cr, "private_dns_mode", "hostname")
    Settings.Global.putString(cr, "private_dns_specifier", BuildConfig.PRIVATE_DNS)
    DPM.setGlobalSetting(DAR, "private_dns_mode", "hostname")
    DPM.setGlobalSetting(DAR, "private_dns_specifier", BuildConfig.PRIVATE_DNS)
}

private val NIGHT_LIGHT_START_MS = BuildConfig.NIGHT_LIGHT_START_MS.toIntOrNull()
private val NIGHT_LIGHT_END_MS = BuildConfig.NIGHT_LIGHT_END_MS.toIntOrNull()
private val NIGHT_LIGHT_TEMP = BuildConfig.NIGHT_LIGHT_TEMP.toIntOrNull()

private fun isWithinNightWindow(startMs: Int, endMs: Int): Boolean {
    val now = Calendar.getInstance()
    val nowMs = (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)) * 60_000
    return if (startMs <= endMs) nowMs in startMs until endMs else nowMs >= startMs || nowMs < endMs
}

internal fun enforceNightLight(context: Context) = runCatching {
    val startMs = NIGHT_LIGHT_START_MS ?: return@runCatching
    val endMs = NIGHT_LIGHT_END_MS ?: return@runCatching
    val temp = NIGHT_LIGHT_TEMP ?: return@runCatching
    val cr = context.contentResolver

    if (Settings.Secure.getInt(cr, "night_display_auto_mode", 0) != 1 ||
        Settings.Secure.getInt(cr, "night_display_custom_start_time", -1) != startMs ||
        Settings.Secure.getInt(cr, "night_display_custom_end_time", -1) != endMs ||
        Settings.Secure.getInt(cr, "night_display_color_temperature", -1) != temp) {

        Settings.Secure.putInt(cr, "night_display_auto_mode", 1)
        Settings.Secure.putInt(cr, "night_display_custom_start_time", startMs)
        Settings.Secure.putInt(cr, "night_display_custom_end_time", endMs)
        Settings.Secure.putInt(cr, "night_display_color_temperature", temp)
    }

    if (isWithinNightWindow(startMs, endMs) && Settings.Secure.getInt(cr, "night_display_activated", 0) == 0) {
        Settings.Secure.putInt(cr, "night_display_activated", 1)
    }
}

private data class ManagedSetting(val namespace: String, val key: String, val value: String) {
    fun read(cr: ContentResolver): String? = when (namespace) {
        "global" -> Settings.Global.getString(cr, key)
        "secure" -> Settings.Secure.getString(cr, key)
        else -> Settings.System.getString(cr, key)
    }

    fun write(cr: ContentResolver) {
        when (namespace) {
            "global" -> Settings.Global.putString(cr, key, value)
            "secure" -> Settings.Secure.putString(cr, key, value)
            else -> Settings.System.putString(cr, key, value)
        }
    }

    fun uri(): Uri? = runCatching {
        when (namespace) {
            "global" -> Settings.Global.getUriFor(key)
            "secure" -> Settings.Secure.getUriFor(key)
            else -> Settings.System.getUriFor(key)
        }
    }.getOrNull()

    fun isSatisfied(cr: ContentResolver): Boolean {
        val current = read(cr) ?: return false
        if (current == value) return true
        val a = current.toFloatOrNull() ?: return false
        val b = value.toFloatOrNull() ?: return false
        return a == b
    }
}

private val MANAGED_SETTINGS: List<ManagedSetting> = BuildConfig.MANAGED_SETTINGS
    .split(';')
    .mapNotNull { raw ->
        val entry = raw.trim()
        val equals = entry.indexOf('=')
        if (equals <= 0) return@mapNotNull null
        val target = entry.substring(0, equals).trim()
        val value = entry.substring(equals + 1).trim()
        val colon = target.indexOf(':')
        if (colon <= 0) return@mapNotNull null
        val namespace = target.substring(0, colon).trim().lowercase(Locale.ROOT)
        val key = target.substring(colon + 1).trim()
        if (key.isEmpty() || namespace !in setOf("global", "secure", "system")) return@mapNotNull null
        ManagedSetting(namespace, key, value)
    }

internal fun managedSettingUris(): List<Uri> = MANAGED_SETTINGS.mapNotNull { it.uri() }

internal fun enforceManagedSettings(context: Context) = runCatching {
    val cr = context.contentResolver
    MANAGED_SETTINGS.forEach { setting ->
        if (!setting.isSatisfied(cr)) runCatching { setting.write(cr) }
    }
}

private fun enforceBatterySaverFlags(context: Context) = runCatching {
    val cr = context.contentResolver
    if (Settings.Global.getString(cr, "battery_saver_constants") != BATTERY_SAVER_FLAGS) {
        Settings.Global.putString(cr, "battery_saver_constants", BATTERY_SAVER_FLAGS)
    }
    if (Settings.Global.getString(cr, "battery_saver_device_specific_constants") != BATTERY_SAVER_FLAGS) {
        Settings.Global.putString(cr, "battery_saver_device_specific_constants", BATTERY_SAVER_FLAGS)
    }
    if (Settings.Global.getInt(cr, "automatic_power_save_mode", -1) != 0) {
        Settings.Global.putInt(cr, "automatic_power_save_mode", 0)
    }
    if (Settings.Global.getInt(cr, "low_power_trigger_level", -1) != 100) {
        Settings.Global.putInt(cr, "low_power_trigger_level", 100)
    }
    if (Settings.Global.getInt(cr, "low_power", -1) != 1) {
        Settings.Global.putInt(cr, "low_power", 1)
    }
    if (Settings.Global.getInt(cr, "low_power_sticky", -1) != 1) {
        Settings.Global.putInt(cr, "low_power_sticky", 1)
    }
    if (Settings.Global.getInt(cr, "low_power_sticky_auto_disable_enabled", -1) != 0) {
        Settings.Global.putInt(cr, "low_power_sticky_auto_disable_enabled", 0)
    }
}

private fun Bundle.applyConfiguredPolicies(spec: String, forPackage: String) {
    spec.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@forEach
            var key = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()

            val scope = key.lastIndexOf(':')
            if (scope >= 0) {
                if (key.substring(0, scope).trim() != forPackage) return@forEach
                key = key.substring(scope + 1).trim()
            }
            if (key.isEmpty()) return@forEach

            when {
                value.equals("true", ignoreCase = true) -> putBoolean(key, true)
                value.equals("false", ignoreCase = true) -> putBoolean(key, false)
                else -> value.toIntOrNull()?.let { putInt(key, it) } ?: putString(key, value)
            }
        }
}

private fun applyChromiumPolicies() {
    val packages = BuildConfig.CHROMIUM_PACKAGES.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (packages.isEmpty()) return

    val base = Bundle().apply {
        putString("DnsOverHttpsMode", "secure")
        val browserDnsRaw = BuildConfig.BROWSER_PRIVATE_DNS
        val dohTemplate = if (browserDnsRaw.startsWith("http://") || browserDnsRaw.startsWith("https://")) {
            browserDnsRaw
        } else {
            "https://$browserDnsRaw/dns-query"
        }
        putString("DnsOverHttpsTemplates", dohTemplate)

        val blocklist = BuildConfig.URL_BLOCKLIST.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
        if (blocklist.isNotEmpty()) putStringArray("URLBlocklist", blocklist)

        val allowlist = BuildConfig.URL_ALLOWLIST.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
        if (allowlist.isNotEmpty()) putStringArray("URLAllowlist", allowlist)
    }

    packages.forEach { pkg ->
        val bundle = Bundle(base).apply { applyConfiguredPolicies(BuildConfig.CHROMIUM_POLICIES, pkg) }
        runCatching { DPM.setApplicationRestrictions(DAR, pkg, bundle) }
    }
}

fun applyStrictRestrictions(context: Context) {
    val desiredRestrictions = buildSet {
        add(UserManager.DISALLOW_ADD_USER)
        add(UserManager.DISALLOW_REMOVE_USER)
        add(UserManager.DISALLOW_SAFE_BOOT)
        add(UserManager.DISALLOW_FACTORY_RESET)
        add(UserManager.DISALLOW_CONFIG_DATE_TIME)
        add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(UserManager.DISALLOW_USER_SWITCH)
            val localeLock = BuildConfig.LOCALE_LOCK
            if (localeLock.isNotBlank() && Locale.getDefault().language == localeLock) {
                add(UserManager.DISALLOW_CONFIG_LOCALE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }
        if (SP.hasRebootedSinceSetup) {
            val cr = context.contentResolver
            val devOptionsOff = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0
            val adbOff = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 0

            if (devOptionsOff && adbOff) {
                add(UserManager.DISALLOW_DEBUGGING_FEATURES)
            }
        }
    }

    runCatching {
        val activeRestrictions = DPM.getUserRestrictions(DAR).keySet()
        activeRestrictions.forEach { currentRestriction ->
            if (currentRestriction !in desiredRestrictions) {
                DPM.clearUserRestriction(DAR, currentRestriction)
            }
        }
        desiredRestrictions.forEach { if (it !in activeRestrictions) DPM.addUserRestriction(DAR, it) }
    }.onFailure { Log.e("Lockdown", "Failed to sync restrictions", it) }
}

private fun hideLauncherIcon(context: Context) = runCatching {
    val componentName = ComponentName(context, MainActivity::class.java)
    val pm = context.packageManager
    if (pm.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
        pm.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

suspend fun enforceStrictWhitelist(context: Context) = withContext(Dispatchers.IO) {
    if (!Privilege.status.value.device) return@withContext
    Privilege.lockdownActive.value = true

    try {
        hideLauncherIcon(context)
        val whitelist = AppWhitelist.getWhitelist()
        val installedApps = AppWhitelist.getInstalledUserApps(context)
        val (toUnsuspend, toSuspend) = installedApps.map { it.packageName }.partition { it in whitelist }

        if (toUnsuspend.isNotEmpty()) runCatching { DPM.setPackagesSuspended(DAR, toUnsuspend.toTypedArray(), false) }
        if (toSuspend.isNotEmpty()) runCatching { DPM.setPackagesSuspended(DAR, toSuspend.toTypedArray(), true) }
    } finally {
        Privilege.lockdownActive.value = false
    }
}
