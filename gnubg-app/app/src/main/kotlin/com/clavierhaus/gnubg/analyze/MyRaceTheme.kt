package com.clavierhaus.gnubg.analyze

import androidx.compose.ui.graphics.Color

/**
 * The three MyRace colour themes (assets/themes.js), same names and values.
 * `checkerHuman` is checkerCols[1] (MyRace's bottom player A) and
 * `checkerEngine` is checkerCols[0] (MyRace's top player B).
 */
data class MyRaceTheme(
    val name: String,
    val background: Color,
    val surface: Color,
    val frame: Color,
    val bar: Color,
    val tray: Color,
    val checkerEngine: Color,
    val checkerHuman: Color,
    val borderEngine: Color,
    val borderHuman: Color,
    val pointA: Color,
    val pointB: Color,
    val button: Color,
    val toggleButton: Color,
    val text: Color,
    val buttonText: Color,
)

val JungleTheme = MyRaceTheme(
    name = "Jungle",
    background = Color(0xFF000000),
    surface = Color(0xFF777777),
    frame = Color(0xFFA52A2A),
    bar = Color(0xFFA52A2A),
    tray = Color(0xFFA52A2A),
    checkerEngine = Color(0xFFFFFFFF),
    checkerHuman = Color(0xFF000000),
    borderEngine = Color(0xFF000000),
    borderHuman = Color(0xFFCCCCCC),
    pointA = Color(0xFF008000),
    pointB = Color(0xFF00FF00),
    button = Color(0xFFFFFF00),
    toggleButton = Color(0xFFAAAAAA),
    text = Color(0xFFFFFFFF),
    buttonText = Color(0xFF333333),
)

val CarnivalTheme = MyRaceTheme(
    name = "Carnival",
    background = Color(0xFF1E90FF),
    surface = Color(0xFF2A91E0),
    frame = Color(0xFFFF0000),
    bar = Color(0xFFCC0000),
    tray = Color(0xFF1F6BA6),
    checkerEngine = Color(0xFFDC143C),
    checkerHuman = Color(0xFF008000),
    borderEngine = Color(0xFF000000),
    borderHuman = Color(0xFF000000),
    pointA = Color(0xFFFFC919),
    pointB = Color(0xFFFF6F00),
    button = Color(0xFFFF0000),
    toggleButton = Color(0xFF000000),
    text = Color(0xFFFFFFFF),
    buttonText = Color(0xFFFFFFFF),
)

val NeonTheme = MyRaceTheme(
    name = "Neon",
    background = Color(0xFF1A0B2E),
    surface = Color(0xFF2B1650),
    frame = Color(0xFFFF2E97),
    bar = Color(0xFFB5156B),
    tray = Color(0xFF3D1F6D),
    checkerEngine = Color(0xFFFFE066),
    checkerHuman = Color(0xFF00F5D4),
    borderEngine = Color(0xFF1A0B2E),
    borderHuman = Color(0xFF1A0B2E),
    pointA = Color(0xFFFF2E97),
    pointB = Color(0xFF8F4BFF),
    button = Color(0xFF00F5D4),
    toggleButton = Color(0xFFFF2E97),
    text = Color(0xFFF5F0FF),
    buttonText = Color(0xFF1A0B2E),
)

val AllThemes = listOf(CarnivalTheme, JungleTheme, NeonTheme)

/** The pill colours MyRace's stats panels use. */
val PillLabel = Color(0xFFD3D3D3)   // lightgray
val PillValue = Color(0xFFFFC0CB)   // pink
