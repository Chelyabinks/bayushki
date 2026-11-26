package com.sleepclub.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Настройки «автозапуска» у разных производителей. Пути к экранам меняются от версии к версии,
 * поэтому пробуем несколько вариантов по очереди, а если ничего не открылось —
 * показываем текстовую инструкцию (по мотивам dontkillmyapp.com) и открываем «О приложении».
 */
object Oem {
    enum class Family { XIAOMI, SAMSUNG, HUAWEI, HONOR, OPPO, VIVO, ONEPLUS, OTHER }

    fun family(): Family {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco").any { it in m } -> Family.XIAOMI
            "samsung" in m -> Family.SAMSUNG
            "honor" in m -> Family.HONOR
            "huawei" in m -> Family.HUAWEI
            "oneplus" in m -> Family.ONEPLUS
            listOf("oppo", "realme").any { it in m } -> Family.OPPO
            listOf("vivo", "iqoo").any { it in m } -> Family.VIVO
            else -> Family.OTHER
        }
    }

    fun needsSetup() = family() != Family.OTHER

    fun brandName(): String = when (family()) {
        Family.XIAOMI -> "Xiaomi"
        Family.SAMSUNG -> "Samsung"
        Family.HUAWEI -> "Huawei"
        Family.HONOR -> "Honor"
        Family.OPPO -> "Oppo/Realme"
        Family.VIVO -> "Vivo"
        Family.ONEPLUS -> "OnePlus"
        Family.OTHER -> Build.MANUFACTURER
    }

    fun instructions(): String = when (family()) {
        Family.XIAOMI -> """
            1. Настройки → Приложения → «Баюшки» → включи «Автозапуск».
            2. Там же: «Контроль активности» (или «Экономия батареи») → «Нет ограничений».
            3. Открой список недавних приложений, зажми карточку «Баюшки» и нажми замочек 🔒 — чтобы MIUI/HyperOS не закрывала его при очистке.
        """.trimIndent()
        Family.SAMSUNG -> """
            1. Настройки → Аккумулятор → Ограничения фонового использования.
            2. Убедись, что «Баюшки» НЕТ в «Спящих» и «Глубоко спящих» приложениях.
            3. Добавь его в «Неспящие приложения» (Never sleeping apps).
            4. В настройках приложения → Аккумулятор → «Без ограничений».
        """.trimIndent()
        Family.HUAWEI, Family.HONOR -> """
            1. Настройки → Аккумулятор → Запуск приложений.
            2. Найди «Баюшки», выключи «Управлять автоматически».
            3. В появившемся окне включи все три: «Автозапуск», «Косвенный запуск», «Работа в фоне».
        """.trimIndent()
        Family.OPPO -> """
            1. Настройки → Приложения → «Баюшки» → Использование батареи (или Энергосбережение).
            2. Включи «Разрешить фоновую активность» и «Разрешить автозапуск».
            3. В недавних приложениях зажми карточку и выбери «Закрепить» 🔒.
        """.trimIndent()
        Family.VIVO -> """
            1. i Manager → Управление приложениями → Автозапуск → включи «Баюшки».
            2. Настройки → Батарея → Высокое энергопотребление в фоне → разреши для «Баюшки».
        """.trimIndent()
        Family.ONEPLUS -> """
            1. Настройки → Приложения → «Баюшки» → Использование батареи → «Разрешить фоновую активность» / «Не оптимизировать».
            2. Если есть пункт «Автозапуск» — включи.
            3. В недавних приложениях закрепи карточку 🔒.
        """.trimIndent()
        Family.OTHER -> "На твоём телефоне дополнительных настроек обычно не требуется."
    }

    private fun candidates(ctx: Context): List<Intent> {
        fun c(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))
        return when (family()) {
            Family.XIAOMI -> listOf(
                c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST")
                    .putExtra("package_name", ctx.packageName).putExtra("package_label", "Баюшки"),
            )
            Family.SAMSUNG -> listOf(
                c("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                c("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                c("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            Family.HUAWEI -> listOf(
                c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                c("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            Family.HONOR -> listOf(
                c("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )
            Family.OPPO -> listOf(
                c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            Family.VIVO -> listOf(
                c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            )
            Family.ONEPLUS -> listOf(
                c("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            )
            Family.OTHER -> emptyList()
        }
    }

    /** true — открылся специальный экран производителя; false — открыли «О приложении». */
    fun openSettings(ctx: Context): Boolean {
        for (intent in candidates(ctx)) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // экрана нет в этой версии прошивки — пробуем следующий
            }
        }
        openAppDetails(ctx)
        return false
    }

    fun openAppDetails(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
