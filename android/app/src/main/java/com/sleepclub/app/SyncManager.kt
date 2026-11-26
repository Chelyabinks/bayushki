package com.sleepclub.app

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.sleepclub.core.SessionBuilder
import com.sleepclub.core.SyncPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Одна отправка: прочитать журнал с прошлой отметки → собрать сессии → отправить пачкой (gzip).
 * Если отправка не удалась, отметка не двигается — в следующий раз уйдёт всё накопленное.
 */
object SyncManager {
    /** Ответ сервера: время отбоя и новости группы (сервер отдаёт их только после подъёма). */
    private fun handleResponse(ctx: Context, prefs: Prefs, r: JSONObject) {
        r.optJSONObject("night")?.let { n ->
            prefs.nightBedMs = n.optLongOrNull("bed_ms") ?: 0
            prefs.nightWakeMs = n.optLongOrNull("wake_ms") ?: 0
        }
        r.optLongOrNull("last_event_id")?.let { prefs.lastEventId = it }

        // Своё положение в игре: о вылете сообщаем сразу (даже ночью).
        r.optJSONObject("game")?.let { g ->
            val state = g.optString("state", "in")
            if (state == "out" && prefs.gameState != "out") Notifications.showEliminated(ctx, g.optStringOrNull("reason"))
            prefs.gameState = state
        }
        if (r.has("tracking")) {
            val tracking = r.optBoolean("tracking", true)
            if (prefs.tracking && !tracking) {
                prefs.tracking = false
                TrackerService.stop(ctx)
            } else if (!prefs.tracking && tracking) {
                // Новый раунд: следим заново, старое не досылаем.
                prefs.tracking = true
                prefs.lastSyncedUntil = System.currentTimeMillis()
                TrackerService.ensureRunning(ctx)
            }
        }
        val events = r.optJSONArray("events")?.objects().orEmpty()
        if (events.isNotEmpty() && !prefs.isNight()) {
            Notifications.showNews(ctx, events.map { it.getString("text") })
            prefs.lastEventId = events.last().getLong("id")
        }
    }

    private const val TAG = "SleepSync"
    private val mutex = Mutex()

    suspend fun sync(context: Context, reason: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { doSync(context.applicationContext, reason) }
    }

    private suspend fun doSync(ctx: Context, reason: String): Boolean {
        val prefs = Prefs(ctx)
        val token = prefs.token ?: return false
        val reader = UsageReader(ctx)
        val now = System.currentTimeMillis()
        val tracking = prefs.tracking
        // Выбывший: ничего о нём не собираем, только спрашиваем сервер, не начался ли новый раунд.
        val perm = if (tracking) reader.hasAccess() else true
        val interactive = ctx.getSystemService(PowerManager::class.java).isInteractive

        val payload = JSONObject()
            .put("v", 2)
            .put("now", now)
            .put("perm", perm)
            .put("ver", BuildConfig.VERSION_CODE)
            .put("reason", reason)
            .put("screen_on", interactive && tracking)
            // Отбой у каждого по его местному времени — пояс телефона сервер узнаёт отсюда.
            .put("tz", java.util.TimeZone.getDefault().getOffset(now) / 60_000)
            .put("tz_id", java.util.TimeZone.getDefault().id)
            .put("health", Health.summaryJson(ctx))
        if (prefs.lastEventId >= 0) payload.put("ev", prefs.lastEventId)

        if (perm && tracking) {
            val last = prefs.lastSyncedUntil
            val start = SyncPlanner.windowStart(last, now)
            val snap = SessionBuilder(ignored = reader.ignoredPackages())
                .build(reader.events(start, now), start, now)
            val fresh = SyncPlanner.onlyNew(snap, last)

            val apps = JSONObject()
            fun app(pkg: String) {
                if (!apps.has(pkg)) apps.put(pkg, JSONArray().put(reader.label(pkg)).put(reader.category(pkg)))
            }
            val sessions = JSONArray()
            for (s in fresh.sessions) {
                app(s.pkg)
                sessions.put(JSONArray().put(s.pkg).put(s.start).put(s.end))
            }
            fun intervals(list: List<com.sleepclub.core.Interval>) =
                JSONArray().also { a -> list.forEach { a.put(JSONArray().put(it.start).put(it.end)) } }
            payload.put("apps", apps).put("sessions", sessions)
                .put("screens", intervals(fresh.screens)).put("used", intervals(fresh.used))


            // Журнал иногда чуть отстаёт от реальности — для «экран включён» верим PowerManager.
            val since = if (snap.screenOn == interactive) snap.screenSince else now
            payload.put("screen_since", since ?: now)
            if (interactive && snap.currentPkg != null) {
                app(snap.currentPkg!!)
                payload.put("cur", JSONObject().put("pkg", snap.currentPkg).put("since", snap.currentSince))
            }
        } else {
            payload.put("screen_since", now)
        }

        return try {
            val r = Api(prefs.serverUrl, token).upload(payload)
            if (perm && tracking) prefs.lastSyncedUntil = now
            handleResponse(ctx, prefs, r)
            prefs.lastSyncOkAt = now
            prefs.lastSyncError = null
            Log.i(TAG, "sync ok ($reason), ${payload.optJSONArray("sessions")?.length() ?: 0} сессий")
            true
        } catch (e: ApiException) {
            prefs.lastSyncError = if (e.code == 401) "Тебя нет в группе (удалили или вышел) — войди заново" else e.message
            Log.w(TAG, "sync failed ($reason): ${e.message}")
            false
        } catch (e: Exception) {
            prefs.lastSyncError = "Нет связи с сервером: ${e.javaClass.simpleName}"
            Log.w(TAG, "sync failed ($reason)", e)
            false
        }
    }
}
