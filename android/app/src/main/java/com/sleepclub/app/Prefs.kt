package com.sleepclub.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Маленькое хранилище настроек (SharedPreferences). */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("sleepclub", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("server_url", null) ?: BuildConfig.SERVER_URL
        set(v) = p.edit { putString("server_url", v.trim().trimEnd('/')) }

    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit { putString("token", v) }

    var memberId: Long
        get() = p.getLong("member_id", -1)
        set(v) = p.edit { putLong("member_id", v) }

    var myName: String?
        get() = p.getString("my_name", null)
        set(v) = p.edit { putString("my_name", v) }

    var groupName: String?
        get() = p.getString("group_name", null)
        set(v) = p.edit { putString("group_name", v) }

    var consentGiven: Boolean
        get() = p.getBoolean("consent", false)
        set(v) = p.edit { putBoolean("consent", v) }

    var onboardingDone: Boolean
        get() = p.getBoolean("onboarding_done", false)
        set(v) = p.edit { putBoolean("onboarding_done", v) }

    /** Пользователь подтвердил, что включил автозапуск в настройках производителя. */
    var oemConfirmed: Boolean
        get() = p.getBoolean("oem_confirmed", false)
        set(v) = p.edit { putBoolean("oem_confirmed", v) }

    /** До какого момента данные уже успешно лежат на сервере. */
    var lastSyncedUntil: Long?
        get() = p.getLong("last_synced_until", -1).takeIf { it > 0 }
        set(v) = p.edit { putLong("last_synced_until", v ?: -1) }

    var lastSyncOkAt: Long
        get() = p.getLong("last_sync_ok_at", 0)
        set(v) = p.edit { putLong("last_sync_ok_at", v) }

    var lastSyncError: String?
        get() = p.getString("last_sync_error", null)
        set(v) = p.edit { putString("last_sync_error", v) }

    var lastUpdateCheck: Long
        get() = p.getLong("last_update_check", 0)
        set(v) = p.edit { putLong("last_update_check", v) }

    /** Какие проблемы уже показывали уведомлением (чтобы не спамить). */
    var notifiedProblems: Set<String>
        get() = p.getStringSet("notified_problems", emptySet()) ?: emptySet()
        set(v) = p.edit { putStringSet("notified_problems", v) }

    var isOwner: Boolean
        get() = p.getBoolean("is_owner", false)
        set(v) = p.edit { putBoolean("is_owner", v) }

    /** Группу только что создали: после настройки телефона открыть экран, где задают отбой и подъём. */
    var setupPending: Boolean
        get() = p.getBoolean("setup_pending", false)
        set(v) = p.edit { putBoolean("setup_pending", v) }

    var inviteCode: String?
        get() = p.getString("invite_code", null)
        set(v) = p.edit { putString("invite_code", v) }

    /** Текущая или ближайшая ночь (отбой–подъём), как её прислал сервер. */
    var nightBedMs: Long
        get() = p.getLong("night_bed", 0)
        set(v) = p.edit { putLong("night_bed", v) }

    var nightWakeMs: Long
        get() = p.getLong("night_wake", 0)
        set(v) = p.edit { putLong("night_wake", v) }

    /** Последнее событие группы, о котором уже показали уведомление (-1 — ещё не знаем). */
    var lastEventId: Long
        get() = p.getLong("last_event_id", -1)
        set(v) = p.edit { putLong("last_event_id", v) }

    /** Нужно ли следить (в игре и раунд идёт). Выбывших не отслеживаем вовсе. */
    var tracking: Boolean
        get() = p.getBoolean("tracking", true)
        set(v) = p.edit { putBoolean("tracking", v) }

    /** Своё положение в игре по последнему ответу сервера: in | out | winner. */
    var gameState: String
        get() = p.getString("game_state", "in") ?: "in"
        set(v) = p.edit { putString("game_state", v) }

    fun isNight(now: Long = System.currentTimeMillis()): Boolean = now in nightBedMs until nightWakeMs

    fun saveRegistration(r: JoinResult) {
        token = r.token
        memberId = r.memberId
        myName = r.name
        groupName = r.groupName
        inviteCode = r.inviteCode
        isOwner = r.isOwner
        lastSyncedUntil = null
        lastEventId = -1
        tracking = true
        gameState = "in"
    }

    /** Вышел из группы или выгнали: забываем всё, кроме адреса сервера и пройденных настроек телефона. */
    fun clearRegistration() = p.edit {
        listOf("token", "member_id", "my_name", "group_name", "invite_code", "is_owner", "last_synced_until",
            "last_event_id", "night_bed", "night_wake", "tracking", "game_state").forEach { remove(it) }
        putBoolean("onboarding_done", false)
    }

    val isRegistered: Boolean get() = token != null
}
