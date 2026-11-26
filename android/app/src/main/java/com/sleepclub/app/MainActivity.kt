package com.sleepclub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sleepclub.app.ui.GroupSettingsScreen
import com.sleepclub.app.ui.HomeScreen
import com.sleepclub.app.ui.MemberScreen
import com.sleepclub.app.ui.OnboardingScreen
import com.sleepclub.app.ui.Palette
import com.sleepclub.app.ui.SleepTheme

class MainActivity : ComponentActivity() {
    /** Увеличивается при каждом возврате в приложение — экраны перепроверяют статусы. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        com.sleepclub.app.ui.AppFonts.init(assets)
        setContent {
            SleepTheme {
                Box(Modifier.fillMaxSize().background(Palette.Bg).safeDrawingPadding()) {
                    var onboarded by remember { mutableStateOf(prefs.onboardingDone) }
                    var openedMember by remember { mutableStateOf<Triple<Long, String, Int>?>(null) }
                    var settingsOpen by remember { mutableStateOf(false) }
                    val tick = resumeTick.intValue
                    when {
                        !onboarded -> OnboardingScreen(tick) {
                            prefs.onboardingDone = true
                            onboarded = true
                            // Организатор новой группы сразу попадает туда, где задают отбой и подъём.
                            if (prefs.setupPending) {
                                prefs.setupPending = false
                                settingsOpen = true
                            }
                            TrackerService.ensureRunning(this@MainActivity)
                            SyncWorker.schedulePeriodic(this@MainActivity)
                            SyncWorker.syncSoon(this@MainActivity, "onboarding")
                        }
                        openedMember != null -> {
                            BackHandler { openedMember = null }
                            val (id, name, no) = openedMember!!
                            MemberScreen(id, name, no, tick) { openedMember = null }
                        }
                        settingsOpen -> {
                            BackHandler { settingsOpen = false }
                            GroupSettingsScreen { settingsOpen = false }
                        }
                        else -> HomeScreen(
                            tick,
                            onOpen = { id, name, no -> openedMember = Triple(id, name, no) },
                            onSettings = { settingsOpen = true },
                            onLeft = {
                                prefs.clearRegistration()
                                stopService(android.content.Intent(this@MainActivity, TrackerService::class.java))
                                onboarded = false
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        if (Prefs(this).onboardingDone) {
            TrackerService.ensureRunning(this) // на переднем плане запуск сервиса всегда разрешён
            Health.notifyIfBroken(this)
        }
    }
}
