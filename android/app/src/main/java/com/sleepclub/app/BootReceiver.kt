package com.sleepclub.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки телефона и после обновления приложения — снова запускаемся. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).onboardingDone) return
        TrackerService.ensureRunning(context)
        SyncWorker.schedulePeriodic(context)
        SyncWorker.syncSoon(context, "boot")
    }
}
