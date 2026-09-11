package app.anonymous.warden

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.anonymous.warden.dpm.handlePrivilegeChange
import app.anonymous.warden.dpm.retrieveSecurityLogs

class Receiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Privilege.updateStatus()
        Log.d("Receiver", "Device admin enabled.")
        handlePrivilegeChange(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Privilege.updateStatus()
        Log.d("Receiver", "Device admin disabled.")
    }

    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        super.onSecurityLogsAvailable(context, intent)
        retrieveSecurityLogs()
    }
}