package com.sleepclub.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        if (Prefs(this).onboardingDone) {
            SyncWorker.schedulePeriodic(this)
            TrackerService.ensureRunning(this)
        }
    }
}
