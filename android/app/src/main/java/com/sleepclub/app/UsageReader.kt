package com.sleepclub.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import com.sleepclub.core.EventKind
import com.sleepclub.core.UsageEvent

/** Читает системный журнал использования и названия приложений. */
class UsageReader(private val context: Context) {
    private val pm = context.packageManager
    private val labels = HashMap<String, String>()

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun events(start: Long, end: Long): List<UsageEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(start, end) ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> EventKind.APP_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> EventKind.APP_PAUSED
                UsageEvents.Event.SCREEN_INTERACTIVE -> EventKind.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> EventKind.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_SHOWN -> EventKind.KEYGUARD_SHOWN
                UsageEvents.Event.KEYGUARD_HIDDEN -> EventKind.KEYGUARD_HIDDEN
                26 /* UsageEvents.Event.DEVICE_SHUTDOWN, API 29+ */ -> EventKind.SHUTDOWN
                else -> null
            } ?: continue
            out += UsageEvent(e.timeStamp, kind, e.packageName)
        }
        return out
    }

    /** Рабочие столы и системный интерфейс — это не «приложения», их не показываем. */
    fun ignoredPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = pm.queryIntentActivities(home, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        return (launchers + listOf("com.android.systemui", "android")).toSet()
    }

    /** Категория из манифеста приложения (игра, видео, соцсеть…), -1 — не указана. */
    fun category(pkg: String): Int = try {
        pm.getApplicationInfo(pkg, 0).category
    } catch (_: PackageManager.NameNotFoundException) {
        -1
    }

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
