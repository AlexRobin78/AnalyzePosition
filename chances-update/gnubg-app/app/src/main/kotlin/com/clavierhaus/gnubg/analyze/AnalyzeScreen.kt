package com.clavierhaus.gnubg.analyze

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.clavierhaus.gnubg.service.RolloutService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/*
 * Analyze Position > Setup, in the MyRace look.
 *
 *   [ button rail ]  [ board: tap to place checkers ]  [ pill panel: setup or results ]
 *
 * The panel shows the match setup (who is on roll, dice, match length, score, cube)
 * or gnubg's verdict for the position on the board. The board is always editable.
 */

private enum class Mode(val label: String) { SETUP("Setup"), RESULTS("Results"), BEAROFF("Bearoff") }

private fun fmtEq(f: Float) = String.format(Locale.US, "%+.3f", f)
private fun fmtPct(f: Float) = String.format(Locale.US, "%.1f", f * 100f)

@Composable
fun AnalyzeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("analyze", Context.MODE_PRIVATE) }

    var themeIdx by remember { mutableIntStateOf(prefs.getInt("theme", 0).coerceIn(AllThemes.indices)) }
    var metIdx by remember { mutableIntStateOf(prefs.getInt("met", 0).coerceIn(Met.entries.indices)) }
    var levelIdx by remember { mutableIntStateOf(prefs.getInt("level", 1).coerceIn(Level.entries.indices)) }
    val theme = AllThemes[themeIdx]
    val met = Met.entries[metIdx]
    val level = Level.entries[levelIdx]

    var ready by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }

    var pos by remember { mutableStateOf(Position.opening()) }
    var editHuman by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(Mode.SETUP) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var askSwap by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var rolloutRes by remember { mutableStateOf<FloatArray?>(null) }
    var rolloutBusy by remember { mutableStateOf(false) }
    var candSnap by remember { mutableStateOf<RolloutSnap?>(null) }
    var candRolling by remember { mutableStateOf(false) }

    var showId by remember { mutableStateOf(false) }
    var idText by remember { mutableStateOf("") }
    var idStatus by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    val rootView = LocalView.current
    DisposableEffect(candRolling, rolloutBusy) {
        rootView.keepScreenOn = candRolling || rolloutBusy
        onDispose { rootView.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        try {
            GnubgSession.initialise(ctx, met, level)
            ready = true
        } catch (t: Throwable) {
            initError = "The gnubg engine could not start: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    // Bear-off analysis: own computation (no gnubg needed), for positions with all 15 checkers home
    var bearoff by remember { mutableStateOf<BearoffReport?>(null) }
    var bearoffBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.Default) {
                ctx.assets.open("bearoff_race.bin").use { Bearoff.loadTable(it) }
            }
        } catch (_: Throwable) { /* without the table the values are solved on demand, just slower */ }
    }
    LaunchedEffect(pos) {
        if (!Bearoff.applies(pos)) { bearoff = null; bearoffBusy = false; return@LaunchedEffect }
        bearoffBusy = true
        delay(200)                       // wait for the taps to settle
        val rep = withContext(Dispatchers.Default) { Bearoff.analyse(pos) }
        bearoff = rep
        bearoffBusy = false
    }
    val bearoffOk = Bearoff.applies(pos)
    val availModes = buildList {
        add(Mode.SETUP)
        if (result != null) add(Mode.RESULTS)
        if (bearoffOk) add(Mode.BEAROFF)
    }
    val effMode = if (mode in availModes) mode else Mode.SETUP
    val nextMode = availModes[(availModes.indexOf(effMode) + 1) % availModes.size]

    fun clearResultState() {
        rolloutRes = null
        candSnap = null
    }

    fun analyze() {
        if (busy || !ready) return
        if (pos.humanOnBoard() == 0 || pos.engineOnBoard() == 0) {
            status = "Each side needs at least one checker on the board."
            mode = Mode.SETUP
            return
        }
        busy = true
        status = null
        clearResultState()
        scope.launch {
            when (val o = GnubgSession.analyse(pos)) {
                is Outcome.Ok -> {
                    result = o.result
                    askSwap = o.askSwap
                    mode = Mode.RESULTS
                }
                is Outcome.Failed -> {
                    result = null
                    askSwap = false
                    status = o.message
                    mode = Mode.SETUP
                }
            }
            busy = false
        }
    }

    fun analyzeId() {
        if (busy || !ready || idText.isBlank()) return
        busy = true
        idStatus = null
        clearResultState()
        scope.launch {
            when (val o = GnubgSession.analyseId(idText)) {
                is Outcome.Ok -> {
                    result = o.result
                    pos = o.result.position
                    askSwap = o.askSwap
                    status = null
                    mode = Mode.RESULTS
                    showId = false
                }
                is Outcome.Failed -> {
                    idStatus = o.message
                }
            }
            busy = false
        }
    }

    fun doSwap() {
        if (busy) return
        busy = true
        clearResultState()
        scope.launch {
            val r = GnubgSession.swapAndRead(null)
            result = r
            pos = r.position
            askSwap = false
            busy = false
        }
    }

    fun rollout() {
        val r = result ?: return
        if (busy || rolloutBusy) return
        if (candRolling) { GnubgSession.rolloutCancel(); return }
        mode = Mode.RESULTS
        if (r.cubeText != null) {
            rolloutBusy = true
            scope.launch {
                rolloutRes = GnubgSession.rolloutCube(r.rawBoard)
                rolloutBusy = false
            }
        } else if (r.candidates.isNotEmpty()) {
            candRolling = true
            candSnap = null
            try { RolloutService.start(ctx) } catch (_: Throwable) {}
            scope.launch {
                val poller = launch {
                    while (true) {
                        delay(200)
                        GnubgSession.rolloutSnapshot(r.rawBoard)?.let { candSnap = it }
                    }
                }
                GnubgSession.rolloutCandidates()
                poller.cancel()
                GnubgSession.rolloutSnapshot(r.rawBoard)?.let { candSnap = it }
                candRolling = false
                try { RolloutService.stop(ctx) } catch (_: Throwable) {}
            }
        }
    }

    fun rereadResult() {
        val r = result ?: return
        if (busy) return
        busy = true
        clearResultState()
        scope.launch {
            result = GnubgSession.reread(r.position)
            busy = false
        }
    }

    // ------------------------------------------------------------------ layout
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val h = maxHeight.value
        val gap = max(0.025f * h, 8f).dp
        val railW = (0.21f * h).coerceIn(76f, 120f).dp
        val panelW = min((0.66f * h).dp, maxWidth * 0.42f)
        val fontSp = (0.04f * h).coerceIn(12f, 16f)
        val railBtnH = ((h - 2 * gap.value - 6 * 6f) / 7f).coerceIn(30f, 52f).dp
        val rowH = (0.092f * h).coerceIn(32f, 46f).dp

        Row(modifier = Modifier.fillMaxSize().padding(gap)) {
            // ----- rail
            Column(
                modifier = Modifier.width(railW).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                RailButton(if (busy) "Working" else "Analyze", theme, railBtnH, fontSp, enabled = ready && !busy) { analyze() }
                val resNow = result
                val canRollout = resNow != null && !busy && !rolloutBusy &&
                    (candRolling || resNow.cubeText != null || resNow.candidates.isNotEmpty())
                RailButton(if (candRolling) "Stop" else "Rollout", theme, railBtnH, fontSp,
                    enabled = canRollout) { rollout() }
                RailButton(nextMode.label, theme, railBtnH, fontSp, enabled = availModes.size > 1) {
                    mode = nextMode
                }
                RailButton("Start pos", theme, railBtnH, fontSp) { pos = Position.opening().copy(
                    d0 = pos.d0, d1 = pos.d1, turn = pos.turn, scoreH = pos.scoreH, scoreE = pos.scoreE,
                    matchTo = pos.matchTo, cube = pos.cube, cubeOwner = pos.cubeOwner, crawford = pos.crawford) }
                RailButton("Clear", theme, railBtnH, fontSp) { pos = Editor.clearBoard(pos) }
                RailButton("Swap", theme, railBtnH, fontSp) { pos = Editor.swapSides(pos) }
                RailButton("ID", theme, railBtnH, fontSp) { idStatus = null; showId = true }
            }

            Spacer(Modifier.width(gap))

            // ----- board
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                val bw: Dp = min(maxWidth, maxHeight * 1.376f)
                PositionBoard(
                    pos = pos,
                    theme = theme,
                    editHuman = editHuman,
                    pipHuman = pos.pipsHuman(),
                    pipEngine = pos.pipsEngine(),
                    interactive = true,
                    onTap = { hit ->
                        when (hit) {
                            is BoardHit.Point -> pos = Editor.tapPoint(pos, hit.zone, hit.level, editHuman)
                            BoardHit.BarEngine -> pos = Editor.addToBar(pos, human = false)
                            BoardHit.BarHuman -> pos = Editor.addToBar(pos, human = true)
                            BoardHit.EditToggle -> editHuman = !editHuman
                            BoardHit.Cube -> pos = Editor.cycleCube(pos)
                            is BoardHit.Die -> pos = Editor.cycleDie(pos, hit.which)
                            BoardHit.None -> {}
                        }
                    },
                    onLongPress = { hit ->
                        when (hit) {
                            is BoardHit.Point -> pos = Editor.clearPoint(pos, hit.zone)
                            BoardHit.BarEngine -> pos = Editor.clearBar(pos, human = false)
                            BoardHit.BarHuman -> pos = Editor.clearBar(pos, human = true)
                            BoardHit.Cube -> pos = Editor.cycleCubeOwner(pos)
                            is BoardHit.Die -> pos = Editor.clearDice(pos)
                            else -> {}
                        }
                    },
                    modifier = Modifier.width(bw),
                )
            }

            Spacer(Modifier.width(gap))

            // ----- panel
            Column(
                modifier = Modifier.width(panelW).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                initError?.let { ErrorPill(it, rowH, fontSp) }
                if (!ready && initError == null) {
                    Note("Starting gnubg…", theme, fontSp)
                }
                status?.let { ErrorPill(it, rowH, fontSp) }

                if (effMode == Mode.SETUP) {
                    SetupPanel(
                        pos = pos, onPos = { pos = it },
                        editHuman = editHuman, onEditHuman = { editHuman = it },
                        met = met,
                        onMet = {
                            metIdx = (metIdx + 1) % Met.entries.size
                            prefs.edit().putInt("met", metIdx).apply()
                            val m = Met.entries[metIdx]
                            scope.launch { GnubgSession.setMet(m); rereadResult() }
                        },
                        level = level,
                        onLevel = {
                            levelIdx = (levelIdx + 1) % Level.entries.size
                            prefs.edit().putInt("level", levelIdx).apply()
                            val l = Level.entries[levelIdx]
                            scope.launch { GnubgSession.setLevel(l); rereadResult() }
                        },
                        themeName = theme.name,
                        onTheme = {
                            themeIdx = (themeIdx + 1) % AllThemes.size
                            prefs.edit().putInt("theme", themeIdx).apply()
                        },
                        theme = theme, rowH = rowH, fontSp = fontSp * 0.9f,
                        bearoffAvailable = bearoffOk,
                    )
                } else if (effMode == Mode.BEAROFF) {
                    BearoffPanel(bearoff, bearoffBusy, theme, rowH, fontSp * 0.9f)
                } else {
                    val r = result
                    if (r != null) {
                        ResultsPanel(
                            r = r,
                            stale = !pos.sameAs(r.position),
                            askSwap = askSwap, onSwap = { doSwap() }, onLeave = { askSwap = false },
                            rolloutRes = rolloutRes, rolloutBusy = rolloutBusy,
                            candSnap = candSnap, candRolling = candRolling,
                            met = met, level = level,
                            theme = theme, rowH = rowH, fontSp = fontSp * 0.9f,
                        )
                    } else {
                        Note("Set up a position and press Analyze.", theme, fontSp)
                    }
                }
            }
        }
    }

    if (showId) {
        AlertDialog(
            onDismissRequest = { showId = false },
            title = { Text("Position ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste a GNU BG ID or an XGID. gnubg decides which it is.", fontSize = 13.sp)
                    TextField(value = idText, onValueChange = { idText = it }, singleLine = false, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { clipboard.getText()?.text?.let { idText = it.trim() } }) { Text("Paste") }
                        TextButton(onClick = {
                            scope.launch {
                                val id = GnubgSession.encode(pos)
                                if (id != null) { idText = id; clipboard.setText(AnnotatedString(id)) }
                                else idStatus = "gnubg could not encode this position."
                            }
                        }) { Text("Copy this board's ID") }
                    }
                    idStatus?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFB00020), fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && idText.isNotBlank(), onClick = { analyzeId() }) { Text("Analyze ID") }
            },
            dismissButton = { TextButton(onClick = { showId = false }) { Text("Close") } },
        )
    }
}

// ---------------------------------------------------------------- panels

@Composable
private fun Note(text: String, theme: MyRaceTheme, fontSp: Float) {
    Text(text, color = theme.text, fontSize = (fontSp * 0.85f).sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun ErrorPill(text: String, rowH: Dp, fontSp: Float) {
    Box(
        modifier = Modifier.fillMaxWidth().background(PillValue, androidx.compose.foundation.shape.RoundedCornerShape(rowH * 0.3f))
            .padding(10.dp),
    ) {
        Text(text, color = androidx.compose.ui.graphics.Color(0xFF8B0000), fontSize = (fontSp * 0.85f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SetupPanel(
    pos: Position, onPos: (Position) -> Unit,
    editHuman: Boolean, onEditHuman: (Boolean) -> Unit,
    met: Met, onMet: () -> Unit,
    level: Level, onLevel: () -> Unit,
    themeName: String, onTheme: () -> Unit,
    theme: MyRaceTheme, rowH: Dp, fontSp: Float,
    bearoffAvailable: Boolean = false,
) {
    if (bearoffAvailable) Note("All 15 checkers are home and you have dice: press Bearoff for the EPC of every play and the shots.", theme, fontSp)
    TapPill("Placing", if (editHuman) "You" else "GNU", rowH, fontSp) { onEditHuman(!editHuman) }
    TapPill("On roll", if (pos.turn == 0) "You" else "GNU", rowH, fontSp) { onPos(pos.copy(turn = 1 - pos.turn)) }

    Pill("Dice", rowH, fontSp) {
        Row(modifier = Modifier.fillMaxSize()) {
            for (i in 0..1) {
                val v = if (i == 0) pos.d0 else pos.d1
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .tapLong({ onPos(Editor.cycleDie(pos, i)) }, { onPos(Editor.clearDice(pos)) }),
                    contentAlignment = Alignment.Center,
                ) { FitText(if (v == 0) "–" else v.toString(), fontSp * 1.2f) }
            }
        }
    }
    Note(if (pos.hasDice) "Dice set: gnubg ranks the checker plays." else "No dice: gnubg makes the cube decision.", theme, fontSp)

    StepPill(
        "Match to", if (pos.matchTo == 0) "Money" else pos.matchTo.toString(), rowH, fontSp,
        canDec = pos.matchTo > 0, canInc = pos.matchTo < 25,
        onDec = { onPos(Editor.setMatchTo(pos, pos.matchTo - 1)) },
        onInc = { onPos(Editor.setMatchTo(pos, pos.matchTo + 1)) },
    )
    if (pos.matchTo > 0) {
        val maxScore = pos.matchTo - 1
        StepPill("Score You", pos.scoreH.toString(), rowH, fontSp,
            canDec = pos.scoreH > 0, canInc = pos.scoreH < maxScore,
            onDec = { onPos(pos.copy(scoreH = pos.scoreH - 1)) },
            onInc = { onPos(pos.copy(scoreH = pos.scoreH + 1)) })
        StepPill("Score GNU", pos.scoreE.toString(), rowH, fontSp,
            canDec = pos.scoreE > 0, canInc = pos.scoreE < maxScore,
            onDec = { onPos(pos.copy(scoreE = pos.scoreE - 1)) },
            onInc = { onPos(pos.copy(scoreE = pos.scoreE + 1)) })
        TapPill("Crawford", if (pos.crawford) "Yes" else "No", rowH, fontSp) { onPos(pos.copy(crawford = !pos.crawford)) }
    }

    TapPill("Cube", pos.cube.toString(), rowH, fontSp,
        onLong = { onPos(pos.copy(cube = 1, cubeOwner = -1)) }) { onPos(Editor.cycleCube(pos)) }
    TapPill(
        "Cube owner",
        when (pos.cubeOwner) { 0 -> "You"; 1 -> "GNU"; else -> "Centred" },
        rowH, fontSp, enabled = pos.cube > 1,
    ) { onPos(Editor.cycleCubeOwner(pos)) }

    Pill("Pips You / GNU", rowH, fontSp) { FitText(pos.pipsHuman().toString() + "  /  " + pos.pipsEngine(), fontSp) }

    TapPill("Match table", met.label, rowH, fontSp) { onMet() }
    TapPill("Cube depth", level.label, rowH, fontSp) { onLevel() }
    TapPill("Theme", themeName, rowH, fontSp) { onTheme() }

    Note("Tap a point to place checkers up to that height; tap the top checker to remove it; long-press clears the point. " +
        "EDIT switches colour. Tap the bar, cube or dice to change them.", theme, fontSp * 0.85f)
}

@Composable
private fun ResultsPanel(
    r: AnalysisResult, stale: Boolean,
    askSwap: Boolean, onSwap: () -> Unit, onLeave: () -> Unit,
    rolloutRes: FloatArray?, rolloutBusy: Boolean,
    candSnap: RolloutSnap?, candRolling: Boolean,
    met: Met, level: Level,
    theme: MyRaceTheme, rowH: Dp, fontSp: Float,
) {
    if (stale) Note("The board has changed since this analysis. Press Analyze.", theme, fontSp)

    if (askSwap) {
        Note("The player on roll is drawn at the top. Swap sides?", theme, fontSp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallPillButton("Swap", rowH, fontSp, Modifier.weight(1f)) { onSwap() }
            SmallPillButton("Leave", rowH, fontSp, Modifier.weight(1f)) { onLeave() }
        }
    }

    // context, every time: a bare position would otherwise be judged for a match the user never stated
    val ctx = (if (r.matchTo == 0) "Money" else r.matchTo.toString() + " pt  " + r.score.first + "–" + r.score.second) +
        (if (r.crawford) "  Crawford" else "")
    Pill("Match", rowH, fontSp) { FitText(ctx, fontSp, maxChars = 12) }
    val owner = when (r.cubeOwner) { -1 -> "centred"; 0 -> "You"; else -> "GNU" }
    Pill("Cube", rowH, fontSp) { FitText(r.cube.toString() + " (" + owner + ")", fontSp) }
    Pill("On roll", rowH, fontSp) {
        FitText((if (r.onRoll == 0) "You" else "GNU") + (r.dice?.let { "  " + it.first + " " + it.second } ?: ""), fontSp)
    }

    if (r.cubeText != null) {
        Pill("Verdict", rowH, fontSp) { FitText(r.cubeText, fontSp, maxChars = 13) }
        r.cubeWin?.let { w ->
            // gnubg's own chances for the player on roll; gammon figures include backgammons
            Pill("Win", rowH, fontSp) { FitText(fmtPct(w[0]) + "%", fontSp) }
            Pill("Gammon win", rowH, fontSp) { FitText(fmtPct(w[1]) + "%", fontSp) }
            Pill("Backgammon win", rowH, fontSp) { FitText(fmtPct(w[2]) + "%", fontSp) }
            Pill("Loss", rowH, fontSp) { FitText(fmtPct(1f - w[0]) + "%", fontSp) }
            Pill("Gammon loss", rowH, fontSp) { FitText(fmtPct(w[3]) + "%", fontSp) }
            Pill("Backgammon loss", rowH, fontSp) { FitText(fmtPct(w[4]) + "%", fontSp) }
            Pill("Cubeless equity", rowH, fontSp) { FitText(fmtEq(w[5]), fontSp) }
            Pill("Cubeful equity", rowH, fontSp) { FitText(fmtEq(w[6]), fontSp) }
            Note("Chances are for the player on roll. Gammon includes backgammon.", theme, fontSp * 0.8f)
        }
        if (r.cubeDecision != GnubgSession.CUBEDECISION_NOT_AVAILABLE) {
            r.cubeEq?.let { e ->
                fun row(label: String, v: Float): Pair<String, String> {
                    val d = v - e[0]
                    return label to (if (abs(d) < 0.0005f) fmtEq(v) + "  best" else fmtEq(v) + "  (" + fmtEq(d) + ")")
                }
                for ((label, value) in listOf(row("No double", e[1]), row("Double, take", e[2]), row("Double, pass", e[3]))) {
                    Pill(label, rowH, fontSp) { FitText(value, fontSp * 0.95f, maxChars = 18) }
                }
            }
        }
        val ro = rolloutRes
        if (rolloutBusy) {
            Pill("Rollout", rowH, fontSp) { FitText("Rolling…", fontSp) }
        } else if (ro != null && ro.size >= 14) {
            Pill("Rollout 144", rowH, fontSp) { FitText("Win " + fmtPct(ro[0]) + " ± " + fmtPct(ro[7]), fontSp, maxChars = 16) }
            Pill("Rolled cubeful", rowH, fontSp) { FitText(fmtEq(ro[6]) + " ± " + String.format(Locale.US, "%.3f", ro[13]), fontSp, maxChars = 16) }
        }
        Note("Cube depth " + level.label + ", table " + met.label, theme, fontSp * 0.85f)
    } else if (r.noDice) {
        Note("No dice in this position, so there is no checker play to rank.", theme, fontSp)
    } else {
        val cs = candSnap
        if (cs == null) {
            val best = r.candidates.firstOrNull()?.equity ?: 0f
            r.candidates.forEachIndexed { i, c ->
                Pill((i + 1).toString() + ". " + c.text, rowH, fontSp, labelWeight = 1.5f, valueWeight = 1.1f) {
                    FitText(if (i == 0) fmtEq(c.equity) + "  best" else fmtEq(c.equity) + " (" + fmtEq(c.equity - best) + ")",
                        fontSp * 0.95f, maxChars = 15)
                }
                c.probs?.let { q ->
                    Note("Win " + fmtPct(q[0]) + " · Gammon " + fmtPct(q[1]) + " · BG " + fmtPct(q[2]) + " %", theme, fontSp * 0.8f)
                    Note("Loss " + fmtPct(1f - q[0]) + " · Gammon " + fmtPct(q[3]) + " · BG " + fmtPct(q[4]) + " %", theme, fontSp * 0.8f)
                }
            }
            Note("Equity is gnubg's cubeful equity for the player on roll. Gammon includes backgammon.", theme, fontSp * 0.8f)
        } else {
            Note("Rollout: " + cs.trials + " games each, cubeful, variance reduced", theme, fontSp)
            cs.cands.forEachIndexed { i, c ->
                Pill((i + 1).toString() + ". " + c.text + (if (candRolling && i == cs.current) " …" else ""),
                    rowH, fontSp, labelWeight = 1.5f, valueWeight = 1.1f) {
                    FitText((if (c.done > 0) fmtEq(c.equity) else "—") + "  " + c.done + "/" + cs.trials, fontSp * 0.9f, maxChars = 16)
                }
            }
            Note("Seed " + cs.seed + ". The same seed and settings in desktop gnubg reproduce these numbers.", theme, fontSp * 0.85f)
        }
    }
}

@Composable
private fun BearoffPanel(
    report: BearoffReport?, busy: Boolean,
    theme: MyRaceTheme, rowH: Dp, fontSp: Float,
) {
    if (report == null) {
        Note(if (busy) "Calculating…" else "Needs all 15 of your checkers on your home board points, You on roll, and dice.", theme, fontSp)
        return
    }
    if (busy) Note("Updating…", theme, fontSp)
    if (report.contact) {
        Note("GNU can still hit you. EPC is the average over every scenario in which you bear off without ever leaving a shot.", theme, fontSp * 0.9f)
        Pill("Shots as is", rowH, fontSp) { FitText(report.asIsShots.toString() + " / 36 rolls", fontSp) }
    } else {
        Note("No contact: EPC is the exact bear-off value of the position after each play.", theme, fontSp * 0.9f)
    }
    report.moves.forEach { m ->
        Pill(m.text + (if (m.hitsGnu) "  hit" else ""), rowH, fontSp, labelWeight = 1.35f, valueWeight = 1f) {
            FitText(if (m.epc != null) "EPC " + String.format(Locale.US, "%.2f", m.epc) else "no safe way", fontSp * 0.95f, maxChars = 12)
        }
        if (report.contact) {
            val safe = if (m.pSafe != null) "  ·  safe " + String.format(Locale.US, "%.0f", m.pSafe * 100) + "%" else ""
            Note("Shot now " + m.shotNow + "/36  ·  next turn " + m.nextShots + "/36" + safe, theme, fontSp * 0.8f)
        }
    }
    Note("Next turn = rolls (of 36) for which every legal play leaves a shot. GNU's checkers are assumed to stay where they are.", theme, fontSp * 0.8f)
}
