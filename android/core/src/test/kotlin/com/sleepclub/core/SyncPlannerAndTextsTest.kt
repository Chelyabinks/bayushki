package com.sleepclub.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SyncPlannerAndTextsTest {
    private val h = SyncPlanner.HOUR
    private val now = 1_000 * h

    @Test
    fun firstSyncTakesLastDay() = assertEquals(now - 24 * h, SyncPlanner.windowStart(null, now))

    @Test
    fun overlapBeforeLastSync() = assertEquals(now - 7 * h, SyncPlanner.windowStart(now - h, now))

    @Test
    fun longOfflineCappedAtWeek() = assertEquals(now - 7 * 24 * h, SyncPlanner.windowStart(now - 30 * 24 * h, now))

    @Test
    fun onlyNewDropsOldIntervals() {
        val snap = UsageSnapshot(
            screens = listOf(Interval(now - 5 * h, now - 4 * h), Interval(now - 2 * h, now)),
            sessions = listOf(AppSession("a", now - 5 * h, now - 4 * h), AppSession("b", now - 90 * 60_000L, now - 30 * 60_000L)),
            screenOn = true, screenSince = now - 2 * h, currentPkg = null, currentSince = null,
        )
        val r = SyncPlanner.onlyNew(snap, now - h)
        assertEquals(listOf(Interval(now - 2 * h, now)), r.screens)
        assertEquals(listOf("b"), r.sessions.map { it.pkg })
    }

    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(hh: Int, mm: Int) = ZonedDateTime.of(2026, 11, 25, hh, mm, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun durations() {
        assertEquals("<1 мин", Texts.duration(30_000))
        assertEquals("17 мин", Texts.duration(17 * 60_000L))
        assertEquals("1 ч 05 мин", Texts.duration(65 * 60_000L))
    }

    @Test
    fun statusLines() {
        val n = at(2, 30)
        assertEquals("сейчас в телефоне: TikTok, 12 мин",
            Texts.statusLine(MemberStatus("active", appName = "TikTok", sinceMs = at(2, 18)), n, zone))
        assertEquals("телефон отложен в 00:42",
            Texts.statusLine(MemberStatus("idle", sinceMs = at(0, 42)), n, zone))
        assertEquals("слежка отключена: отозван доступ к статистике",
            Texts.statusLine(MemberStatus("stale", reason = "permission"), n, zone))
        assertEquals("02:14–02:31 · TikTok · 17 мин", Texts.sessionLine(at(2, 14), at(2, 31), "TikTok", zone))
        // До отбоя приложение друзьям не показывается — только «в телефоне»
        assertEquals("сейчас в телефоне, 12 мин",
            Texts.statusLine(MemberStatus("active", sinceMs = at(2, 18)), n, zone))
        // Выбывших не отслеживаем
        assertEquals("не отслеживается: вне игры", Texts.statusLine(MemberStatus("untracked"), n, zone))
        assertEquals("⏸", Texts.statusEmoji(MemberStatus("untracked")))
        // У участника отбой — друзья видят только это
        assertEquals("отбой", Texts.statusLine(MemberStatus("curfew"), n, zone))
        assertEquals("😴", Texts.statusEmoji(MemberStatus("curfew")))
    }

    @Test
    fun timezoneLabels() {
        assertEquals("UTC+3", Texts.utcLabel(180))
        assertEquals("UTC+5:30", Texts.utcLabel(330))
        assertEquals("UTC−4", Texts.utcLabel(-240))
        assertEquals("UTC+0", Texts.utcLabel(0))
    }

    @Test
    fun curfewJokeIsStableWithinADay() {
        assertEquals(Texts.curfewJoke(7, 20_000), Texts.curfewJoke(7, 20_000))
        // за неделю подписи меняются
        assert((0L until 7L).map { Texts.curfewJoke(7, 20_000 + it) }.toSet().size > 1)
        // отрицательные id/дни не ломают выбор
        Texts.curfewJoke(-1, -5)
    }
}
