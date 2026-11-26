package com.sleepclub.core

import com.sleepclub.core.EventKind.APP_PAUSED
import com.sleepclub.core.EventKind.APP_RESUMED
import com.sleepclub.core.EventKind.KEYGUARD_HIDDEN
import com.sleepclub.core.EventKind.KEYGUARD_SHOWN
import com.sleepclub.core.EventKind.SCREEN_OFF
import com.sleepclub.core.EventKind.SCREEN_ON
import com.sleepclub.core.EventKind.SHUTDOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBuilderTest {
    private val s = 1_000L
    private val m = 60 * s
    private val launcher = "launcher"
    private val builder = SessionBuilder(ignored = setOf(launcher))

    private fun ev(t: Long, k: EventKind, p: String? = null) = UsageEvent(t, k, p)

    @Test
    fun simpleNightSession() {
        val events = listOf(
            ev(10 * m, SCREEN_ON),
            ev(10 * m + 2 * s, APP_RESUMED, launcher),
            ev(10 * m + 5 * s, APP_PAUSED, launcher),
            ev(10 * m + 5 * s, APP_RESUMED, "tiktok"),
            ev(27 * m, APP_PAUSED, "tiktok"),
            ev(27 * m, SCREEN_OFF),
        )
        val r = builder.build(events, 0, 60 * m)
        assertEquals(listOf(Interval(10 * m, 27 * m)), r.screens)
        assertEquals(listOf(AppSession("tiktok", 10 * m + 5 * s, 27 * m)), r.sessions)
        assertFalse(r.screenOn)
        assertEquals(27 * m, r.screenSince)
        assertNull(r.currentPkg)
    }

    @Test
    fun activitySwitchInsideAppIsOneSession() {
        val events = listOf(
            ev(1 * m, SCREEN_ON),
            ev(1 * m, APP_RESUMED, "tg"),
            ev(2 * m, APP_PAUSED, "tg"),
            ev(2 * m + 300, APP_RESUMED, "tg"), // открыл другой чат — новая activity
            ev(5 * m, APP_PAUSED, "tg"),
            ev(5 * m, APP_RESUMED, "yt"),
            ev(9 * m, SCREEN_OFF),
        )
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(AppSession("tg", 1 * m, 5 * m), AppSession("yt", 5 * m, 9 * m)), r.sessions)
    }

    @Test
    fun openSessionAtWindowEndIsCurrent() {
        val events = listOf(
            ev(1 * m, SCREEN_ON),
            ev(2 * m, APP_RESUMED, "tiktok"),
        )
        val r = builder.build(events, 0, 14 * m)
        assertTrue(r.screenOn)
        assertEquals(1 * m, r.screenSince)
        assertEquals("tiktok", r.currentPkg)
        assertEquals(2 * m, r.currentSince)
        assertEquals(listOf(AppSession("tiktok", 2 * m, 14 * m)), r.sessions)
        assertEquals(listOf(Interval(1 * m, 14 * m)), r.screens)
    }

    @Test
    fun appOpenedBeforeWindowStart() {
        // Окно началось, когда экран уже был включён и открыт YouTube.
        val events = listOf(
            ev(3 * m, APP_PAUSED, "yt"),
            ev(3 * m, APP_RESUMED, launcher),
            ev(4 * m, SCREEN_OFF),
        )
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(AppSession("yt", 0, 3 * m)), r.sessions)
        assertEquals(listOf(Interval(0, 4 * m)), r.screens)
    }

    @Test
    fun screenOffClosesSessionWithoutPause() {
        val events = listOf(
            ev(0, SCREEN_ON),
            ev(1 * m, APP_RESUMED, "inst"),
            ev(6 * m, SCREEN_OFF), // PAUSED пришёл позже выключения
            ev(6 * m + 200, APP_PAUSED, "inst"),
        )
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(AppSession("inst", 1 * m, 6 * m)), r.sessions)
        assertFalse(r.screenOn)
    }

    @Test
    fun longPauseSplitsSameApp() {
        val events = listOf(
            ev(0, SCREEN_ON),
            ev(1 * m, APP_RESUMED, "tg"),
            ev(2 * m, APP_PAUSED, "tg"),
            ev(5 * m, APP_RESUMED, "tg"),
            ev(6 * m, SCREEN_OFF),
        )
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(AppSession("tg", 1 * m, 2 * m), AppSession("tg", 5 * m, 6 * m)), r.sessions)
    }

    @Test
    fun missingScreenOnRecoveredFromResume() {
        val events = listOf(
            ev(1 * m, SCREEN_OFF),
            ev(5 * m, APP_RESUMED, "tg"), // SCREEN_ON потерялся
            ev(7 * m, SCREEN_OFF),
        )
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(Interval(0, 1 * m), Interval(5 * m, 7 * m)), r.screens)
        assertEquals(listOf(AppSession("tg", 5 * m, 7 * m)), r.sessions)
    }

    @Test
    fun shutdownActsAsScreenOff() {
        val events = listOf(
            ev(0, SCREEN_ON),
            ev(1 * m, APP_RESUMED, "tg"),
            ev(3 * m, SHUTDOWN),
        )
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(Interval(0, 3 * m)), r.screens)
        assertEquals(listOf(AppSession("tg", 1 * m, 3 * m)), r.sessions)
    }

    @Test
    fun noEventsMeansScreenOff() {
        val r = builder.build(emptyList(), 0, 10 * m)
        assertTrue(r.screens.isEmpty())
        assertFalse(r.screenOn)
        assertNull(r.screenSince)
    }

    @Test
    fun tinySessionsDroppedAndLauncherIgnored() {
        val events = listOf(
            ev(0, SCREEN_ON),
            ev(1 * m, APP_RESUMED, launcher),
            ev(2 * m, APP_PAUSED, launcher),
            ev(2 * m, APP_RESUMED, "flash"),
            ev(2 * m + 400, APP_PAUSED, "flash"),
            ev(2 * m + 400, APP_RESUMED, launcher),
        )
        val r = builder.build(events, 0, 10 * m)
        assertTrue(r.sessions.isEmpty())
        assertNull(r.currentPkg) // сейчас на рабочем столе
        assertTrue(r.screenOn)
    }

    @Test
    fun eventsOutsideWindowIgnoredAndUnsortedHandled() {
        val events = listOf(
            ev(5 * m, SCREEN_OFF),
            ev(-5 * m, SCREEN_ON),
            ev(2 * m, APP_RESUMED, "tg"),
            ev(11 * m, SCREEN_ON),
        )
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(Interval(0, 5 * m)), r.screens)
        assertEquals(listOf(AppSession("tg", 2 * m, 5 * m)), r.sessions)
    }

    @Test
    fun usedExcludesLockScreenAndIncludesLauncher() {
        val events = listOf(
            ev(0, SCREEN_ON),                       // глянул на время на экране блокировки
            ev(30 * s, SCREEN_OFF),
            ev(30 * s + 100, KEYGUARD_SHOWN),
            ev(10 * m, SCREEN_ON),
            ev(10 * m + 1 * s, APP_RESUMED, "clock"), // будильник поверх блокировки — не считается
            ev(10 * m + 20 * s, APP_PAUSED, "clock"),
            ev(10 * m + 30 * s, KEYGUARD_HIDDEN),   // разблокировал
            ev(10 * m + 30 * s, APP_RESUMED, launcher),
            ev(10 * m + 40 * s, APP_PAUSED, launcher),
            ev(10 * m + 40 * s, APP_RESUMED, "tiktok"),
            ev(12 * m, APP_PAUSED, "tiktok"),
            ev(12 * m, SCREEN_OFF),
            ev(12 * m + 100, KEYGUARD_SHOWN),
        )
        val r = builder.build(events, 0, 60 * m)
        assertEquals(listOf(Interval(10 * m + 30 * s, 12 * m)), r.used)
        assertEquals(2, r.sessions.size) // clock и tiktok, без лаунчера
    }

    @Test
    fun noKeyguardEventsMeansUnlocked() {
        val events = listOf(ev(0, SCREEN_ON), ev(1 * s, APP_RESUMED, "a"), ev(3 * m, SCREEN_OFF))
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(Interval(1 * s, 3 * m)), r.used)
    }

    @Test
    fun windowStartsWhileLocked() {
        // Первое событие блокировки — «снята», значит до него телефон был заблокирован.
        val events = listOf(ev(1 * m, APP_RESUMED, "clock"), ev(2 * m, KEYGUARD_HIDDEN), ev(4 * m, SCREEN_OFF))
        val r = builder.build(events, 0, 10 * m)
        assertEquals(listOf(Interval(2 * m, 4 * m)), r.used)
    }

    @Test
    fun intervalOps() {
        val a = listOf(Interval(0, 10), Interval(5, 15), Interval(20, 30))
        assertEquals(listOf(Interval(0, 15), Interval(20, 30)), Intervals.union(a))
        assertEquals(listOf(Interval(10, 15), Interval(20, 22)), Intervals.intersect(a, listOf(Interval(10, 22))))
        assertEquals(listOf(Interval(0, 3), Interval(8, 15), Interval(20, 30)),
            Intervals.subtract(a, listOf(Interval(3, 8))))
    }
}
