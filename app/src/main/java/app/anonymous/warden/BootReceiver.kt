package app.anonymous.warden

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.anonymous.warden.dpm.enforceAllPolicies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in arrayOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Privilege.status.value.device) {
                    if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                        Log.d("BootReceiver", "Package updated; re-evaluating policies.")
                    } else if (!SP.hasRebootedSinceSetup) {
                        SP.hasRebootedSinceSetup = true
                        Log.d("BootReceiver", "First reboot after setup detected.")
                    }
                    enforceAllPolicies(context)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Policy enforcement failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
