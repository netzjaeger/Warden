package app.anonymous.warden

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

object Privilege {
    lateinit var DPM: DevicePolicyManager
        private set

    lateinit var DAR: ComponentName
        private set

    data class Status(
        val device: Boolean = false,
        val profile: Boolean = false
    ) {
        val activated: Boolean get() = device || profile
    }

    val status = MutableStateFlow(Status())
    val lockdownActive = MutableStateFlow(false)

    fun initialize(context: Context) {
        if (!::DPM.isInitialized) {
            DPM = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            DAR = ComponentName(context, Receiver::class.java)
        }
        updateStatus()
    }

    fun updateStatus() {
        status.value = Status(
            device = DPM.isDeviceOwnerApp(DAR.packageName),
            profile = DPM.isProfileOwnerApp(DAR.packageName)
        )
    }
}
