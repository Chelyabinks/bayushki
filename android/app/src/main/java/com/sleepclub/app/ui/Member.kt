package com.sleepclub.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepclub.app.Api
import com.sleepclub.app.Prefs
import com.sleepclub.app.Timeline
import com.sleepclub.core.Texts
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

/** «Личное дело»: протокол дня участника на листе в линейку. */
@Composable
fun MemberScreen(memberId: Long, name: String, no: Int, resumeTick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val zone = ZoneId.systemDefault()
    var dateStr by rememberSaveable { mutableStateOf(LocalDate.now(zone).toString()) }
    val date = LocalDate.parse(dateStr)
    var data by remember { mutableStateOf<Timeline?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Текущие отбой и подъём группы: у каждого они наступают по его местному времени.
    var schedule by remember { mutableStateOf<Pair<String, String>?>(null) }

    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    LaunchedEffect(memberId, dateStr, resumeTick) {
        data = null
        while (true) {
            try {
                val api = Api(prefs.serverUrl, prefs.token)
                data = api.timeline(memberId, dayStart, dayEnd)
                schedule = runCatching { api.group().settings.let { it.bedtime to it.wake } }.getOrNull() ?: schedule
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Нет связи с сервером"
            }
            delay(60_000)
        }
    }

    val now = System.currentTimeMillis()
    val mono = monoLabel(12, Palette.InkMuted, 1.0)
    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    val s = size.width / 24f
                    drawLine(Palette.Text, Offset(15 * s, 5 * s), Offset(8 * s, 12 * s), 2 * s, StrokeCap.Round)
                    drawLine(Palette.Text, Offset(8 * s, 12 * s), Offset(15 * s, 19 * s), 2 * s, StrokeCap.Round)
                }
            }
            Text("ЛИЧНОЕ ДЕЛО № %02d".format(no), style = monoLabel())
        }

        Box(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp).fillMaxSize()
                .shadow(16.dp, RoundedCornerShape(3.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.PaperSheet)
                .drawBehind {
                    val step = 32.dp.toPx()
                    var y = step
                    while (y < size.height) {
                        drawLine(Palette.PaperRule, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        y += step
                    }
                },
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Протокол за выбранный день, с листанием по дням
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", style = mono.copy(fontSize = 20.sp),
                        modifier = Modifier.clickable { dateStr = date.minusDays(1).toString() }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp))
                    Text("ПРОТОКОЛ · ${DAY_FMT.format(date).uppercase()}", style = mono, modifier = Modifier.weight(1f))
                    val canNext = date < LocalDate.now(zone)
                    Text("›", style = mono.copy(fontSize = 20.sp, color = if (canNext) Palette.InkMuted else Color.Transparent),
                        modifier = Modifier.clickable(enabled = canNext) { dateStr = date.plusDays(1).toString() }
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                }
                Text(name.uppercase(), style = TextStyle(fontFamily = AppFonts.display, fontSize = 44.sp, lineHeight = 44.sp, color = Palette.Ink))

                error?.let { Text(it, color = Palette.InkRed, fontSize = 14.sp) }
                val d = data
                if (d == null) {
                    if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Ink, trackColor = Palette.PaperRule)
                    return@Column
                }
                val m = d.member
                val theirZone = m.tzOffsetMin?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: zone

                val meta = buildList {
                    schedule?.let { (bed, wake) -> add("отбой $bed–$wake") }
                    m.tzOffsetMin?.let { add(Texts.utcLabel(it)) }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = monoLabel(12, Palette.InkMuted, 0.0))

                Text(Texts.statusLine(m.status, now, zone), fontSize = 14.sp,
                    color = if (m.status.state == "stale") Palette.InkRed else Palette.InkSoft)

                DayBar(d, dayStart, dayEnd)
                val screenTotal = d.screens.sumOf { it.second - it.first }
                val usedTotal = d.used.sumOf { it.second - it.first }
                val night = d.nights.sumOf { (ns, ne) -> d.used.sumOf { (s, e) -> maxOf(0L, minOf(e, ne) - maxOf(s, ns)) } }
                Text("Экран включён: ${Texts.duration(screenTotal)}" +
                    (if (usedTotal > 0) ", из них разблокирован: ${Texts.duration(usedTotal)}" else ""),
                    fontSize = 14.sp, color = Palette.Ink, fontWeight = FontWeight.Medium)
                if (d.nights.isNotEmpty()) {
                    Text("После отбоя: ${if (night > 0) Texts.duration(night) else "ни минуты"}", fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, color = if (night > 0) Palette.InkRed else Palette.Ink)
                }

                // Журнал сессий
                Column {
                    val cell = TextStyle(fontFamily = AppFonts.mono, fontSize = 14.sp, color = Palette.Ink)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("ВРЕМЯ", style = monoLabel(11, Palette.InkMuted, 1.0), modifier = Modifier.width(64.dp))
                        Text("ГДЕ", style = monoLabel(11, Palette.InkMuted, 1.0), modifier = Modifier.weight(1f))
                        Text("СКОЛЬКО", style = monoLabel(11, Palette.InkMuted, 1.0), textAlign = TextAlign.End)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Palette.Ink))
                    if (d.sessions.isEmpty()) {
                        Text("Приложений в это время не видно", style = cell.copy(color = Palette.InkMuted), modifier = Modifier.padding(vertical = 8.dp))
                    }
                    for (s in d.sessions) {
                        val atNight = d.nights.any { (ns, ne) -> s.end > ns && s.start < ne }
                        val st = if (atNight) cell.copy(color = Palette.InkRed, fontWeight = FontWeight.Bold) else cell
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text(Texts.time(s.start, zone), style = st, modifier = Modifier.width(64.dp))
                            Text(s.name, style = if (atNight) st.copy(textDecoration = TextDecoration.Underline) else st,
                                modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 1)
                            Text(mmss(s.end - s.start), style = st)
                        }
                    }
                }
                if (memberId != prefs.memberId) {
                    Text("Приложения друзей видно за 40 минут до их отбоя, а что они делали ночью — после их подъёма. " +
                        "Днём — только «в телефоне или нет».", color = Palette.InkMuted, fontSize = 13.sp)
                }

                if (d.totals.isNotEmpty()) {
                    Text("ИТОГ ПО ПРИЛОЖЕНИЯМ", style = monoLabel(11, Palette.InkMuted, 1.0), modifier = Modifier.padding(top = 6.dp))
                    val max = d.totals.maxOf { it.second }.coerceAtLeast(1)
                    for ((app, ms) in d.totals.take(15)) {
                        Column {
                            Row {
                                Text(app, fontSize = 14.sp, color = Palette.Ink, modifier = Modifier.weight(1f))
                                Text(Texts.duration(ms), style = monoLabel(12, Palette.InkMuted, 0.0))
                            }
                            Box(Modifier.fillMaxWidth().height(4.dp).background(Palette.PaperRule)) {
                                Box(Modifier.fillMaxWidth(ms.toFloat() / max).fillMaxHeight().background(Palette.Ink))
                            }
                        }
                    }
                }

                if (m.game.state == "out") {
                    Text("Основание: ${m.game.reason ?: "нарушение режима"}. Выбыл до конца раунда.",
                        fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFF2E2A22))
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (m.game.state == "out") "Не расстраивайся.\nЗавтра ляжешь пораньше." else "Спи крепко.\nЗавтра снова проверю.",
                        style = TextStyle(fontFamily = AppFonts.hand, fontWeight = FontWeight.Medium, fontSize = 24.sp,
                            lineHeight = 25.sp, color = Palette.InkCare),
                        modifier = Modifier.weight(1f),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Баюшки", style = TextStyle(fontFamily = AppFonts.hand, fontWeight = FontWeight.Bold, fontSize = 26.sp,
                            color = Color(0xFF2F2479)), modifier = Modifier.rotate(-6f))
                        Box(Modifier.width(92.dp).height(1.dp).background(Palette.Ink))
                        Text("ДЕЖУРНЫЙ", style = monoLabel(10, Palette.InkMuted, 1.0))
                    }
                }
            }
            val stampMod = Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 18.dp)
            when (data?.member?.game?.state) {
                "out" -> Stamp("ВЫБЫЛ", Palette.StampRed, stampMod, -14f, size = 30, alpha = 0.82f)
                "winner" -> Stamp("ВЫСПАЛСЯ", Palette.StampViolet, stampMod, -10f, size = 24)
                else -> {}
            }
        }
    }
}

/** «4:06» — минуты и секунды сессии. */
private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Полоса на 24 часа: ночь подсвечена, экран — серым, приложения — чернилами. */
@Composable
private fun DayBar(d: Timeline, dayStart: Long, dayEnd: Long) {
    val span = (dayEnd - dayStart).toFloat()
    Column {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            fun x(t: Long) = ((t - dayStart) / span).coerceIn(0f, 1f) * size.width
            drawRoundRect(Palette.PaperRule, cornerRadius = CornerRadius(4f, 4f))
            val hour = 3_600_000L
            for ((ns, ne) in d.nights) {
                drawRect(Palette.StampViolet.copy(alpha = 0.15f), topLeft = Offset(x(ns), 0f), size = Size(x(ne) - x(ns), size.height))
            }
            for ((s, e) in d.screens) {
                drawRect(Palette.InkMuted.copy(alpha = 0.45f), topLeft = Offset(x(s), size.height * 0.15f),
                    size = Size((x(e) - x(s)).coerceAtLeast(2f), size.height * 0.7f))
            }
            for (s in d.sessions) {
                val atNight = d.nights.any { (ns, ne) -> s.end > ns && s.start < ne }
                drawRect(if (atNight) Palette.StampRed else Palette.Ink, topLeft = Offset(x(s.start), size.height * 0.3f),
                    size = Size((x(s.end) - x(s.start)).coerceAtLeast(2f), size.height * 0.4f))
            }
            for (h in 0..24 step 3) {
                val xx = x(dayStart + h * hour)
                drawLine(Palette.InkMuted.copy(alpha = 0.4f), Offset(xx, size.height * 0.85f), Offset(xx, size.height))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (h in 0..24 step 6) Text("%02d".format(h), style = monoLabel(11, Palette.InkMuted, 0.0))
        }
    }
}
