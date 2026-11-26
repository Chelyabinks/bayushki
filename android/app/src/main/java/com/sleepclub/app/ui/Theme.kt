package com.sleepclub.app.ui

import android.content.res.AssetManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** «Казённая колыбельная»: ночной барак, бумажные карточки, штампы и тёплая лампа. */
object Palette {
    // Ночь
    val Bg = Color(0xFF0E100F)
    val BgDeep = Color(0xFF07080A)
    val Surface = Color(0xFF171A18)
    val SurfaceHi = Color(0xFF1F2420)
    val Line = Color(0xFF2C322D)
    val Dash = Color(0xFF3A413B)
    val Text = Color(0xFFECE6D6)
    val TextSoft = Color(0xFFC9C3B3)
    val Muted = Color(0xFF8A9187)

    // Лампа
    val Amber = Color(0xFFF3B54A)
    val AmberSoft = Color(0xFFE9C98A)
    val AmberDim = Color(0xFFC9B07A)
    val AmberLine = Color(0xFF3A3220)

    // Бумага и чернила
    val Paper = Color(0xFFE9E2CF)
    val PaperSleep = Color(0xFFDCD4BE)
    val PaperOut = Color(0xFFC9C0A8)
    val PaperSheet = Color(0xFFECE5D2)
    val PaperRule = Color(0xFFD3CAB2)
    val PaperLine = Color(0xFFCFC6AE)
    val Ink = Color(0xFF1D1B16)
    val InkSoft = Color(0xFF3B372E)
    val InkMuted = Color(0xFF5E584B)
    val InkCare = Color(0xFF7A4E0E)
    val StampViolet = Color(0xFF4B3592)
    val StampRed = Color(0xFFB3301B)
    val InkRed = Color(0xFF9E2915)

    // Сигналы
    val Alive = Color(0xFF59FF00)
    val Good = Color(0xFF9BD27A)
    val Bad = Color(0xFFE2604A)

    // Старые имена — их используют экраны настроек и входа
    val Accent = Amber
    val Moon = Amber
    val Night = Color(0x33F3B54A)
    val Screen = Dash
}

/**
 * Шрифты лежат в app/src/main/assets/fonts. Если какого-то файла нет,
 * берём системный запасной, чтобы приложение всё равно работало.
 */
object AppFonts {
    var display: FontFamily = FontFamily.SansSerif; private set   // Dela Gothic One
    var mono: FontFamily = FontFamily.Monospace; private set      // PT Mono
    var hand: FontFamily = FontFamily.Cursive; private set        // Caveat
    var body: FontFamily = FontFamily.Default; private set        // Golos Text
    var care: FontFamily = FontFamily.Serif; private set          // Lora Italic

    fun init(assets: AssetManager) {
        val have = runCatching { assets.list("fonts")?.toSet() }.getOrNull().orEmpty()
        fun family(fallback: FontFamily, vararg files: Triple<String, FontWeight, FontStyle>): FontFamily {
            val fonts = files.filter { it.first in have }.map { (f, w, s) -> Font("fonts/$f", assets, w, s) }
            return if (fonts.isEmpty()) fallback else FontFamily(fonts)
        }
        val n = FontStyle.Normal
        display = family(FontFamily.SansSerif, Triple("DelaGothicOne-Regular.ttf", FontWeight.Normal, n))
        mono = family(FontFamily.Monospace, Triple("PTMono-Regular.ttf", FontWeight.Normal, n))
        hand = family(
            FontFamily.Cursive,
            Triple("Caveat-Medium.ttf", FontWeight.Medium, n),
            Triple("Caveat-Bold.ttf", FontWeight.Bold, n),
        )
        body = family(
            FontFamily.Default,
            Triple("GolosText-Regular.ttf", FontWeight.Normal, n),
            Triple("GolosText-Medium.ttf", FontWeight.Medium, n),
            Triple("GolosText-SemiBold.ttf", FontWeight.SemiBold, n),
            Triple("GolosText-Bold.ttf", FontWeight.Bold, n),
        )
        care = family(FontFamily.Serif, Triple("Lora-Italic.ttf", FontWeight.Normal, FontStyle.Italic))
    }
}

private val AppShape = RoundedCornerShape(3.dp)

@Composable
fun SleepTheme(content: @Composable () -> Unit) {
    val b = AppFonts.body
    val d = AppFonts.display
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Amber,
            onPrimary = Color(0xFF1A1408),
            secondary = Palette.Amber,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.SurfaceHi,
            onSurfaceVariant = Palette.Muted,
            surfaceContainerHigh = Palette.Surface,
            outline = Palette.Dash,
            outlineVariant = Palette.Line,
            error = Palette.Bad,
        ),
        typography = Typography(
            displayLarge = base.displayLarge.copy(fontFamily = d),
            displayMedium = base.displayMedium.copy(fontFamily = d),
            displaySmall = base.displaySmall.copy(fontFamily = d),
            headlineLarge = base.headlineLarge.copy(fontFamily = d, fontWeight = FontWeight.Normal),
            headlineMedium = base.headlineMedium.copy(fontFamily = d, fontWeight = FontWeight.Normal),
            headlineSmall = base.headlineSmall.copy(fontFamily = d, fontWeight = FontWeight.Normal),
            titleLarge = base.titleLarge.copy(fontFamily = d, fontWeight = FontWeight.Normal),
            titleMedium = base.titleMedium.copy(fontFamily = b, fontWeight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.copy(fontFamily = b),
            bodyLarge = base.bodyLarge.copy(fontFamily = b),
            bodyMedium = base.bodyMedium.copy(fontFamily = b),
            bodySmall = base.bodySmall.copy(fontFamily = b),
            labelLarge = base.labelLarge.copy(fontFamily = b, fontWeight = FontWeight.SemiBold),
            labelMedium = base.labelMedium.copy(fontFamily = b),
            labelSmall = base.labelSmall.copy(fontFamily = b),
        ),
        shapes = Shapes(
            extraSmall = AppShape, small = AppShape, medium = RoundedCornerShape(4.dp),
            large = RoundedCornerShape(6.dp), extraLarge = RoundedCornerShape(8.dp),
        ),
    ) {
        // Без этого текст вне карточек рисуется чёрным по умолчанию.
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}

/** Тёмная панель с тонкой рамкой — для служебных блоков. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    color: Color = Palette.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Palette.Text),
        border = BorderStroke(1.dp, Palette.Line),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Главная кнопка: янтарная, почти прямоугольная. Перекрывает material3.Button во всех экранах. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = Palette.Amber, contentColor = Color(0xFF1A1408),
        disabledContainerColor = Palette.SurfaceHi, disabledContentColor = Palette.Muted,
    ),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier.heightIn(min = 44.dp), enabled = enabled,
    shape = AppShape, colors = colors, contentPadding = contentPadding, content = content,
)

/** Второстепенная кнопка: тонкая рамка на тёмном. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick, modifier = modifier.heightIn(min = 44.dp), enabled = enabled,
    shape = AppShape,
    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Text),
    border = BorderStroke(1.dp, if (enabled) Palette.Dash else Palette.Line),
    contentPadding = contentPadding, content = content,
)

/** Служебная надпись машинописью: «ОТБОЙ С», «НОЧЬ». */
fun monoLabel(size: Int = 11, color: Color = Palette.Muted, spacing: Double = 2.0) = TextStyle(
    fontFamily = AppFonts.mono, fontSize = size.sp, letterSpacing = spacing.sp, color = color,
)
