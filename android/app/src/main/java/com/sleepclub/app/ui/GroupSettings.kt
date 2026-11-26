package com.sleepclub.app.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepclub.app.Api
import com.sleepclub.app.AppRule
import com.sleepclub.app.GroupInfo
import com.sleepclub.app.GroupSettings
import com.sleepclub.app.MemberRow
import com.sleepclub.app.Prefs
import kotlinx.coroutines.launch

private val DAY_NAMES = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

/** Что спросить перед опасным действием. */
private data class Confirm(val title: String, val text: String, val action: suspend () -> Unit)

@Composable
fun GroupSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val api = remember { Api(prefs.serverUrl, prefs.token) }
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf<GroupInfo?>(null) }
    var draft by remember { mutableStateOf<GroupSettings?>(null) }
    var name by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppRule>?>(null) }
    var members by remember { mutableStateOf<List<MemberRow>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    LaunchedEffect(reload) {
        try {
            val g = api.group()
            group = g
            draft = g.settings
            name = g.name
            members = api.members().members
            apps = api.groupApps()
        } catch (e: Exception) {
            message = "❌ ${e.message}"
        }
    }

    fun launchAction(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                message = "❌ ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { confirm = null; launchAction { c.action(); reload++ } }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }

    val g = group
    val d = draft
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Назад") }
                Text("Настройки группы", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        if (g == null || d == null) {
            item { if (message == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else Text(message!!, color = Palette.Bad) }
            return@LazyColumn
        }

        item {
            SectionCard {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Название группы") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Время", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime(ctx, d.bedtime) { draft = d.copy(bedtime = it) } }) {
                        Text("Отбой ${d.bedtime}")
                    }
                    OutlinedButton(onClick = { pickTime(ctx, d.wake) { draft = d.copy(wake = it) } }) {
                        Text("Подъём ${d.wake}")
                    }
                }
                Text("Ночи, в которые действуют правила (по дню вечера):", color = Palette.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_NAMES.forEachIndexed { i, day ->
                        val on = i in d.days
                        val toggle = { draft = d.copy(days = if (on) d.days - i else d.days + i) }
                        if (on) Button(onClick = toggle, contentPadding = PaddingValues(6.dp), modifier = Modifier.weight(1f)) { Text(day) }
                        else OutlinedButton(onClick = toggle, contentPadding = PaddingValues(6.dp), modifier = Modifier.weight(1f)) { Text(day) }
                    }
                }
            }
        }
        item {
            SectionCard {
                Text("Испытание", fontWeight = FontWeight.Bold)
                Stepper("Длится", d.roundNights, 1, 365, 1, { "$it ${nightsWord(it)}" }) { draft = d.copy(roundNights = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in listOf(7, 14, 30)) {
                        OutlinedButton(onClick = { draft = d.copy(roundNights = n) }) { Text("$n") }
                    }
                }
                Text("Считается от начала раунда. Побеждают все, кто не вылетел до конца.", color = Palette.Muted, fontSize = 13.sp)
                Text("Лимиты после отбоя", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Stepper("Одна сессия, не дольше", d.maxSessionS, 30, 3600, 30, ::dur) { draft = d.copy(maxSessionS = it) }
                Stepper("Все заходы за ночь, не больше", d.nightTotalS, 60, 7200, 60, ::dur) { draft = d.copy(nightTotalS = it) }
                Stepper("Запрещённые приложения — вылет за", d.forbiddenS, 1, 600, 5, ::dur) { draft = d.copy(forbiddenS = it) }
                Stepper("В «Баюшках» не считается", d.ownAppS, 0, 3600, 60, { if (it == 0) "0" else dur(it) }) {
                    draft = d.copy(ownAppS = it)
                }
                Text("Первые минуты за ночь в самих «Баюшках» не считаются, дальше — как обычное время.",
                    color = Palette.Muted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Отключил слежку ночью — вылет", modifier = Modifier.weight(1f))
                    Switch(checked = d.trackingOffEliminates, onCheckedChange = { draft = d.copy(trackingOffEliminates = it) })
                }
            }
        }
        item {
            SectionCard {
                Text("Запрещённые категории", fontWeight = FontWeight.Bold)
                for ((key, title) in g.categories) {
                    val on = key in d.forbiddenCategories
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { draft = d.copy(forbiddenCategories = if (on) d.forbiddenCategories - key else d.forbiddenCategories + key) }) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(title, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text("Звонки и сами «Баюшки» не считаются никогда.", color = Palette.Muted, fontSize = 13.sp)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Приложения участников", fontWeight = FontWeight.Bold)
                Text("Нажми на приложение, чтобы переключить: авто (по категории) → 🚫 запрещено → ✅ можно.",
                    color = Palette.Muted, fontSize = 13.sp)
                if (apps == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (apps!!.isEmpty()) Text("Пока никто ничего не открывал.", color = Palette.Muted)
            }
        }
        items(apps.orEmpty(), key = { it.pkg }) { a -> AppRuleRow(a, d) { draft = it } }
        item {
            message?.let { Text(it) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                launchAction {
                    val r = api.updateGroup(name.trim().takeIf { it.isNotBlank() && it != g.name }, tzOffsetMinutes(), d)
                    group = r
                    prefs.groupName = r.name
                    message = "✅ Сохранено — новые правила уже действуют"
                }
            }) { Text(if (busy) "Сохраняю…" else "Сохранить") }
        }

        item { Text("Участники", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        items(members.filter { !it.isMe }, key = { it.id }) { m ->
            SectionCard {
                Text(m.name + if (m.game.state == "out") " 💀" else "", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        confirm = Confirm("Сделать организатором?", "${m.name} получит все настройки, а ты станешь обычным участником.") {
                            api.memberAction(m.id, "make-owner"); prefs.isOwner = false; onBack()
                        }
                    }) { Text("👑 Передать") }
                    TextButton(onClick = {
                        confirm = Confirm("Разрешить перевход?", "Если ${m.name} переустановил приложение или сменил телефон, " +
                            "он сможет снова войти под этим именем — его история и статус в игре сохранятся.") {
                            api.memberAction(m.id, "allow-rejoin"); message = "✅ ${m.name} может войти заново"
                        }
                    }) { Text("🔁 Перевход") }
                    TextButton(onClick = {
                        confirm = Confirm("Удалить ${m.name}?", "Участник пропадёт из группы, его приложение перестанет работать.") {
                            api.memberAction(m.id, "kick")
                        }
                    }) { Text("Удалить", color = Palette.Bad) }
                }
            }
        }
        item {
            SectionCard {
                Text("Игра", fontWeight = FontWeight.Bold)
                val r = g.round
                Text(
                    "Раунд ${r.no} · " + when {
                        !r.finished -> "прошло ${r.nightsDone} из ${r.nights} ${nightsWord(r.nights)}"
                        r.winners.isEmpty() -> "окончен, победителей нет"
                        else -> "окончен, победители: ${r.winners.joinToString(", ")}"
                    },
                    color = Palette.Muted,
                )
                g.cancelTarget?.let { n ->
                    val zone = java.time.ZoneId.systemDefault()
                    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    val bed = java.time.Instant.ofEpochMilli(n.bedMs).atZone(zone).format(fmt)
                    val started = n.bedMs <= System.currentTimeMillis()
                    if (!n.cancelled) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            confirm = Confirm(if (started) "Закончить отбой сейчас?" else "Отменить отбой в $bed?",
                                "Сегодня ночью правила действовать не будут. Ночь всё равно засчитается в испытание.") {
                                api.cancelNight()
                            }
                        }) { Text(if (started) "🌅 Закончить отбой сейчас" else "🌅 Отменить отбой в $bed") }
                    } else if (!started) {
                        Text("Отбой в $bed отменён.", color = Palette.Moon)
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            confirm = Confirm("Вернуть отбой в $bed?", "Правила снова будут действовать этой ночью.") {
                                api.restoreNight()
                            }
                        }) { Text("🌙 Вернуть отбой") }
                    }
                }
                if (!r.finished) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        confirm = Confirm("Завершить испытание сейчас?",
                            "Все, кто ещё не вылетел, станут победителями. Отменить нельзя, но можно начать новый раунд.") {
                            api.finishRound()
                        }
                    }) { Text("🏁 Завершить испытание") }
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    confirm = Confirm("Начать новый раунд?", "Все вылетевшие вернутся в игру, испытание начнётся заново.") { api.newRound() }
                }) { Text("🔄 Новый раунд") }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    confirm = Confirm("Сменить код группы?", "Старый код перестанет работать. Те, кто уже в группе, останутся.") {
                        prefs.inviteCode = api.newInvite().inviteCode
                    }
                }) { Text("Сменить код (сейчас ${g.inviteCode})") }
            }
        }
    }
}

@Composable
private fun AppRuleRow(a: AppRule, d: GroupSettings, onChange: (GroupSettings) -> Unit) {
    val mode = when {
        a.locked -> "locked"
        a.pkg in d.allowedApps -> "allowed"
        a.pkg in d.forbiddenApps -> "forbidden"
        else -> "auto"
    }
    val autoForbidden = a.categories.any { it in d.forbiddenCategories }
    val label = when (mode) {
        "locked" -> "✅ всегда можно"
        "allowed" -> "✅ можно"
        "forbidden" -> "🚫 запрещено"
        else -> if (autoForbidden) "авто: 🚫" else "авто: можно"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = !a.locked) {
            onChange(
                when (mode) {
                    "auto" -> d.copy(forbiddenApps = d.forbiddenApps + a.pkg, allowedApps = d.allowedApps - a.pkg)
                    "forbidden" -> d.copy(forbiddenApps = d.forbiddenApps - a.pkg, allowedApps = d.allowedApps + a.pkg)
                    else -> d.copy(forbiddenApps = d.forbiddenApps - a.pkg, allowedApps = d.allowedApps - a.pkg)
                }
            )
        }.padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.name)
            Text(a.pkg, color = Palette.Muted, fontSize = 11.sp)
        }
        Text(label, color = if (label.contains("🚫")) Palette.Bad else Palette.Good)
    }
}

@Composable
private fun Stepper(title: String, value: Int, min: Int, max: Int, step: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(44.dp)) { Text("−") }
        Text(fmt(value), fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp).padding(horizontal = 4.dp))
        OutlinedButton(onClick = { onChange(if (value == 1 && step > 1) step else (value + step).coerceAtMost(max)) },
            contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("+") }
    }
}

private fun nightsWord(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "ночь"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "ночи"
    else -> "ночей"
}

private fun dur(sec: Int): String = when {
    sec < 60 -> "$sec с"
    sec % 60 == 0 -> "${sec / 60} мин"
    else -> "${sec / 60} мин ${sec % 60} с"
}

private fun pickTime(ctx: Context, current: String, onPicked: (String) -> Unit) {
    val (h, m) = current.split(":").map { it.toInt() }
    TimePickerDialog(ctx, { _, hh, mm -> onPicked("%02d:%02d".format(hh, mm)) }, h, m, true).show()
}
