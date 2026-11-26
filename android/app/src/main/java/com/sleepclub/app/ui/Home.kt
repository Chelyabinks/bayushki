package com.sleepclub.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepclub.app.Api
import com.sleepclub.app.ApiException
import com.sleepclub.app.BuildConfig
import com.sleepclub.app.Check
import com.sleepclub.app.CheckId
import com.sleepclub.app.GroupInfo
import com.sleepclub.app.Health
import com.sleepclub.app.MemberRow
import com.sleepclub.app.MembersInfo
import com.sleepclub.app.Oem
import com.sleepclub.app.Prefs
import com.sleepclub.app.RoundInfo
import com.sleepclub.app.SyncManager
import com.sleepclub.app.TrackerService
import com.sleepclub.app.Updater
import com.sleepclub.core.Texts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun HomeScreen(
    resumeTick: Int,
    onOpen: (Long, String, Int) -> Unit,
    onSettings: () -> Unit,
    onLeft: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<MembersInfo?>(null) }
    var group by remember { mutableStateOf<GroupInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var checks by remember { mutableStateOf(Health.checks(ctx)) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    // Данные — раз в 30 секунд, пока экран открыт.
    LaunchedEffect(resumeTick, refreshKey) {
        while (true) {
            checks = Health.checks(ctx)
            try {
                val api = Api(prefs.serverUrl, prefs.token)
                group = api.group().also {
                    prefs.isOwner = it.isOwner
                    prefs.inviteCode = it.inviteCode
                    prefs.groupName = it.name
                    it.bedMs?.let { b -> prefs.nightBedMs = b }
                    it.wakeMs?.let { w -> prefs.nightWakeMs = w }
                }
                info = api.members()
                loadError = null
            } catch (e: ApiException) {
                loadError = if (e.code == 401) "Тебя нет в группе: организатор удалил тебя или ты вошёл с другого телефона"
                else e.message
            } catch (e: Exception) {
                loadError = e.message ?: "Нет связи с сервером"
            }
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    // Часы для обратного отсчёта — каждую секунду.
    LaunchedEffect(Unit) {
        while (true) {
            clock = System.currentTimeMillis()
            delay(1_000 - clock % 1_000)
        }
    }
    LaunchedEffect(Unit) { // раз за открытие приложения; фоновая проверка — раз в сутки
        release = runCatching { Updater.fetchLatest() }.getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Выйти из группы?") },
            text = { Text("Слежка остановится, друзья перестанут тебя видеть. Вернуться можно по коду, " +
                "но это будет как новый участник.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        runCatching { Api(prefs.serverUrl, prefs.token).leave() }
                        onLeft()
                    }
                }) { Text("Выйти", color = Palette.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Остаться") } },
        )
    }

    val zone = ZoneId.systemDefault()
    val round = group?.round
    val groupName = group?.name ?: prefs.groupName ?: "Баюшки"
    val night = info?.inNight ?: prefs.isNight()

    if (night) {
        val wake = prefs.nightWakeMs.takeIf { it > clock }
        NightScreen(
            clock = HM.format(Instant.ofEpochMilli(clock).atZone(zone)),
            groupName = groupName,
            nightLabel = round?.let { "НОЧЬ ${currentNight(it)} / ${it.nights}" } ?: "",
            wakeTime = wake?.let { Texts.time(it, zone) },
            endsIn = wake?.let { hms(it - clock, withSeconds = false) },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Palette.Amber.copy(alpha = 0.14f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(540f, -120f), radius = 1100f,
            ),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Header(
                groupName = groupName,
                g = group,
                clock = clock,
                checks = checks,
                prefs = prefs,
                onSyncNow = {
                    scope.launch {
                        SyncManager.sync(ctx, "manual")
                        checks = Health.checks(ctx)
                        refreshKey++
                    }
                },
            )
        }
        release?.let { r -> item { UpdateCard(ctx, r) } }
        loadError?.let { err ->
            item {
                SectionCard {
                    Text(err, color = Palette.Bad)
                    if (err.startsWith("Тебя нет в группе")) Button(onClick = { onLeft() }) { Text("Войти заново") }
                }
            }
        }
        round?.takeIf { it.finished }?.let { r -> item { FinishedCard(r, group?.isOwner == true) } }

        val list = info
        when {
            list == null && loadError == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Amber) }
            list != null -> {
                val alive = list.members.count { it.game.state != "out" }
                item {
                    SpacedRow(
                        left = { Text("ЛИЧНЫЙ СОСТАВ", style = TextStyle(fontFamily = AppFonts.display, fontSize = 18.sp)) },
                        right = { Text("в строю $alive из ${list.members.size}", style = monoLabel(12, spacing = 0.0)) },
                    )
                }
                itemsIndexed(list.members, key = { _, m -> m.id }) { i, m ->
                    MemberCard(m, i + 1, now, zone) { onOpen(m.id, m.name, i + 1) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("покинуть группу", style = monoLabel(11, spacing = 0.0),
                    modifier = Modifier.clickable { confirmLeave = true }.padding(vertical = 10.dp))
            }
        }
        group?.let { g -> item { GroupCard(ctx, g, onSettings) } }
    }
}

private fun currentNight(r: RoundInfo) = minOf(r.nightsDone + 1, r.nights)

/** «00:48:52» или «5:13». */
private fun hms(ms: Long, withSeconds: Boolean): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (withSeconds) "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%d:%02d".format(s / 3600, s % 3600 / 60)
}

@Composable
private fun Header(groupName: String, g: GroupInfo?, clock: Long, checks: List<Check>, prefs: Prefs, onSyncNow: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("«$groupName»", style = TextStyle(fontFamily = AppFonts.display, fontSize = 22.sp, color = Palette.TextSoft))

        if (g != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ОТБОЙ С", style = monoLabel())
                Text(g.settings.bedtime, style = TextStyle(fontFamily = AppFonts.display, fontSize = 76.sp, lineHeight = 78.sp,
                    letterSpacing = (-1.5).sp, color = Palette.Text))
                Text("ДО ${g.settings.wake}", style = monoLabel(12, spacing = 1.0), modifier = Modifier.padding(top = 4.dp))
            }

            val bed = g.bedMs
            if (!g.round.finished && bed != null && bed > clock) {
                Row(
                    Modifier.border(1.dp, Palette.AmberLine, RoundedCornerShape(50))
                        .background(Palette.Amber.copy(alpha = 0.08f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("ДО ОТБОЯ", style = monoLabel(12, Palette.AmberDim, 1.0))
                    Text(hms(bed - clock, withSeconds = true), style = TextStyle(fontFamily = AppFonts.mono, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = Palette.Amber))
                }
            }

            if (!g.round.finished) {
                val r = g.round
                val cur = currentNight(r)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tally(r.nightsDone, r.nights, Modifier.width(minOf(tallyWidth(r.nights), 190.dp)))
                    Column {
                        Text("НОЧЬ", style = monoLabel())
                        Text("$cur / ${r.nights}", style = TextStyle(fontFamily = AppFonts.display, fontSize = 26.sp, lineHeight = 28.sp))
                        Text(nightsLeftText(r.nights - cur), style = TextStyle(fontFamily = AppFonts.hand,
                            fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 22.sp, color = Palette.Amber))
                    }
                }
            }
        }

        HealthStrip(ctx, prefs, checks, onSyncNow)
    }
}

/** «Слежка работает» с раскрывающимся списком проверок. */
@Composable
private fun HealthStrip(ctx: Context, prefs: Prefs, checks: List<Check>, onSyncNow: () -> Unit) {
    val bad = checks.filter { !it.ok }
    var expanded by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val open = expanded || bad.isNotEmpty()
    Column(Modifier.fillMaxWidth().dashedBorder().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val dot = if (bad.isEmpty()) Palette.Alive else Palette.Bad
            Box(Modifier.size(8.dp).shadow(6.dp, CircleShape, ambientColor = Palette.Amber, spotColor = Palette.Amber)
                .background(dot, CircleShape))
            val last = prefs.lastSyncOkAt
            Text(
                if (bad.isEmpty()) "СЛЕЖКА РАБОТАЕТ" + (if (last > 0) " · ${Texts.dateTime(last, System.currentTimeMillis(), zone)}" else "")
                else "СЛЕЖКА СЛОМАЛАСЬ",
                style = monoLabel(12, if (bad.isEmpty()) Palette.TextSoft else Palette.Bad, 1.0),
                modifier = Modifier.weight(1f),
            )
            if (bad.isEmpty()) {
                Text(if (expanded) "скрыть" else "подробнее", style = TextStyle(fontFamily = AppFonts.mono, fontSize = 12.sp, color = Palette.Amber),
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 12.dp))
            } else {
                Spacer(Modifier.height(40.dp))
            }
        }
        if (open) {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bad.isNotEmpty()) Text("Друзья видят у тебя «нет связи». Почини, это быстро:", color = Palette.TextSoft, fontSize = 14.sp)
                for (c in checks) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (c.ok) "✓ " else "✗ ") + c.title, fontSize = 14.sp, color = if (c.ok) Palette.TextSoft else Palette.Bad)
                            if (!c.ok) Text(c.problem, color = Palette.Muted, fontSize = 13.sp)
                        }
                        if (!c.ok) Button(onClick = { fix(ctx, prefs, c.id, onSyncNow) }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Исправить")
                        }
                    }
                }
                OutlinedButton(onClick = onSyncNow) { Text("Отправить сейчас") }
            }
        }
    }
}

@Composable
private fun MemberCard(m: MemberRow, no: Int, now: Long, zone: ZoneId, onClick: () -> Unit) {
    val out = m.game.state == "out"
    val curfew = m.status.state == "curfew"
    var showTz by remember { mutableStateOf(false) }
    val myOffset = zone.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds / 60
    val offset = m.tzOffsetMin ?: myOffset
    val theirZone = ZoneOffset.ofTotalSeconds(offset * 60)
    val theirTime = Instant.ofEpochMilli(now).atOffset(theirZone).format(HM)

    if (showTz) {
        val diff = offset - myOffset
        val diffText = when {
            diff == 0 -> "Время у вас совпадает."
            diff > 0 -> "У него на ${Texts.duration(diff * 60_000L)} больше, чем у тебя."
            else -> "У него на ${Texts.duration(-diff * 60_000L)} меньше, чем у тебя."
        }
        AlertDialog(
            onDismissRequest = { showTz = false },
            title = { Text("${m.name}: сейчас $theirTime") },
            text = {
                Text("Часовой пояс: ${m.tzId ?: "неизвестно"} (${Texts.utcLabel(offset)}).\n$diffText\n\n" +
                    "Отбой и подъём у каждого наступают по его местному времени.")
            },
            confirmButton = { TextButton(onClick = { showTz = false }) { Text("Понятно") } },
        )
    }

    val paper = when {
        out -> Palette.PaperOut
        curfew -> Palette.PaperSleep
        else -> Palette.Paper
    }
    val hand = TextStyle(fontFamily = AppFonts.hand, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 22.sp, color = Palette.InkCare)
    PaperCard(paper, Modifier.clickable(onClick = onClick)) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("№ %02d".format(no), style = monoLabel(11, Palette.InkMuted, 0.0))
                if (m.isOwner) WhistleIcon(modifier = Modifier.padding(start = 6.dp))
                Spacer(Modifier.weight(1f))
                Text("$theirTime · ${Texts.utcLabel(offset)}", style = monoLabel(11, Palette.InkMuted, 0.0),
                    modifier = Modifier.clickable { showTz = true }.padding(vertical = 4.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.PaperLine))
            Column(Modifier.padding(top = 8.dp, end = 76.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        m.name.uppercase(),
                        style = TextStyle(fontFamily = AppFonts.display, fontSize = 26.sp, lineHeight = 28.sp,
                            color = if (out) Color(0xFF4A453A) else Palette.Ink,
                            textDecoration = if (out) TextDecoration.LineThrough else null),
                    )
                    if (m.isMe) Text("это ты", style = hand.copy(fontSize = 20.sp))
                }
                when {
                    out -> {
                        Text("вылетел ${m.game.at?.let { Texts.dateTime(it, now, theirZone) } ?: ""}: ${m.game.reason ?: ""}",
                            fontSize = 14.sp, color = Color(0xFF2E2A22), modifier = Modifier.padding(top = 6.dp))
                        Text("бывает. в следующий раз выспишься", style = hand.copy(fontSize = 20.sp))
                    }
                    curfew -> {
                        val day = Instant.ofEpochMilli(now).atOffset(theirZone).minusHours(12).toLocalDate().toEpochDay()
                        Text("спит. ${Texts.curfewJoke(m.id, day)}", style = hand, modifier = Modifier.padding(top = 4.dp))
                    }
                    else -> Text(
                        Texts.statusLine(m.status, now, zone), fontSize = 14.sp,
                        color = if (m.status.state == "stale") Palette.StampRed else Palette.InkSoft,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        val tilt = listOf(-9f, 7f, -13f, -5f, 6f)[(m.id % 5).toInt()]
        val stampMod = Modifier.align(Alignment.TopEnd).padding(top = 30.dp)
        when {
            m.game.state == "winner" -> Stamp("ВЫСПАЛСЯ", Palette.StampViolet, stampMod, tilt)
            out -> Stamp("НАРУШИЛ\nРЕЖИМ", Palette.StampRed, stampMod, -13f, alpha = 0.9f)
            curfew -> Stamp("ОТБОЙ", Palette.Ink, stampMod, 7f, alpha = 0.7f)
            m.status.state == "stale" -> Stamp("НЕТ\nСВЯЗИ", Palette.StampRed, stampMod, tilt)
            m.status.state == "untracked" -> {}
            else -> Stamp("В СТРОЮ", Palette.StampViolet, stampMod, tilt)
        }
    }
}

@Composable
private fun FinishedCard(r: RoundInfo, isOwner: Boolean) {
    PaperCard(Palette.PaperSheet) {
        Column(Modifier.padding(end = 90.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("РАУНД ${r.no} ОКОНЧЕН", style = monoLabel(11, Palette.InkMuted))
            Text(
                when {
                    r.winners.isEmpty() -> "Победителей нет: вылетели все."
                    r.winners.size == 1 -> "Победитель: ${r.winners[0]}"
                    else -> "Победители: ${r.winners.joinToString(", ")}"
                },
                style = TextStyle(fontFamily = AppFonts.display, fontSize = 20.sp, lineHeight = 24.sp, color = Palette.Ink),
            )
            if (isOwner) Text("Новый раунд можно начать в настройках группы.", fontSize = 14.sp, color = Palette.InkSoft)
        }
        if (r.winners.isNotEmpty()) {
            Stamp("ВЫСПАЛСЯ", Palette.StampViolet, Modifier.align(Alignment.TopEnd).padding(top = 8.dp), -11f)
        }
    }
}

@Composable
private fun GroupCard(ctx: Context, g: GroupInfo, onSettings: () -> Unit) {
    var showRules by remember { mutableStateOf(false) }
    SectionCard {
        Column(Modifier.fillMaxWidth().clickable { copyCode(ctx, g.inviteCode) }) {
            Text("КОД ГРУППЫ ·\nНАЖМИ, ЧТОБЫ СКОПИРОВАТЬ", style = monoLabel())
            Text(g.inviteCode, style = TextStyle(fontFamily = AppFonts.display, fontSize = 32.sp, letterSpacing = 6.sp),
                modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).dashedLine())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { showRules = !showRules }, modifier = Modifier.weight(1f)) {
                Text(if (showRules) "Скрыть устав" else "Устав")
            }
            if (g.isOwner) OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("Настройки группы") }
        }
        if (showRules) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                g.ownerName?.let { Text("Организатор: $it", color = Palette.Muted, fontSize = 13.sp) }
                for (r in g.rules) Text("• $r", fontSize = 14.sp, color = Palette.TextSoft)
            }
        }
    }
}

private fun fix(ctx: Context, prefs: Prefs, id: CheckId, onSyncNow: () -> Unit) {
    when (id) {
        CheckId.USAGE -> openUsageSettings(ctx)
        CheckId.NOTIFICATIONS -> ctx.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        CheckId.BATTERY -> ctx.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}")))
        CheckId.OEM -> { Oem.openSettings(ctx); prefs.oemConfirmed = true }
        CheckId.SERVICE -> { TrackerService.ensureRunning(ctx); onSyncNow() }
        CheckId.SYNC -> onSyncNow()
    }
}

@Composable
private fun UpdateCard(ctx: Context, r: Updater.Release) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard {
        Text("Вышла версия ${r.tag}", style = TextStyle(fontFamily = AppFonts.display, fontSize = 18.sp))
        if (r.notes.isNotBlank()) Text(r.notes.take(300), color = Palette.Muted, fontSize = 13.sp)
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Palette.Amber) }
        error?.let { Text(it, color = Palette.Bad) }
        Button(enabled = progress == null || apk != null, onClick = {
            val file = apk
            when {
                !Updater.canInstall(ctx) -> Updater.openInstallPermission(ctx)
                file != null -> Updater.install(ctx, file)
                else -> scope.launch {
                    try {
                        progress = 0f
                        val f = Updater.download(ctx, r) { p -> progress = p }
                        apk = f
                        Updater.install(ctx, f)
                    } catch (e: Exception) {
                        error = "Не скачалось: ${e.message}"
                        progress = null
                    }
                }
            }
        }) { Text(if (!Updater.canInstall(ctx)) "Разрешить установку" else if (apk != null) "Установить" else "Обновить") }
    }
}
