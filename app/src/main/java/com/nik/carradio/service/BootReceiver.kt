package com.nik.carradio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.RadioController

/**
 * Cold boot (and the "quick boot" some head units send). Resumes the radio if it was on.
 * It never starts the foreground service from here on Android 15+, where a mediaPlayback
 * service may not start from BOOT_COMPLETED: RadioService.start() is guarded and the MCU
 * tuner plays without it anyway. Most head units sleep on ACC off instead of booting;
 * that path is the FYT ACC-on callback in the controller.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AppLog.i("Boot broadcast: ${intent.action}")
        RadioController.resumeAfterBoot()
    }
}
