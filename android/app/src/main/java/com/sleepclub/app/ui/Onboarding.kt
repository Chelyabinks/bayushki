package com.sleepclub.app.ui

import androidx.compose.foundation.clickable
import com.sleepclub.app.BuildConfig
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepclub.app.Api
import com.sleepclub.app.Health
import com.sleepclub.app.JoinResult
import com.sleepclub.app.Oem
import com.sleepclub.app.Prefs
import kotlinx.coroutines.launch

private const val STEPS = 6

@Composable
fun OnboardingScreen(resumeTick: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    // Начинаем с первого невыполненного шага (если мастер прервали на середине).
    var step by rememberSaveable {
        mutableIntStateOf(
            when {
                !prefs.consentGiven -> 0
                !prefs.isRegistered -> 1
                else -> 2
            }
        )
    }
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Шаг ${step + 1} из $STEPS", color = Palette.Muted)
        LinearProgressIndicator(progress = { (step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
        val next: () -> Unit = { if (step < STEPS - 1) step++ else onDone() }
        // resumeTick читаем, чтобы шаги перепроверялись после возврата из настроек
        key(resumeTick) {
            when (step) {
                0 -> ConsentStep { prefs.consentGiven = true; next() }
                1 -> RegisterStep(prefs, next)
                2 -> UsageStep(ctx, next)
                3 -> NotificationsStep(ctx, next)
                4 -> BatteryStep(ctx, next)
                else -> OemStep(ctx, prefs, next)
            }
        }
    }
}

@Composable
private fun Header(emoji: String, title: String) {
    Text(emoji, fontSize = 48.sp)
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusRow(ok: Boolean, okText: String, badText: String) {
    Text(if (ok) "✅ $okText" else "❌ $badText", color = if (ok) Palette.Good else Palette.Bad)
}

@Composable
private fun ConsentStep(onAgree: () -> Unit) {
    Header("🌙", "Баюшки: челлендж «ложимся вовремя»")
    SectionCard {
        Text("Как это работает:", fontWeight = FontWeight.Bold)
        Text("• организатор группы задаёт отбой и подъём;")
        Text("• после отбоя нельзя долго сидеть в телефоне и нельзя открывать запрещённые приложения " +
            "(мессенджеры, соцсети, видео, игры);")
        Text("• нарушил — вылетел из игры. Испытание длится несколько ночей (по умолчанию 7), побеждают все, кто продержался до конца;")
        Text("• открывать «Баюшки» не нужно: всё считается само, пока приложение установлено.")
    }
    SectionCard {
        Text("Что увидят твои друзья из группы:", fontWeight = FontWeight.Bold)
        Text("• когда у тебя был включён экран телефона (по минутам);")
        Text("• какие приложения ты открывал и сколько в них сидел: «02:14–02:31 · TikTok · 17 мин» — " +
            "за 40 минут до отбоя сразу, а что было ночью — после подъёма. Днём видно лишь «в телефоне или нет»;")
        Text("• твоё местное время и часовой пояс (отбой у каждого наступает по его времени);")
        Text("• что ты делаешь прямо сейчас: «сейчас в телефоне, 12 мин» или «телефон отложен в 00:42»;")
        Text("• когда ты вылетел (или раунд закончился), за тобой больше не следят до нового раунда.")
    }
    SectionCard {
        Text("Что НЕ собирается:", fontWeight = FontWeight.Bold)
        Text("переписки, содержимое экрана, фото, местоположение, контакты, пароли. Только названия приложений и время.")
        Text("Данные хранятся на сервере организатора и видны только участникам твоей группы. " +
            "Своя страница в приложении показывает ровно то, что видят другие.", color = Palette.Muted)
    }
    Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) { Text("Понятно, я согласен") }
}

@Composable
private fun RegisterStep(prefs: Prefs, next: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var invite by rememberSaveable { mutableStateOf("") }
    var groupName by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(prefs.myName ?: "") }
    var server by rememberSaveable { mutableStateOf(prefs.serverUrl) }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<JoinResult?>(null) }

    created?.let { r ->
        Header("🎉", "Группа «${r.groupName}» создана")
        Text("Ты организатор. Отправь друзьям этот код — по нему они войдут в группу:")
        SectionCard(modifier = Modifier.clickable { copyCode(ctx, r.inviteCode) }) {
            Text(r.inviteCode, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("Нажми, чтобы скопировать", color = Palette.Muted, fontSize = 13.sp)
        }
        OutlinedButton(onClick = { shareInvite(ctx, r.groupName, r.inviteCode) }, modifier = Modifier.fillMaxWidth()) {
            Text("Поделиться кодом")
        }
        Text("Отбой по умолчанию 00:30, подъём 06:00. Поменять время и правила можно потом: " +
            "главный экран → «Настройки группы».", color = Palette.Muted)
        Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Дальше") }
        return
    }

    Header(if (creating) "🛠️" else "🎟️", if (creating) "Новая группа" else "Вход в группу")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val pick = @Composable { value: Boolean, label: String ->
            if (creating == value) Button(onClick = {}) { Text(label) }
            else OutlinedButton(onClick = { creating = value; error = null }) { Text(label) }
        }
        pick(false, "Войти по коду")
        pick(true, "Создать группу")
    }
    if (creating) {
        Text("Ты станешь организатором: задашь отбой, правила и получишь код для друзей.", color = Palette.Muted)
        OutlinedTextField(
            value = groupName, onValueChange = { groupName = it.take(40) },
            label = { Text("Название группы") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    } else {
        Text("Код группы (буквы и цифры) даёт организатор.", color = Palette.Muted)
        OutlinedTextField(
            value = invite, onValueChange = { v -> invite = v.uppercase().filter { it.isLetterOrDigit() }.take(16) },
            label = { Text("Код группы") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
    }
    OutlinedTextField(
        value = name, onValueChange = { name = it.take(32) },
        label = { Text("Твоё имя (так тебя увидят друзья)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )
    // Адрес сервера нужен только при проверке на эмуляторе — в сборке для друзей кнопки нет.
    if (BuildConfig.DEBUG) {
        TextButton(onClick = { showServer = !showServer }) { Text(if (showServer) "Скрыть адрес сервера" else "Адрес сервера…") }
    }
    if (showServer) {
        OutlinedTextField(value = server, onValueChange = { server = it.trim() },
            label = { Text("Адрес сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Для эмулятора на том же ПК: http://10.0.2.2:8787", color = Palette.Muted, fontSize = 13.sp)
    }
    error?.let { Text("❌ $it", color = Palette.Bad) }
    val ready = name.isNotBlank() && if (creating) groupName.isNotBlank() else invite.length >= 4
    Button(
        enabled = !busy && ready,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val api = Api(server, null)
                    val r = if (creating) api.createGroup(groupName.trim(), name.trim(), tzOffsetMinutes())
                    else api.join(invite, name.trim())
                    prefs.serverUrl = server
                    prefs.saveRegistration(r)
                    prefs.setupPending = creating
                    if (creating) created = r else next()
                } catch (e: Exception) {
                    error = e.message ?: "Не удалось связаться с сервером"
                } finally {
                    busy = false
                }
            }
        },
    ) { Text(if (busy) "Подключаюсь…" else if (creating) "Создать" else "Войти") }
}

/** Смещение часового пояса телефона от UTC в минутах (Москва: 180). */
fun tzOffsetMinutes(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

fun copyCode(ctx: Context, code: String) {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Код группы", code))
    // Android 13+ сам показывает, что скопировано; на старых — подсказка.
    if (android.os.Build.VERSION.SDK_INT < 33) {
        android.widget.Toast.makeText(ctx, "Код $code скопирован", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun shareInvite(ctx: Context, group: String, code: String) {
    val text = "Залетай в группу «$group» в Баюшки — посмотрим, кто дольше продержится без телефона после отбоя 😴\n" +
        "Код группы: $code"
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Поделиться кодом"))
}

@Composable
private fun UsageStep(ctx: Context, next: () -> Unit) {
    val ok = Health.hasUsageAccess(ctx)
    Header("📊", "Доступ к статистике")
    Text("Android сам ведёт журнал: какое приложение и когда было на экране. " +
        "Чтобы его читать, нужно одно разрешение. В открывшемся списке найди «Баюшки» и включи переключатель.")
    StatusRow(ok, "Доступ есть", "Доступа пока нет")
    if (!ok) Button(onClick = { openUsageSettings(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки") }
    Button(onClick = next, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Дальше") }
}

@Composable
private fun NotificationsStep(ctx: Context, next: () -> Unit) {
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true }
    val ok = Health.notificationsOk(ctx)
    Header("🔔", "Уведомления")
    Text("Приложение покажет одно тихое уведомление «работаю в фоне» — так Android не выгружает его из памяти. " +
        "А если что-то сломается, придёт уведомление с кнопкой «Исправить».")
    StatusRow(ok, "Уведомления разрешены", "Уведомления выключены")
    if (!ok) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !asked) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }) { Text("Разрешить") }
    }
    Button(onClick = next, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Дальше") }
}

@Composable
private fun BatteryStep(ctx: Context, next: () -> Unit) {
    val ok = Health.batteryOk(ctx)
    Header("🔋", "Батарея: без ограничений")
    Text("Иначе Android «усыпляет» приложение, и данные приходят с опозданием на часы. " +
        "Расход от этого не вырастет: пока экран выключен, приложение ничего не делает.")
    StatusRow(ok, "Ограничений нет", "Android может усыплять приложение")
    if (!ok) Button(modifier = Modifier.fillMaxWidth(), onClick = {
        try {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }) { Text("Разрешить") }
    Button(onClick = next, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Дальше") }
}

@Composable
private fun OemStep(ctx: Context, prefs: Prefs, next: () -> Unit) {
    var confirmed by remember { mutableStateOf(prefs.oemConfirmed) }
    var fallback by remember { mutableStateOf(false) }
    Header("🚀", "Автозапуск и работа в фоне")
    if (!Oem.needsSetup()) {
        Text("У тебя ${Oem.brandName()} — на таких телефонах дополнительных настроек обычно не нужно. 👍")
        Button(onClick = { prefs.oemConfirmed = true; next() }, modifier = Modifier.fillMaxWidth()) { Text("Готово!") }
        return
    }
    Text("${Oem.brandName()} любит закрывать приложения в фоне. Сделай так:")
    SectionCard { Text(Oem.instructions()) }
    if (fallback) Text("Нужный экран не открылся автоматически — открыл настройки приложения, дальше по инструкции выше.",
        color = Palette.Muted, fontSize = 13.sp)
    OutlinedButton(onClick = { fallback = !Oem.openSettings(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it; prefs.oemConfirmed = it })
        Text("Я всё сделал")
    }
    Spacer(Modifier.height(4.dp))
    Button(onClick = next, enabled = confirmed, modifier = Modifier.fillMaxWidth()) { Text("Готово!") }
}

fun openUsageSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    } catch (_: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}
