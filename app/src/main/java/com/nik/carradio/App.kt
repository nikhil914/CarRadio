package com.nik.carradio

import android.app.Application
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.DeviceInfo
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.StationStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { AppLog.e("CRASH in thread ${t.name}: ${e.javaClass.name}: ${e.message}", e); AppLog.flush() }
            previous?.uncaughtException(t, e)
        }
        AppLog.i("==== App start ${BuildConfig.VERSION_NAME} on Android ${DeviceInfo.realAndroid()} (${android.os.Build.MODEL}) ====")
        RadioController.init(this)
        StationStore.load(this, RadioController.prefs)
        com.nik.carradio.core.UiConfig.init(this)
        com.nik.carradio.ui.LogoStore.init(this)
        com.nik.carradio.service.Widgets.start(this)
        com.nik.carradio.core.Updater.autoCheck(this)
    }
}
