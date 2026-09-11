package app.anonymous.warden

import android.app.Application

lateinit var SP: SharedPrefs
    private set

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SP = SharedPrefs(this)
        AppWhitelist.initializeDefaultWhitelist()
        Privilege.initialize(this)
    }
}