package com.sleepclub.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Статус участника, как его вернул сервер. */
data class MemberStatus(
    val state: String, // active | idle | stale
    val reason: String? = null,
    val appName: String? = null,
    val sinceMs: Long? = null,
    val lastUploadMs: Long? = null,
)

object Texts {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DHM = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    fun time(ms: Long, zone: ZoneId): String = HM.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun dateTime(ms: Long, now: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone)
        val n = Instant.ofEpochMilli(now).atZone(zone)
        return if (d.toLocalDate() == n.toLocalDate()) HM.format(d) else DHM.format(d)
    }

    /** «17 мин», «1 ч 05 мин», «меньше минуты». */
    fun duration(ms: Long): String {
        val totalMin = ms / 60_000
        if (totalMin < 1) return "<1 мин"
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h == 0L) "$m мин" else "$h ч ${m.toString().padStart(2, '0')} мин"
    }

    fun statusEmoji(s: MemberStatus): String = when (s.state) {
        "active" -> "🟢"
        "idle" -> "🌙"
        "untracked" -> "⏸"
        "curfew" -> "😴"
        else -> "⚠️"
    }

    fun statusLine(s: MemberStatus, now: Long, zone: ZoneId): String = when (s.state) {
        "active" -> {
            val dur = s.sinceMs?.let { ", " + duration(now - it) } ?: ""
            if (s.appName != null) "сейчас в телефоне: ${s.appName}$dur" else "сейчас в телефоне$dur"
        }
        "idle" -> s.sinceMs?.let { "телефон отложен в ${dateTime(it, now, zone)}" } ?: "телефон отложен"
        "untracked" -> "не отслеживается: вне игры"
        "curfew" -> "отбой"
        else -> when (s.reason) {
            "permission" -> "слежка отключена: отозван доступ к статистике"
            "no_data" -> "слежка отключена: ещё не присылал данные"
            else -> "слежка отключена: давно нет данных" +
                (s.lastUploadMs?.let { " (последние в ${dateTime(it, now, zone)})" } ?: "")
        }
    }

    private val CURFEW_JOKES = listOf(
        "считает овец",
        "уже обнимается с подушкой",
        "смотрит сны в 4K",
        "абонент временно спит",
        "в режиме «не беспокоить» до утра",
        "баюшки-баю",
        "подзаряжается вместе с телефоном",
        "ушёл на ночную смену во сне",
        "храпит в такт будильнику",
        "видит сон, где он не сидит в телефоне",
    )

    /** Шуточная подпись к «отбою». Одна и та же весь день для одного человека, но меняется по дням. */
    fun curfewJoke(memberId: Long, dayNumber: Long): String =
        CURFEW_JOKES[Math.floorMod(memberId * 31 + dayNumber, CURFEW_JOKES.size.toLong()).toInt()]

    /** «UTC+3», «UTC+5:30», «UTC−4» */
    fun utcLabel(offsetMin: Int): String {
        val sign = if (offsetMin < 0) "−" else "+"
        val a = kotlin.math.abs(offsetMin)
        return "UTC$sign${a / 60}" + if (a % 60 != 0) ":%02d".format(a % 60) else ""
    }

    /** «02:14–02:31 · TikTok · 17 мин» */
    fun sessionLine(start: Long, end: Long, name: String, zone: ZoneId): String =
        "${time(start, zone)}–${time(end, zone)} · $name · ${duration(end - start)}"
}
