package com.clavierhaus.gnubg.analyze

import android.content.Context
import com.clavierhaus.gnubg.Engine
import com.clavierhaus.gnubg.engine.AssetExtractor
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/*
 * Everything that talks to gnubg. This is a port of the readBack / applyId /
 * evaluateEdit / rollout logic of the gnubg-android Analyse screen, with the UI
 * removed. No backgammon is decided here: the position is encoded by gnubg
 * (Engine.idsFromState), installed by gnubg (Engine.setGnubgId), validated by
 * gnubg's CheckPosition, and every number shown comes back from gnubg.
 *
 * All engine calls run on one dedicated thread, one at a time.
 */

/** [probs]: win, win gammon, win backgammon, lose gammon, lose backgammon (gnubg's cumulative convention), for the mover. */
class Candidate(val text: String, val equity: Float, val probs: FloatArray?)
data class RolloutCand(val text: String, val equity: Float, val done: Int)
data class RolloutSnap(
    val active: Boolean, val nCand: Int, val current: Int,
    val seed: Int, val trials: Int, val cands: List<RolloutCand>,
)

class AnalysisResult(
    val position: Position,           // what was analysed, as the user set it up
    val rawBoard: IntArray,           // engine frame, as cubeDecision / rollout take it
    val dice: Pair<Int, Int>?,
    val matchTo: Int,
    val score: Pair<Int, Int>,
    val cube: Int,
    val cubeOwner: Int,
    val crawford: Boolean,
    val onRoll: Int,
    val candidates: List<Candidate>,
    val noDice: Boolean,
    val cubeText: String?,            // gnubg's words for the cube decision
    val cubeDecision: Int,
    val cubeWin: FloatArray?,         // aarOutput[0][0..6] for the player on roll
    val cubeEq: FloatArray?,          // arDouble: optimal, no double, take, drop
)

sealed interface Outcome {
    data class Ok(val result: AnalysisResult, val askSwap: Boolean) : Outcome
    data class Failed(val message: String) : Outcome
}

enum class Met(val label: String, val fileName: String) {
    KAZAROSS_XG2("Kazaross XG2", "Kazaross-XG2.xml"),
    ROCKWELL_KAZAROSS("Rockwell / Kazaross", "Rockwell-Kazaross.xml"),
    WOOLSEY("Kit Woolsey", "woolsey.xml"),
    JACOBS_TRICE("Jacobs & Trice", "jacobs.xml"),
    SNOWIE("Snowie 2.1", "snowie.xml"),
    GNUBG_11("GNUbg 11-point", "g11.xml"),
    MEC26("MEC 26", "mec26.xml"),
    ZADEH("Zadeh (default)", "zadeh.xml"),
}

/** Evaluation depth of the cube verdict (the chequer ranking is always gnubg's 2-ply analysis). */
enum class Level(val label: String, val settingIndex: Int) {
    ZERO_PLY("0-ply", 4),
    TWO_PLY("2-ply", 6),
    THREE_PLY("3-ply", 7),
}

object GnubgSession {
    private val engineThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gnubg-engine-thread")
    }.asCoroutineDispatcher()

    private const val MAX_CANDIDATES = 8
    private const val GAME_PLAYING = 1
    private var metDir: String? = null
    @Volatile private var ready = false

    /** cubedecision NOT_AVAILABLE (engine-core/eval.h): no double is possible. */
    const val CUBEDECISION_NOT_AVAILABLE = 15

    suspend fun initialise(context: Context, met: Met, level: Level) = withContext(engineThread) {
        if (ready) return@withContext
        val app = context.applicationContext
        Engine.initialise(AssetExtractor.extractWeights(app))
        // The cube verdict is evaluated at the chosen depth, with no noise.
        Engine.setEngineStrength(level.settingIndex)
        Engine.setCubeUse(true)
        Engine.setAutoCrawford(true)
        metDir = AssetExtractor.extractMets(app)
        Engine.setMet(metDir + "/" + met.fileName)
        ready = true
    }

    suspend fun setLevel(level: Level) = withContext(engineThread) {
        Engine.setEngineStrength(level.settingIndex)
    }

    suspend fun setMet(met: Met) = withContext(engineThread) {
        val dir = metDir ?: return@withContext
        Engine.setMet(dir + "/" + met.fileName)
    }

    /** Encode the editor state with gnubg's encoders: "PositionID:MatchID". */
    suspend fun encode(p: Position): String? = withContext(engineThread) {
        Engine.idsFromState(
            p.board, p.d0, p.d1, p.turn, p.scoreH, p.scoreE,
            p.matchTo, p.cube, p.cubeOwner, if (p.crawford) 1 else 0,
        )
    }

    suspend fun swapPlayers() = withContext(engineThread) { Engine.swapPlayers() }

    /** Install a position from the editor and analyse it. */
    suspend fun analyse(p: Position): Outcome {
        val id = encode(p) ?: return Outcome.Failed("gnubg could not encode this position.")
        return analyseId(id, p)
    }

    /** Install a pasted GNU BG ID / XGID and analyse it. */
    suspend fun analyseId(id: String, source: Position? = null): Outcome = withContext(engineThread) {
        val rc = Engine.setGnubgId(id.trim())
        // SetGNUbgID discards SetBoard's return value, so a 0 or 2 does not prove the
        // position was installed. SetBoard refuses unless gs == GAME_PLAYING; if it is
        // not, no position was set (a finished-game ID yields the opening position).
        val gs = if (rc == 0 || rc == 2) Engine.getMatchState()[0] else GAME_PLAYING
        when {
            rc == 1 -> Outcome.Failed("No valid ID found. Paste a GNU BG ID or an XGID.")
            rc != 0 && rc != 2 -> Outcome.Failed("gnubg rejected this position.")
            gs != GAME_PLAYING -> Outcome.Failed(
                "gnubg did not set this position: it describes a game that is not in progress."
            )
            else -> Outcome.Ok(readBack(source), askSwap = rc == 2)
        }
    }

    /** Re-derive everything from the installed position (after a MET change or a swap). */
    suspend fun reread(source: Position?): AnalysisResult = withContext(engineThread) { readBack(source) }

    suspend fun swapAndRead(source: Position?): AnalysisResult = withContext(engineThread) {
        Engine.swapPlayers()
        readBack(source)
    }

    /** Must run on the engine thread. */
    private fun readBack(source: Position?): AnalysisResult {
        val st = Engine.getMatchState()
        val display = Engine.getMatchBoardHuman()
        val raw = Engine.getMatchBoard()
        val d0 = st[3]
        val d1 = st[4]

        val equities = FloatArray(MAX_CANDIDATES)
        val moves = IntArray(MAX_CANDIDATES * 8)
        val n = Engine.hintMoves(MAX_CANDIDATES, equities, moves)
        val probs = FloatArray(MAX_CANDIDATES * 5)
        val probRows = if (n > 0) Engine.hintProbs(MAX_CANDIDATES, probs) else 0
        val cands = if (n > 0) {
            (0 until n).map { i ->
                val mv = IntArray(8) { j -> moves[i * 8 + j] }
                Candidate(
                    Engine.formatMove(raw, mv), equities[i],
                    if (i < probRows) FloatArray(5) { j -> probs[i * 5 + j] } else null,
                )
            }
        } else emptyList()

        // No dice = a cube decision (gnubg's own rule in its desktop edit mode).
        var cubeText: String? = null
        var cubeWin: FloatArray? = null
        var cubeEq: FloatArray? = null
        var cubeDec = -1
        if (d0 == 0) {
            val cd = Engine.cubeDecision(raw)
            if (cd != null && cd.size >= 19) {
                cubeText = Engine.cubeRecommendation(cd[18])
                cubeDec = cd[18]
                cubeWin = FloatArray(7) { i -> Float.fromBits(cd[i]) }
                cubeEq = FloatArray(4) { i -> Float.fromBits(cd[14 + i]) }
            }
        }

        val pos = source ?: Position(
            board = display, d0 = d0, d1 = d1, turn = st[2],
            scoreH = st[10], scoreE = st[11], matchTo = st[12],
            cube = st[7], cubeOwner = st[6], crawford = st[8] != 0,
        )
        return AnalysisResult(
            position = pos,
            rawBoard = raw,
            dice = if (d0 > 0 && d1 > 0) Pair(d0, d1) else null,
            matchTo = st[12],
            score = Pair(st[10], st[11]),
            cube = st[7],
            cubeOwner = st[6],
            crawford = st[8] != 0,
            onRoll = st[2],
            candidates = cands,
            noDice = n == 0,
            cubeText = cubeText,
            cubeDecision = cubeDec,
            cubeWin = cubeWin,
            cubeEq = cubeEq,
        )
    }

    /** The installed position as an editor [Position] (used after pasting an ID). */
    suspend fun installedPosition(): Position = withContext(engineThread) {
        val st = Engine.getMatchState()
        Position(
            board = Engine.getMatchBoardHuman(),
            d0 = st[3], d1 = st[4], turn = st[2],
            scoreH = st[10], scoreE = st[11], matchTo = st[12],
            cube = st[7], cubeOwner = st[6], crawford = st[8] != 0,
        )
    }

    suspend fun currentId(): String? = withContext(engineThread) {
        val ids = Engine.currentIds()
        if (ids != null && ids.size == 2) ids[0] + ":" + ids[1] else null
    }

    /** Rollout of the cube decision: gnubg_mobile_rollout's means + std devs (7 + 7). */
    suspend fun rolloutCube(raw: IntArray, trials: Int = 144): FloatArray? =
        withContext(engineThread) { Engine.rollout(raw, trials) }

    /** Blocks for the whole run; poll [rolloutSnapshot] meanwhile. */
    suspend fun rolloutCandidates() = withContext(engineThread) { Engine.rolloutStart(3, 0) }

    fun rolloutCancel() = Engine.rolloutCancel()

    /** Safe from any thread. */
    fun rolloutSnapshot(rawBoard: IntArray): RolloutSnap? {
        val raw = IntArray(206)
        if (Engine.rolloutStatus(raw) <= 0) return null
        val n = raw[1]
        val cands = (0 until n).map { k ->
            val base = 6 + k * 25
            val mv = IntArray(8) { j -> raw[base + j] }
            RolloutCand(
                text = Engine.formatMove(rawBoard, mv),
                equity = Float.fromBits(raw[base + 8]),
                done = raw[base + 10],
            )
        }
        return RolloutSnap(raw[0] != 0, n, raw[2], raw[3], raw[4], cands)
    }
}
