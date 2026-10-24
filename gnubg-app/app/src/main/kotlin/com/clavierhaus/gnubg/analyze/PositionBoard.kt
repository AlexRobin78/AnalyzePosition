package com.clavierhaus.gnubg.analyze

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor

/*
 * The board, drawn the way MyRace draws it (BackgammonBoard.js): a frame, two
 * halves of six triangles, a centre bar, off-board trays in the corners. Sizes are
 * all fractions of one unit `u` (the board height / 0.9), exactly as MyRace's.
 *
 * Editing follows MyRace too: tap a point at the height you want the stack to
 * reach, long-press to clear it. The EDIT button in the bar switches which side's
 * checkers a tap places. Beyond MyRace: tap the bar's upper / lower half to put a
 * checker on the bar, tap the cube to double it (long-press to hand it over),
 * tap a die to change it (long-press to remove the dice).
 */

sealed interface BoardHit {
    data class Point(val zone: Int, val level: Int) : BoardHit
    data object BarEngine : BoardHit
    data object BarHuman : BoardHit
    data object EditToggle : BoardHit
    data object Cube : BoardHit
    data class Die(val which: Int) : BoardHit
    data object None : BoardHit
}

private const val BOARD_ASPECT = 1.376f

private class Geom(val w: Float, val h: Float) {
    val u = h / 0.9f
    val halfW = 0.4f * w
    val barW = 0.06f * w
    val margin = (w - 2 * halfW - barW) / 2f
    val pw = halfW / 6f
    val y0 = 0.005f * h
    val y1 = 0.995f * h
    val ph = 0.384f * u
    val cd = 0.08f * u                       // checker diameter
    val barX = margin + halfW
    val rightX = barX + barW
    val trayW = 0.074f * u
    val trayH = 0.384f * u
    val trayInset = 0.0045f * u

    // EDIT button in the middle of the bar
    val editW = barW * 0.94f
    val editH = 0.17f * u
    val editRect = Rect(barX + (barW - editW) / 2f, h / 2f - editH / 2f,
                        barX + (barW + editW) / 2f, h / 2f + editH / 2f)

    // The cube sits in the left margin
    val cubeSide = 0.066f * u
    fun cubeRect(owner: Int): Rect {
        val cx = margin / 2f
        val cy = when (owner) {
            0 -> h - trayInset - cubeSide * 0.8f      // You: bottom
            1 -> trayInset + cubeSide * 0.8f          // GNU: top
            else -> h / 2f
        }
        return Rect(cx - cubeSide / 2f, cy - cubeSide / 2f, cx + cubeSide / 2f, cy + cubeSide / 2f)
    }

    // Dice sit in the gap between the two rows, in the roller's half
    val dieSide = 0.1f * u
    fun dieRect(which: Int, turn: Int): Rect {
        val centreX = if (turn == 0) rightX + halfW / 2f else margin + halfW / 2f
        val gap = dieSide * 0.35f
        val left = centreX - dieSide - gap / 2f + which * (dieSide + gap)
        val top = h / 2f - dieSide / 2f
        return Rect(left, top, left + dieSide, top + dieSide)
    }

    /** Left edge and row of a display point (1..24). */
    fun column(zone: Int): Pair<Float, Boolean> = when (zone) {
        in 13..18 -> Pair(margin + (zone - 13) * pw, true)
        in 19..24 -> Pair(rightX + (zone - 19) * pw, true)
        in 7..12 -> Pair(margin + (12 - zone) * pw, false)
        else -> Pair(rightX + (6 - zone) * pw, false)
    }

    fun zoneAt(x: Float, top: Boolean): Int? {
        val col: Int
        val right: Boolean
        if (x >= margin && x < margin + halfW) { col = floor((x - margin) / pw).toInt(); right = false }
        else if (x >= rightX && x < rightX + halfW) { col = floor((x - rightX) / pw).toInt(); right = true }
        else return null
        val c = col.coerceIn(0, 5)
        return if (top) (if (right) 19 + c else 13 + c) else (if (right) 6 - c else 12 - c)
    }
}

private fun hitTest(g: Geom, pos: Position, at: Offset): BoardHit {
    if (g.editRect.contains(at)) return BoardHit.EditToggle
    if (g.cubeRect(pos.cubeOwner).inflate(g.cubeSide * 0.25f).contains(at)) return BoardHit.Cube
    for (i in 0..1) if (g.dieRect(i, pos.turn).inflate(g.dieSide * 0.1f).contains(at)) return BoardHit.Die(i)
    if (at.x >= g.barX && at.x < g.barX + g.barW) {
        return if (at.y < g.h / 2f) BoardHit.BarEngine else BoardHit.BarHuman
    }
    val top = at.y < g.h / 2f
    val zone = g.zoneAt(at.x, top) ?: return BoardHit.None
    val dist = if (top) at.y - g.y0 else g.y1 - at.y
    if (dist > g.ph + g.cd * 0.5f) return BoardHit.None      // the gap between the rows
    val level = ceil(dist / g.cd).toInt().coerceIn(1, 5)
    return BoardHit.Point(zone, level)
}

@Composable
fun PositionBoard(
    pos: Position,
    theme: MyRaceTheme,
    editHuman: Boolean,
    pipHuman: Int,
    pipEngine: Int,
    interactive: Boolean,
    onTap: (BoardHit) -> Unit,
    onLongPress: (BoardHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val posNow by rememberUpdatedState(pos)
    val onTapNow by rememberUpdatedState(onTap)
    val onLongNow by rememberUpdatedState(onLongPress)

    Canvas(
        modifier = modifier
            .aspectRatio(BOARD_ASPECT)
            .onSizeChanged { boxSize = it }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                detectTapGestures(
                    onTap = { off ->
                        if (boxSize.width > 0) {
                            val g = Geom(boxSize.width.toFloat(), boxSize.height.toFloat())
                            onTapNow(hitTest(g, posNow, off))
                        }
                    },
                    onLongPress = { off ->
                        if (boxSize.width > 0) {
                            val g = Geom(boxSize.width.toFloat(), boxSize.height.toFloat())
                            onLongNow(hitTest(g, posNow, off))
                        }
                    },
                )
            }
    ) {
        drawBoard(Geom(this.size.width, this.size.height), pos, theme, editHuman, pipHuman, pipEngine, measurer)
    }
}

private fun DrawScope.checker(c: Offset, r: Float, fill: Color, border: Color) {
    drawCircle(fill, r, c)
    drawCircle(border, r, c, style = Stroke(width = maxOf(1f, r * 0.08f)))
}

private fun DrawScope.label(
    m: TextMeasurer, text: String, centre: Offset, px: Float, color: Color, bold: Boolean = true
) {
    val r = m.measure(
        text,
        TextStyle(
            fontSize = px.toSp(),
            color = color,
            fontFamily = FontFamily.SansSerif,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
    )
    drawText(r, topLeft = Offset(centre.x - r.size.width / 2f, centre.y - r.size.height / 2f))
}

private fun DrawScope.drawBoard(
    g: Geom, pos: Position, th: MyRaceTheme, editHuman: Boolean,
    pipHuman: Int, pipEngine: Int, m: TextMeasurer,
) {
    // frame
    drawRect(th.frame)

    // trays: the two on the left are decoration, the two on the right hold what is borne off
    fun tray(x: Float, y: Float) = drawRect(th.tray, Offset(x, y), Size(g.trayW, g.trayH))
    tray(g.trayInset, g.trayInset)
    tray(g.trayInset, g.h - g.trayInset - g.trayH)
    val rightTrayX = g.w - g.trayInset - g.trayW
    tray(rightTrayX, g.trayInset)
    tray(rightTrayX, g.h - g.trayInset - g.trayH)
    val offH = pos.humanOff()
    val offE = pos.engineOff()
    val slotH = g.trayH * 0.05f
    for (i in 0 until offE) {
        val top = g.trayInset + i * slotH
        drawRect(th.checkerEngine, Offset(rightTrayX + g.trayW * 0.05f, top), Size(g.trayW * 0.9f, slotH))
        drawRect(th.borderEngine, Offset(rightTrayX + g.trayW * 0.05f, top), Size(g.trayW * 0.9f, slotH), style = Stroke(1f))
    }
    label(m, offE.toString(), Offset(rightTrayX + g.trayW / 2f, g.trayInset + offE * slotH + g.u * 0.03f), g.u * 0.034f, Color.White)
    for (i in 0 until offH) {
        val bottom = g.h - g.trayInset - i * slotH
        drawRect(th.checkerHuman, Offset(rightTrayX + g.trayW * 0.05f, bottom - slotH), Size(g.trayW * 0.9f, slotH))
        drawRect(th.borderHuman, Offset(rightTrayX + g.trayW * 0.05f, bottom - slotH), Size(g.trayW * 0.9f, slotH), style = Stroke(1f))
    }
    label(m, offH.toString(), Offset(rightTrayX + g.trayW / 2f, g.h - g.trayInset - offH * slotH - g.u * 0.03f), g.u * 0.034f, Color.White)

    // the two halves
    drawRect(th.surface, Offset(g.margin, g.y0 - 0.005f * g.h), Size(g.halfW, g.h * 0.99f + 0.005f * g.h))
    drawRect(th.surface, Offset(g.rightX, g.y0 - 0.005f * g.h), Size(g.halfW, g.h * 0.99f + 0.005f * g.h))

    // triangles and checkers
    for (zone in 1..24) {
        val (left, top) = g.column(zone)
        val idx = if (top) (if (zone <= 18) zone - 13 else zone - 19) else (if (zone >= 7) 12 - zone else 6 - zone)
        val colA = if (top) idx % 2 == 0 else idx % 2 == 1
        val tri = Path().apply {
            if (top) {
                moveTo(left, g.y0); lineTo(left + g.pw, g.y0); lineTo(left + g.pw / 2f, g.y0 + g.ph)
            } else {
                moveTo(left, g.y1); lineTo(left + g.pw, g.y1); lineTo(left + g.pw / 2f, g.y1 - g.ph)
            }
            close()
        }
        drawPath(tri, if (colA) th.pointA else th.pointB)

        val nH = Editor.humanAt(pos.board, zone)
        val nE = Editor.engineAt(pos.board, zone)
        val n = if (nH > 0) nH else nE
        if (n > 0) {
            val human = nH > 0
            val fill = if (human) th.checkerHuman else th.checkerEngine
            val border = if (human) th.borderHuman else th.borderEngine
            val shown = minOf(5, n)
            for (i in 0 until shown) {
                val cy = if (top) g.y0 + (i + 0.5f) * g.cd else g.y1 - (i + 0.5f) * g.cd
                val c = Offset(left + g.pw / 2f, cy)
                checker(c, g.cd / 2f * 0.96f, fill, border)
                if (i == shown - 1 && n > 5) {
                    val textColor = if (fill.luminanceApprox() > 0.5f) Color.Black else Color.White
                    label(m, n.toString(), c, g.cd * 0.5f, textColor)
                }
            }
        }
    }

    // bar
    drawRect(th.bar, Offset(g.barX, 0f), Size(g.barW, g.h))
    label(m, pipEngine.toString(), Offset(g.barX + g.barW / 2f, g.u * 0.04f), g.u * 0.04f, Color.White)
    label(m, pipHuman.toString(), Offset(g.barX + g.barW / 2f, g.h - g.u * 0.04f), g.u * 0.04f, Color.White)

    val barR = g.barW * 0.42f
    fun barStack(n: Int, human: Boolean) {
        if (n <= 0) return
        val shown = minOf(4, n)
        val fill = if (human) th.checkerHuman else th.checkerEngine
        val border = if (human) th.borderHuman else th.borderEngine
        for (i in 0 until shown) {
            val cy = if (human) g.h / 2f + g.editH / 2f + barR * (1.1f + 2.05f * i)
                     else g.h / 2f - g.editH / 2f - barR * (1.1f + 2.05f * i)
            val c = Offset(g.barX + g.barW / 2f, cy)
            checker(c, barR, fill, border)
            if (i == shown - 1 && n > 1) {
                val tc = if (fill.luminanceApprox() > 0.5f) Color.Black else Color.White
                label(m, n.toString(), c, barR * 1.1f, tc)
            }
        }
    }
    barStack(pos.board[24], human = false)
    barStack(pos.board[49], human = true)

    // EDIT button: which side a tap on a point places
    val sel = if (editHuman) th.checkerHuman else th.checkerEngine
    val selBorder = if (editHuman) th.borderHuman else th.borderEngine
    drawRoundRect(th.toggleButton, g.editRect.topLeft, g.editRect.size, CornerRadius(g.u * 0.012f))
    drawRoundRect(sel, g.editRect.topLeft, g.editRect.size, CornerRadius(g.u * 0.012f), style = Stroke(maxOf(2f, g.u * 0.008f)))
    label(m, "EDIT", Offset(g.editRect.center.x, g.editRect.top + g.editH * 0.32f), g.u * 0.03f, Color.White)
    val ind = Rect(g.editRect.center.x - g.editW * 0.3f, g.editRect.top + g.editH * 0.6f,
                   g.editRect.center.x + g.editW * 0.3f, g.editRect.top + g.editH * 0.82f)
    drawRoundRect(sel, ind.topLeft, ind.size, CornerRadius(g.u * 0.006f))
    drawRoundRect(selBorder, ind.topLeft, ind.size, CornerRadius(g.u * 0.006f), style = Stroke(1f))

    // cube
    val cr = g.cubeRect(pos.cubeOwner)
    drawRoundRect(Color.White, cr.topLeft, cr.size, CornerRadius(g.u * 0.008f))
    drawRoundRect(Color.Black, cr.topLeft, cr.size, CornerRadius(g.u * 0.008f), style = Stroke(maxOf(1.5f, g.u * 0.004f)))
    val cubeText = if (pos.cube == 1 && pos.cubeOwner == -1) "64" else pos.cube.toString()
    label(m, cubeText, cr.center, g.cubeSide * (if (cubeText.length > 2) 0.4f else 0.52f), Color.Black)

    // dice
    for (i in 0..1) {
        val r = g.dieRect(i, pos.turn)
        val v = if (i == 0) pos.d0 else pos.d1
        if (v == 0) {
            drawRoundRect(Color.White.copy(alpha = 0.18f), r.topLeft, r.size, CornerRadius(g.dieSide * 0.18f))
            drawRoundRect(Color.White.copy(alpha = 0.7f), r.topLeft, r.size, CornerRadius(g.dieSide * 0.18f),
                style = Stroke(maxOf(1.5f, g.u * 0.004f)))
            label(m, "?", r.center, g.dieSide * 0.6f, Color.White.copy(alpha = 0.8f))
        } else {
            drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(g.dieSide * 0.18f))
            drawRoundRect(Color.Black, r.topLeft, r.size, CornerRadius(g.dieSide * 0.18f), style = Stroke(maxOf(1.5f, g.u * 0.004f)))
            for (p in pips(v)) {
                drawCircle(Color.Black, g.dieSide * 0.085f,
                    Offset(r.left + r.width * p.first, r.top + r.height * p.second))
            }
        }
    }
}

private fun pips(v: Int): List<Pair<Float, Float>> {
    val a = 0.27f; val b = 0.5f; val c = 0.73f
    return when (v) {
        1 -> listOf(b to b)
        2 -> listOf(a to a, c to c)
        3 -> listOf(a to a, b to b, c to c)
        4 -> listOf(a to a, c to a, a to c, c to c)
        5 -> listOf(a to a, c to a, b to b, a to c, c to c)
        else -> listOf(a to a, c to a, a to b, c to b, a to c, c to c)
    }
}

private fun Color.luminanceApprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
