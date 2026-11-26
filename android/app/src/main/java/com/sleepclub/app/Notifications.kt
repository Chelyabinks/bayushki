package com.sleepclub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_ALERTS = "alerts"
    const val CH_UPDATES = "updates"
    const val CH_NEWS = "news"
    const val ID_SERVICE = 1
    const val ID_ALERT = 2
    const val ID_UPDATE = 3
    const val ID_NEWS = 4
    const val ID_ELIMINATED = 5

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, "Фоновая работа", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Тихое постоянное уведомление: без него Android выгружает приложение"
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERTS, "Проблемы", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Если слежка перестала работать и нужно что-то включить"
                },
                NotificationChannel(CH_UPDATES, "Обновления", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_NEWS, "Новости группы", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Кто вылетел и кто победил. Приходят только после подъёма"
                },
            )
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun serviceNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Баюшки")
            .setContentText("Работаю тихо: отправляю данные, только пока экран включён")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

    /** Утренние новости: «💀 Вася вылетел в 01:12…». Ночью не показываем ничего. */
    fun showNews(ctx: Context, lines: List<String>) = notify(ctx, ID_NEWS,
        NotificationCompat.Builder(ctx, CH_NEWS)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(if (lines.size == 1) "Новости группы" else "Новости группы: ${lines.size}")
            .setContentText(lines.last())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.takeLast(8).joinToString("\n")))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build())

    /** О своём вылете человек узнаёт сразу, даже ночью: он уже не в игре, провоцировать нечего. */
    fun showEliminated(ctx: Context, reason: String?) = notify(ctx, ID_ELIMINATED,
        NotificationCompat.Builder(ctx, CH_NEWS)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("💀 Ты вылетел из игры")
            .setContentText(reason ?: "Нарушение правил отбоя")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason ?: "Нарушение правил отбоя"))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build(), force = true)

    fun showProblem(ctx: Context, text: String) = notify(ctx, ID_ALERT,
        NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("❌ Слежка сломалась — нажми, чтобы исправить")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build())

    fun cancelProblem(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_ALERT)

    fun showUpdate(ctx: Context, version: String) = notify(ctx, ID_UPDATE,
        NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Доступно обновление $version")
            .setContentText("Открой приложение и нажми «Обновить»")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build())

    private fun notify(ctx: Context, id: Int, n: Notification, force: Boolean = false) {
        // Ночью приложение молчит: ничто не должно звать в телефон после отбоя.
        if (!force && Prefs(ctx).isNight()) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
            // уведомления запрещены — покажем проблему в самом приложении
        }
    }
}
