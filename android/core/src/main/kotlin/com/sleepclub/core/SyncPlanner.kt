package com.sleepclub.core

/**
 * Решает, какой кусок журнала перечитывать и что из него отправлять.
 *
 * Телефон хранит только отметку «успешно отправлено до» (lastSyncedUntil).
 * Пока сервер недоступен, отметка не двигается — и при следующей удачной отправке
 * всё накопленное время будет перечитано из системного журнала и отправлено одной пачкой.
 * Окно берётся с запасом (OVERLAP), чтобы правильно склеить сессии, начатые раньше отметки;
 * сервер склеивает пересекающиеся интервалы, поэтому повторы безопасны.
 */
object SyncPlanner {
    const val HOUR = 3_600_000L
    const val OVERLAP_MS = 6 * HOUR
    const val FIRST_SYNC_BACK_MS = 24 * HOUR
    const val MAX_BACK_MS = 7 * 24 * HOUR
    private const val SLACK_MS = 60_000L

    fun windowStart(lastSyncedUntil: Long?, now: Long): Long {
        val from = if (lastSyncedUntil == null) now - FIRST_SYNC_BACK_MS else lastSyncedUntil - OVERLAP_MS
        return maxOf(from, now - MAX_BACK_MS)
    }

    /** Оставляет только то, что изменилось после прошлой отправки. */
    fun onlyNew(snapshot: UsageSnapshot, lastSyncedUntil: Long?): UsageSnapshot {
        if (lastSyncedUntil == null) return snapshot
        val edge = lastSyncedUntil - SLACK_MS
        return snapshot.copy(
            screens = snapshot.screens.filter { it.end >= edge },
            sessions = snapshot.sessions.filter { it.end >= edge },
            used = snapshot.used.filter { it.end >= edge },
        )
    }
}
