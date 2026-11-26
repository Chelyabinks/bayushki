package com.sleepclub.core

/** Упрощённое событие из журнала Android (UsageEvents.Event). */
enum class EventKind { APP_RESUMED, APP_PAUSED, SCREEN_ON, SCREEN_OFF, SHUTDOWN, KEYGUARD_SHOWN, KEYGUARD_HIDDEN }

data class UsageEvent(val time: Long, val kind: EventKind, val pkg: String? = null)

data class Interval(val start: Long, val end: Long) {
    val duration: Long get() = end - start
}

data class AppSession(val pkg: String, val start: Long, val end: Long) {
    val duration: Long get() = end - start
}

/**
 * Результат разбора журнала за окно [windowStart, windowEnd].
 * Незакрытые интервалы (экран ещё включён, приложение ещё открыто) заканчиваются на windowEnd.
 */
data class UsageSnapshot(
    val screens: List<Interval>,
    val sessions: List<AppSession>,
    val screenOn: Boolean,
    /** Если экран включён — когда включили; если выключен — когда выключили (null — не знаем). */
    val screenSince: Long?,
    val currentPkg: String?,
    val currentSince: Long?,
    /**
     * Когда телефоном реально пользовались: экран включён, телефон разблокирован и на экране
     * какое-то приложение (включая рабочий стол). Именно это проверяют правила ночи —
     * горящий экран блокировки не считается.
     */
    val used: List<Interval> = emptyList(),
)

/**
 * Превращает поток событий в интервалы «экран включён» и сессии по приложениям.
 *
 * Тонкости:
 *  - при переходе между экранами одного приложения Android шлёт PAUSED и сразу RESUMED —
 *    это одна сессия, если пауза короче [samePkgGapMs];
 *  - если в начале окна видим PAUSED без RESUMED — приложение было открыто до окна;
 *  - выключение экрана закрывает текущую сессию;
 *  - сессии пакетов из [ignored] (лаунчер, системный UI) не попадают в результат,
 *    но время в них учитывается в [UsageSnapshot.used];
 *  - пока показан экран блокировки (KEYGUARD_SHOWN…KEYGUARD_HIDDEN), телефоном не пользуются,
 *    даже если поверх блокировки открыт будильник или звонок.
 */
class SessionBuilder(
    private val ignored: Set<String> = emptySet(),
    private val minSessionMs: Long = 1_000,
    private val samePkgGapMs: Long = 5_000,
) {
    fun build(events: List<UsageEvent>, windowStart: Long, windowEnd: Long): UsageSnapshot {
        val evs = events.filter { it.time in windowStart..windowEnd }.sortedBy { it.time }

        val screens = ArrayList<Interval>()
        val sessions = ArrayList<AppSession>()

        // Начальное состояние экрана: смотрим на первое экранное событие.
        val firstScreen = evs.firstOrNull { it.kind == EventKind.SCREEN_ON || it.kind == EventKind.SCREEN_OFF || it.kind == EventKind.SHUTDOWN }
        var screenOn = when (firstScreen?.kind) {
            EventKind.SCREEN_ON -> false
            EventKind.SCREEN_OFF, EventKind.SHUTDOWN -> true
            else -> evs.any { it.kind == EventKind.APP_RESUMED || it.kind == EventKind.APP_PAUSED }
        }
        var screenStart = windowStart
        var lastOff: Long? = null

        // Экран блокировки. Начальное состояние — по первому событию блокировки в окне.
        val firstLock = evs.firstOrNull { it.kind == EventKind.KEYGUARD_SHOWN || it.kind == EventKind.KEYGUARD_HIDDEN }
        var locked = firstLock?.kind == EventKind.KEYGUARD_HIDDEN
        var lockStart = windowStart
        val locks = ArrayList<Interval>()

        var fgPkg: String? = null
        var fgStart = 0L
        var pendingPause: Long? = null
        var sawAppEvent = false

        fun addSession(pkg: String, start: Long, end: Long) {
            if (end - start >= minSessionMs) sessions += AppSession(pkg, start, end)
        }

        fun closeFg(at: Long) {
            val pkg = fgPkg
            if (pkg != null) addSession(pkg, fgStart, at)
            fgPkg = null
            pendingPause = null
        }

        for (e in evs) {
            when (e.kind) {
                EventKind.KEYGUARD_SHOWN -> if (!locked) {
                    locked = true
                    lockStart = e.time
                }

                EventKind.KEYGUARD_HIDDEN -> if (locked) {
                    if (e.time > lockStart) locks += Interval(lockStart, e.time)
                    locked = false
                }

                EventKind.SCREEN_ON -> if (!screenOn) {
                    screenOn = true
                    screenStart = e.time
                }

                EventKind.SCREEN_OFF, EventKind.SHUTDOWN -> {
                    closeFg(pendingPause ?: e.time)
                    if (screenOn) {
                        if (e.time > screenStart) screens += Interval(screenStart, e.time)
                        screenOn = false
                    }
                    lastOff = e.time
                }

                EventKind.APP_RESUMED -> {
                    val pkg = e.pkg ?: continue
                    sawAppEvent = true
                    val pause = pendingPause
                    if (fgPkg == pkg && (pause == null || e.time - pause <= samePkgGapMs)) {
                        pendingPause = null // переход внутри того же приложения
                    } else {
                        closeFg(pause ?: e.time)
                        fgPkg = pkg
                        fgStart = e.time
                    }
                    if (!screenOn) { // событие «экран включён» потерялось — восстанавливаем
                        screenOn = true
                        screenStart = e.time
                    }
                }

                EventKind.APP_PAUSED -> {
                    val pkg = e.pkg ?: continue
                    if (fgPkg == pkg) {
                        pendingPause = e.time
                    } else if (fgPkg == null && !sawAppEvent) {
                        addSession(pkg, windowStart, e.time) // было открыто ещё до начала окна
                    }
                    sawAppEvent = true
                }
            }
        }

        var currentPkg: String? = null
        var currentSince: Long? = null
        val pkg = fgPkg
        if (pkg != null) {
            val pause = pendingPause
            if (pause != null) {
                closeFg(pause)
            } else {
                addSession(pkg, fgStart, windowEnd)
                if (pkg !in ignored) {
                    currentPkg = pkg
                    currentSince = fgStart
                }
            }
        }
        if (screenOn && windowEnd > screenStart) screens += Interval(screenStart, windowEnd)
        if (locked && windowEnd > lockStart) locks += Interval(lockStart, windowEnd)

        val used = Intervals.subtract(Intervals.intersect(Intervals.union(sessions.map { Interval(it.start, it.end) }), screens), locks)

        return UsageSnapshot(
            screens = screens,
            sessions = sessions.filter { it.pkg !in ignored },
            screenOn = screenOn,
            screenSince = if (screenOn) screenStart else lastOff,
            currentPkg = currentPkg,
            currentSince = currentSince,
            used = used,
        )
    }
}

/** Операции над списками интервалов. */
object Intervals {
    fun union(list: List<Interval>): List<Interval> {
        val out = ArrayList<Interval>()
        for (i in list.filter { it.end > it.start }.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && i.start <= last.end) out[out.size - 1] = Interval(last.start, maxOf(last.end, i.end))
            else out += i
        }
        return out
    }

    fun intersect(a: List<Interval>, b: List<Interval>): List<Interval> {
        val out = ArrayList<Interval>()
        for (x in union(a)) for (y in union(b)) {
            val s = maxOf(x.start, y.start)
            val e = minOf(x.end, y.end)
            if (e > s) out += Interval(s, e)
        }
        return union(out)
    }

    fun subtract(a: List<Interval>, holes: List<Interval>): List<Interval> {
        val hs = union(holes)
        val out = ArrayList<Interval>()
        for (x in union(a)) {
            var cur = x.start
            for (h in hs) {
                if (h.end <= cur || h.start >= x.end) continue
                if (h.start > cur) out += Interval(cur, h.start)
                cur = maxOf(cur, h.end)
            }
            if (cur < x.end) out += Interval(cur, x.end)
        }
        return out
    }
}
