package com.clavierhaus.gnubg.analyze

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/* MyRace look: bold Arial-ish text, lightgray label + pink value pills, rounded
 * theme-coloured rail buttons. */

fun Modifier.tapLong(onTap: () -> Unit, onLong: (() -> Unit)? = null): Modifier =
    this.pointerInput(onTap, onLong) {
        detectTapGestures(
            onTap = { onTap() },
            onLongPress = { onLong?.invoke() },
        )
    }

/** Bold single-line text that shrinks for long strings instead of wrapping. */
@Composable
fun FitText(
    text: String,
    baseSp: Float,
    color: Color = Color.Black,
    align: TextAlign = TextAlign.Center,
    maxChars: Int = 14,
    modifier: Modifier = Modifier,
) {
    val scale = if (text.length <= maxChars) 1f else min(1f, maxChars.toFloat() / text.length).coerceAtLeast(0.6f)
    Text(
        text = text,
        color = color,
        fontSize = (baseSp * scale).sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun RailButton(
    text: String,
    theme: MyRaceTheme,
    height: Dp,
    fontSp: Float,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(10.dp))
            .background(theme.button)
            .then(if (enabled) Modifier.tapLong(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        FitText(text, fontSp, theme.buttonText, maxChars = 9, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** A lightgray label cell and a pink value cell, like MyRace's stats rows. */
@Composable
fun Pill(
    label: String,
    height: Dp,
    fontSp: Float,
    modifier: Modifier = Modifier,
    labelWeight: Float = 1f,
    valueWeight: Float = 1f,
    value: @Composable BoxScope.() -> Unit,
) {
    val r = height * 0.4f
    Row(modifier = modifier.fillMaxWidth().height(height)) {
        Box(
            modifier = Modifier
                .weight(labelWeight)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = r, bottomStart = r))
                .background(PillLabel)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            FitText(label, fontSp, align = TextAlign.Start, maxChars = 16)
        }
        Box(
            modifier = Modifier
                .weight(valueWeight)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = r, bottomEnd = r))
                .background(PillValue),
            contentAlignment = Alignment.Center,
            content = value,
        )
    }
}

/** A pill whose whole value cell is one tap target (toggles and cyclers). */
@Composable
fun TapPill(
    label: String,
    valueText: String,
    height: Dp,
    fontSp: Float,
    enabled: Boolean = true,
    onLong: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    Pill(label, height, fontSp, modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
        Box(
            modifier = Modifier.fillMaxSize().then(if (enabled) Modifier.tapLong(onTap, onLong) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            FitText(valueText, fontSp, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

/** A pill with  −  value  +  in the value cell. */
@Composable
fun StepPill(
    label: String,
    valueText: String,
    height: Dp,
    fontSp: Float,
    canDec: Boolean,
    canInc: Boolean,
    onDec: () -> Unit,
    onInc: () -> Unit,
) {
    Pill(label, height, fontSp) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().alpha(if (canDec) 1f else 0.25f)
                    .then(if (canDec) Modifier.tapLong(onDec) else Modifier),
                contentAlignment = Alignment.Center,
            ) { FitText("−", fontSp * 1.5f) }
            Box(modifier = Modifier.weight(1.4f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                FitText(valueText, fontSp)
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().alpha(if (canInc) 1f else 0.25f)
                    .then(if (canInc) Modifier.tapLong(onInc) else Modifier),
                contentAlignment = Alignment.Center,
            ) { FitText("+", fontSp * 1.5f) }
        }
    }
}

@Composable
fun SmallPillButton(text: String, height: Dp, fontSp: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height * 0.4f))
            .background(PillValue)
            .tapLong(onClick),
        contentAlignment = Alignment.Center,
    ) { FitText(text, fontSp, modifier = Modifier.padding(horizontal = 10.dp)) }
}

val SpacedBy6 = Arrangement.spacedBy(6.dp)
