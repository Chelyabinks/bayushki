package com.sleepclub.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

enum class CheckId { USAGE, NOTIFICATIONS, BATTERY, OEM, SERVICE, SYNC }

data class Check(val id: CheckId, val title: String, val ok: Boolean, val problem: String)

/** Чек-лист «всё ли работает». */
object Health {

    fun hasUsageAccess(ctx: Context) = UsageReader(ctx).hasAccess()

    fun notificationsOk(ctx: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun batteryOk(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun checks(ctx: Context): List<Check> {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val syncOk = prefs.lastSyncOkAt > 0 && now - prefs.lastSyncOkAt < 90 * 60_000L && prefs.lastSyncError == null
        val list = mutableListOf(
            Check(CheckId.USAGE, "Доступ к статистике использования", hasUsageAccess(ctx),
                "Без него приложение не видит, чем ты пользуешься, и у всех ты будешь с ⚠️"),
            Check(CheckId.NOTIFICATIONS, "Уведомления", notificationsOk(ctx),
                "Без уведомлений Android быстрее выгружает приложение, и ты не узнаешь о поломке"),
            Check(CheckId.BATTERY, "Без ограничений батареи", batteryOk(ctx),
                "Android будет усыплять приложение, данные начнут опаздывать на часы"),
            Check(CheckId.SERVICE, "Фоновая служба запущена", TrackerService.running || !prefs.tracking,
                "Служба не работает — нажми «Исправить», и она перезапустится"),
            Check(CheckId.SYNC, "Связь с сервером", syncOk,
                prefs.lastSyncError ?: "Давно не было удачной отправки"),
        )
        if (Oem.needsSetup()) {
            list.add(3, Check(CheckId.OEM, "Автозапуск (${Oem.brandName()})", prefs.oemConfirmed,
                "Без этого ${Oem.brandName()} убивает приложение в фоне"))
        }
        return list
    }

    fun summaryJson(ctx: Context): JSONObject = JSONObject()
        .put("notif", notificationsOk(ctx))
        .put("battery", batteryOk(ctx))
        .put("oem", Prefs(ctx).oemConfirmed || !Oem.needsSetup())
        .put("brand", Build.MANUFACTURER)
        .put("sdk", Build.VERSION.SDK_INT)

    /** Показывает уведомление о новой поломке (один раз на каждую проблему). */
    fun notifyIfBroken(ctx: Context) {
        val prefs = Prefs(ctx)
        // ночью не беспокоим — напомним утром; выбывшим слежка не нужна
        if (!prefs.onboardingDone || prefs.isNight() || !prefs.tracking) return
        val critical = checks(ctx).filter { !it.ok && it.id in setOf(CheckId.USAGE, CheckId.BATTERY, CheckId.NOTIFICATIONS) }
        val ids = critical.map { it.id.name }.toSet()
        val fresh = critical.filter { it.id.name !in prefs.notifiedProblems }
        if (fresh.isNotEmpty()) Notifications.showProblem(ctx, fresh.joinToString("\n") { "❌ ${it.title}: ${it.problem}" })
        if (ids.isEmpty()) Notifications.cancelProblem(ctx)
        prefs.notifiedProblems = ids
    }
}
