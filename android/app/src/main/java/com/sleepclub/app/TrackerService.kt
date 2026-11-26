package com.sleepclub.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Тихий сервис. Сам по себе ничего не делает: только слушает «экран включён/выключен».
 *  - экран включён  → через минуту первая отправка, дальше раз в 5 минут
 *    (после отбоя — раз в минуту, чтобы нарушение было видно сразу; экран всё равно горит)
 *    (процессор и так не спит, пока горит экран — никаких wakelock'ов не нужно);
 *  - экран выключен → таймер остановлен, одна отправка через WorkManager, дальше тишина.
 */
class TrackerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startTicking()
                Intent.ACTION_SCREEN_OFF -> {
                    stopTicking()
                    SyncWorker.syncSoon(applicationContext, "screen_off")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!Prefs(this).tracking) {
            // Выбыл или раунд окончен: не следим. Служба вернётся, когда начнётся новый раунд.
            stopSelf()
            return
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.serviceNotification(this), type)
        running = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (getSystemService(PowerManager::class.java).isInteractive) startTicking()
        Log.i(TAG, "сервис запущен")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            delay(FIRST_DELAY_MS) // быстрый взгляд на часы не стоит отдельной отправки
            while (isActive) {
                SyncManager.sync(applicationContext, "tick")
                delay(if (Prefs(applicationContext).isNight()) NIGHT_INTERVAL_MS else INTERVAL_MS)
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    companion object {
        private const val TAG = "SleepService"
        private const val FIRST_DELAY_MS = 60_000L
        private const val INTERVAL_MS = 5 * 60_000L
        private const val NIGHT_INTERVAL_MS = 60_000L
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackerService::class.java))
        }

        @Volatile
        var running = false
            private set

        fun ensureRunning(ctx: Context) {
            if (running || !Prefs(ctx).onboardingDone || !Prefs(ctx).tracking) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TrackerService::class.java))
            } catch (e: Exception) {
                // Android 12+ запрещает старт из фона, если приложение не исключено из оптимизации батареи.
                Log.w(TAG, "не удалось запустить сервис: ${e.message}")
            }
        }
    }
}
