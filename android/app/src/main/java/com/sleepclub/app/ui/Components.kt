package com.sleepclub.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Пунктирная рамка, как в «Слежка работает». */
fun Modifier.dashedBorder(color: Color = Palette.Dash, radius: Dp = 4.dp, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
    )
}

/** Резиновый штамп: рамка краской, чуть повёрнут и полупрозрачен. */
@Composable
fun Stamp(text: String, color: Color, modifier: Modifier = Modifier, rotation: Float = -8f, size: Int = 13, alpha: Float = 0.85f) {
    Text(
        text,
        modifier = modifier
            .rotate(rotation)
            .alpha(alpha)
            .border(if (size >= 24) 4.dp else 3.dp, color, RoundedCornerShape(5.dp))
            .padding(horizontal = if (size >= 24) 14.dp else 9.dp, vertical = 4.dp),
        style = TextStyle(
            fontFamily = AppFonts.display, fontSize = size.sp, lineHeight = (size * 1.1).sp,
            letterSpacing = (if (size >= 24) 2 else 1).sp, color = color, textAlign = TextAlign.Center,
        ),
    )
}

/** Зарубки на стене: прошедшие ночи — мелом, оставшиеся — пунктиром. Каждая пятая перечёркивает четыре. */
@Composable
fun Tally(done: Int, total: Int, modifier: Modifier = Modifier, height: Dp = 54.dp) {
    val count = total.coerceAtLeast(1)
    val groups = (count - 1) / 5
    Canvas(modifier.height(height)) {
        val gapGroup = 10.dp.toPx()
        val step = ((size.width - groups * gapGroup - 8.dp.toPx()) / count).coerceAtMost(14.dp.toPx())
        val top = size.height * 0.1f
        val bottom = size.height * 0.9f
        var x = 6.dp.toPx()
        val chalk = Palette.Text
        val dashed = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx()))
        for (i in 0 until count) {
            val isFifth = i % 5 == 4
            val past = i < done
            val wobble = if (i % 2 == 0) 1.5f else -1f
            if (isFifth && past) {
                // Пятая зарубка — косая черта через предыдущие четыре.
                val x0 = x - step * 4 - 3.dp.toPx()
                drawLine(chalk, Offset(x0, bottom - 8.dp.toPx()), Offset(x + 2.dp.toPx(), top + 8.dp.toPx()),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            } else if (past) {
                drawLine(chalk, Offset(x + wobble, top), Offset(x - wobble, bottom), strokeWidth = 3.2.dp.toPx(), cap = StrokeCap.Round)
            } else {
                drawLine(Palette.Dash, Offset(x, top), Offset(x, bottom), strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round, pathEffect = dashed)
            }
            x += if (isFifth) step + gapGroup else step
        }
    }
}

/** Ширина, которую занимают зарубки: чтобы короткий раунд не растягивался на весь экран. */
fun tallyWidth(total: Int): Dp {
    val count = total.coerceAtLeast(1)
    return (count * 14 + ((count - 1) / 5) * 10 + 12).dp
}

/** «ещё три ночки», «последняя ночка», «ещё 12 ночек». */
fun nightsLeftText(left: Int): String = when {
    left <= 0 -> "последняя ночка"
    left == 1 -> "ещё одна ночка"
    left in 2..4 -> "ещё ${listOf("две", "три", "четыре")[left - 2]} ночки"
    else -> {
        val m10 = left % 10
        val m100 = left % 100
        "ещё $left " + when {
            m100 in 11..14 -> "ночек"
            m10 == 1 -> "ночка"
            m10 in 2..4 -> "ночки"
            else -> "ночек"
        }
    }
}

/** Свисток организатора — вместо слова «организатор». */
@Composable
fun WhistleIcon(color: Color = Palette.StampViolet, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 22.dp, height = 14.dp)) {
        val s = size.width / 30f
        val body = Path().apply {
            moveTo(2 * s, 4 * s); lineTo(15 * s, 4 * s); lineTo(17 * s, 2 * s); lineTo(28 * s, 2 * s)
            lineTo(28 * s, 8 * s); lineTo(20 * s, 8 * s)
            arcTo(androidx.compose.ui.geometry.Rect(6 * s, 4 * s, 20 * s, 18 * s), 0f, 180f, false)
            lineTo(6 * s, 8 * s); lineTo(2 * s, 8 * s); close()
        }
        drawPath(body, color, style = Stroke(width = 2.2f * s, join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawCircle(color, radius = 2 * s, center = Offset(13 * s, 11 * s))
    }
}

/** Колючая проволока поперёк экрана. */
@Composable
fun BarbedWire(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        val c = Color(0xFF4A524B)
        val w = size.width
        val h = size.height
        fun strand(dy: Float) = Path().apply {
            moveTo(0f, h / 2 + dy)
            var x = 0f
            var up = true
            while (x < w) {
                val nx = x + w / 6
                quadraticTo(x + w / 12, h / 2 + dy + if (up) -3.dp.toPx() else 3.dp.toPx(), nx, h / 2 + dy)
                x = nx
                up = !up
            }
        }
        drawPath(strand(0f), c, style = Stroke(1.4.dp.toPx()))
        drawPath(strand(1.dp.toPx()), c, style = Stroke(1.4.dp.toPx()))
        var x = w * 0.13f
        val k = 4.dp.toPx()
        while (x < w) {
            drawLine(c, Offset(x - k, h / 2 - 6.dp.toPx()), Offset(x + k, h / 2 + 6.dp.toPx()), 1.6.dp.toPx(), StrokeCap.Round)
            drawLine(c, Offset(x + k, h / 2 - 6.dp.toPx()), Offset(x - k, h / 2 + 6.dp.toPx()), 1.6.dp.toPx(), StrokeCap.Round)
            x += w * 0.23f
        }
    }
}

/** Бумажная учётная карточка с дыркой от скоросшивателя. */
@Composable
fun PaperCard(color: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(3.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .background(color, RoundedCornerShape(3.dp))
            .padding(start = 30.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
    ) {
        Box(Modifier.offset(x = (-20).dp, y = 4.dp).size(9.dp).background(Palette.Bg, CircleShape))
        content()
    }
}

/** Полный экран «Отбой»: луна, луч прожектора, проволока и обратный отсчёт до подъёма. */
@Composable
fun NightScreen(clock: String, groupName: String, nightLabel: String, wakeTime: String?, endsIn: String?) {
    Box(Modifier.fillMaxSize().background(Palette.BgDeep)) {
        // Луч прожектора и звёзды
        Canvas(Modifier.fillMaxSize()) {
            val beam = Path().apply {
                moveTo(size.width * 0.62f, -40f)
                lineTo(size.width * 0.70f, -40f)
                lineTo(size.width * 1.15f, size.height * 0.72f)
                lineTo(size.width * 0.05f, size.height * 0.72f)
                close()
            }
            drawPath(beam, Brush.verticalGradient(listOf(Palette.Text.copy(alpha = 0.10f), Color.Transparent),
                endY = size.height * 0.72f))
            val stars = listOf(0.10f to 0.14f, 0.25f to 0.08f, 0.54f to 0.18f, 0.18f to 0.27f, 0.82f to 0.30f)
            for ((i, p) in stars.withIndex()) {
                drawCircle(Palette.Text.copy(alpha = 0.4f + 0.1f * (i % 3)), radius = if (i == 2) 1.6.dp.toPx() else 1.dp.toPx(),
                    center = Offset(size.width * p.first, size.height * p.second))
            }
        }
        // Луна
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 44.dp).size(74.dp)
                .shadow(40.dp, CircleShape, ambientColor = Palette.Amber, spotColor = Palette.Amber)
                .background(Brush.radialGradient(listOf(Color(0xFFF6E7BF), Color(0xFFE9D7A6))), CircleShape),
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 30.dp)) {
            Row {
                Text("$clock · ГРУППА «${groupName.uppercase()}»", style = monoLabel(), modifier = Modifier.weight(1f))
                Text(nightLabel, style = monoLabel())
            }
            Spacer(Modifier.weight(1f))
            Text("ОТБОЙ", style = TextStyle(fontFamily = AppFonts.display, fontSize = 76.sp, lineHeight = 72.sp,
                letterSpacing = (-1).sp, color = Palette.Text))
            Spacer(Modifier.height(18.dp))
            Text(
                "Кто держится, а кто вылетел, узнаешь ${wakeTime?.let { "в $it" } ?: "утром"}. " +
                    "А сейчас положи телефон экраном вниз и укройся потеплее.",
                style = TextStyle(fontFamily = AppFonts.care, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontSize = 22.sp, lineHeight = 26.sp, color = Palette.AmberSoft),
            )
            Spacer(Modifier.height(30.dp))
            BarbedWire(Modifier.padding(horizontal = 0.dp))
            Spacer(Modifier.height(22.dp))
            if (endsIn != null) {
                Text("ЗАКОНЧИТСЯ ЧЕРЕЗ", style = monoLabel())
                Text(endsIn, style = TextStyle(fontFamily = AppFonts.display, fontSize = 40.sp, color = Palette.Text))
            }
            Spacer(Modifier.weight(0.6f))
            Box(Modifier.fillMaxWidth().height(1.dp).dashedLine())
            Spacer(Modifier.height(14.dp))
            Text("СЛЕЖКА ВЕДЁТСЯ · ПИЗДУЙ СПАТЬ", style = monoLabel(spacing = 1.0))
        }
    }
}

/** Горизонтальная пунктирная линия-разделитель. */
fun Modifier.dashedLine(color: Color = Palette.Dash): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
}

/** Строка из двух частей, прижатых к краям. */
@Composable
fun SpacedRow(modifier: Modifier = Modifier, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        left(); right()
    }
}
