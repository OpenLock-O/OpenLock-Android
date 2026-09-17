package moe.openlock.app

import android.app.Application
import moe.openlock.app.data.SettingsStore

class OpenLockApp : Application() {

    /** App-wide preferences; the repository reads these directly. */
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }
}
