package com.clavierhaus.gnubg.analyze

/*
 * Bear-off analysis for a position where all 15 of your checkers are in your home
 * board and you have dice to play.
 *
 *  - EPC (effective pip count) of the position after each legal play: the expected
 *    number of rolls to bear everything off, times 8.167 pips per roll. With no
 *    contact this is the exact one-sided bear-off value, found by solving the
 *    bear-off exactly (every roll plays the move that minimises the expected rolls).
 *
 *  - With contact (GNU has a checker on the bar or inside your home board, behind
 *    one of your points), the EPC is the average over every scenario in which you
 *    bear off completely without ever leaving a shot: each roll you play the
 *    safe move with the lowest race EPC; a roll with no safe move ends that scenario
 *    (it is left out of the average). The share of scenarios that stay safe is
 *    reported too.
 *
 *  - Shots: GNU's checkers are assumed to stay where they are. "Shot now" counts the
 *    rolls (of 36) that hit after the play; "next turn" counts the rolls (of 36) for
 *    which every legal play leaves a shot.
 *
 * Own checkers are indexed 1..6 (your points). The opponent array is indexed
 * 0 = GNU's bar and 1..6 = GNU's checkers on your points 1..6, which is where they
 * can hit you. GNU's checkers elsewhere cannot reach your home board.
 */

const val PIPS_PER_ROLL = 8.167
private const val NO_PLAY = "no play"

data class BearoffMove(
    val text: String,
    val epc: Double?,          // null: no safe bear-off exists
    val pSafe: Double?,        // contact only: share of scenarios that bear off without a shot
    val shotNow: Int,          // rolls (of 36) that hit after this play
    val nextShots: Int,        // rolls (of 36) next turn that leave a shot whatever you play
    val hitsGnu: Boolean,
)

data class BearoffReport(
    val contact: Boolean,
    val asIsShots: Int,        // rolls (of 36) that would leave a shot from the position as it stands
    val moves: List<BearoffMove>,
)

object Bearoff {
    private class Play(val text: String, val my: IntArray, val opp: IntArray?, val hit: Boolean)

    // unordered rolls with their weight out of 36
    private val rolls: List<IntArray> = buildList {
        for (a in 1..6) for (b in a..6) add(intArrayOf(a, b, if (a == b) 1 else 2))
    }

    private val raceMemo = HashMap<Long, Double>()

    // Precomputed table of raceRolls for every position of up to 15 checkers on points 1..6
    // (54,264 positions), stored as float32 in ascending key order. Optional: without it the
    // values are solved on demand, which takes several seconds.
    private var tableKeys: LongArray? = null
    private var tableVals: FloatArray? = null

    /** All positions in ascending key order: c6 outermost, c1 innermost, at most 15 checkers. */
    fun enumerateKeys(): LongArray {
        val out = ArrayList<Long>(54264)
        for (c6 in 0..15) for (c5 in 0..15 - c6) for (c4 in 0..15 - c6 - c5)
            for (c3 in 0..15 - c6 - c5 - c4) for (c2 in 0..15 - c6 - c5 - c4 - c3)
                for (c1 in 0..15 - c6 - c5 - c4 - c3 - c2) {
                    var k = 0L
                    for (c in intArrayOf(c6, c5, c4, c3, c2, c1)) k = k * 16 + c
                    out.add(k)
                }
        return out.toLongArray()
    }

    fun loadTable(input: java.io.InputStream) {
        val keys = enumerateKeys()
        val vals = FloatArray(keys.size)
        val buf = java.io.DataInputStream(java.io.BufferedInputStream(input, 1 shl 16))
        for (i in vals.indices) vals[i] = buf.readFloat()
        tableKeys = keys
        tableVals = vals
    }

    fun tableLoaded(): Boolean = tableVals != null

    fun keyOf(my: IntArray): Long = keyMy(my)
    private val hitMemo = HashMap<Long, Int>()
    private val safeMemo = HashMap<Long, DoubleArray>()
    private val nextMemo = HashMap<Long, Int>()

    private fun keyMy(my: IntArray): Long {
        var k = 0L
        for (p in 1..6) k = k * 16 + my[p]
        return k
    }

    private fun key(my: IntArray, opp: IntArray?): Long {
        var k = keyMy(my)
        if (opp != null) for (i in 0..6) k = k * 16 + minOf(opp[i], 15)
        return k
    }

    private fun isEmpty(my: IntArray): Boolean { for (p in 1..6) if (my[p] != 0) return false; return true }
    private fun maxPoint(my: IntArray): Int { for (p in 6 downTo 1) if (my[p] > 0) return p; return 0 }

    /** Can GNU still hit anything of yours, now or later? */
    fun hasContact(my: IntArray, opp: IntArray?): Boolean {
        if (opp == null) return false
        val top = maxPoint(my)
        if (top == 0) return false
        if (opp[0] > 0) return true
        for (z in 1 until top) if (opp[z] > 0) return true
        return false
    }

    // ------------------------------------------------------------------ plays

    private fun orders(a: Int, b: Int): List<IntArray> =
        if (a == b) listOf(intArrayOf(a, a, a, a)) else listOf(intArrayOf(a, b), intArrayOf(b, a))

    /** All legal plays for the roll, one per distinct result, using as many dice as possible. */
    private fun legalPlays(my0: IntArray, opp0: IntArray?, a: Int, b: Int): List<Play> {
        val found = LinkedHashMap<Long, Play>()
        val usedOf = HashMap<Long, Int>()
        var maxUsed = 0
        for (order in orders(a, b)) {
            val seen = HashSet<Long>()
            fun go(i: Int, m: IntArray, o: IntArray?, steps: List<String>, hit: Boolean) {
                val sk = key(m, o) * 8 + i
                if (!seen.add(sk)) return
                var any = false
                if (i < order.size) {
                    val d = order[i]
                    for (p in 6 downTo 1) {
                        if (m[p] == 0) continue
                        val dest = p - d
                        if (dest >= 1) {
                            if (o != null && o[dest] >= 2) continue
                            var no = o
                            var h = false
                            if (o != null && o[dest] == 1) { no = o.copyOf(); no[dest] = 0; no[0]++; h = true }
                            val nm = m.copyOf(); nm[p]--; nm[dest]++
                            any = true
                            go(i + 1, nm, no, steps + (p.toString() + "/" + dest + (if (h) "*" else "")), hit || h)
                        } else {
                            if (dest < 0) { var higher = false; for (q in p + 1..6) if (m[q] > 0) higher = true; if (higher) continue }
                            val nm = m.copyOf(); nm[p]--
                            any = true
                            go(i + 1, nm, o, steps + (p.toString() + "/off"), hit)
                        }
                    }
                }
                if (!any) {
                    val rk = key(m, o)
                    if (i > maxUsed) maxUsed = i
                    val prev = usedOf[rk]
                    if (prev == null || i > prev) {
                        usedOf[rk] = i
                        found[rk] = Play(compress(steps), m, o, hit)
                    }
                }
            }
            go(0, my0, opp0, emptyList(), false)
        }
        return found.entries.filter { usedOf[it.key] == maxUsed }.map { it.value }
    }

    private fun compress(steps: List<String>): String {
        if (steps.isEmpty()) return NO_PLAY
        val out = ArrayList<String>()
        val counts = LinkedHashMap<String, Int>()
        for (s in steps) counts[s] = (counts[s] ?: 0) + 1
        for ((s, n) in counts) out.add(if (n > 1) "$s($n)" else s)
        return out.joinToString(" ")
    }

    // ------------------------------------------------------------------ race (no contact)

    /** Expected number of rolls to bear off completely, playing to minimise it. */
    fun raceRolls(my: IntArray): Double {
        if (isEmpty(my)) return 0.0
        val k = keyMy(my)
        raceMemo[k]?.let { return it }
        val tk = tableKeys
        if (tk != null) {
            val idx = java.util.Arrays.binarySearch(tk, k)
            if (idx >= 0) { val v = tableVals!![idx].toDouble(); raceMemo[k] = v; return v }
        }
        var total = 0.0
        for (r in rolls) {
            var best = Double.MAX_VALUE
            for (pl in legalPlays(my, null, r[0], r[1])) {
                val e = raceRolls(pl.my)
                if (e < best) best = e
            }
            total += r[2] * (1.0 + best)
        }
        val e = total / 36.0
        raceMemo[k] = e
        return e
    }

    // ------------------------------------------------------------------ shots

    private fun hasBlot(my: IntArray): Boolean { for (p in 1..6) if (my[p] == 1) return true; return false }

    private fun hitDfs(my: IntArray, order: IntArray, i: Int, bar: Int, c: IntArray): Boolean {
        if (i >= order.size) return false
        val d = order[i]
        if (bar > 0) {
            if (my[d] >= 2) return hitDfs(my, order, i + 1, bar, c)      // cannot enter with this die
            if (my[d] == 1) return true
            val c2 = c.copyOf(); c2[d]++
            return hitDfs(my, order, i + 1, bar - 1, c2)
        }
        var moved = false
        for (z in 1..6) {
            if (c[z] == 0) continue
            val dest = z + d
            if (dest > 6) {
                val c2 = c.copyOf(); c2[z]--
                moved = true
                if (hitDfs(my, order, i + 1, 0, c2)) return true
            } else {
                if (my[dest] >= 2) continue
                if (my[dest] == 1) return true
                val c2 = c.copyOf(); c2[z]--; c2[dest]++
                moved = true
                if (hitDfs(my, order, i + 1, 0, c2)) return true
            }
        }
        return if (!moved) hitDfs(my, order, i + 1, 0, c) else false
    }

    /** Number of rolls (of 36) with which GNU can hit one of your blots. */
    fun hitRolls(my: IntArray, opp: IntArray?): Int {
        if (opp == null || !hasBlot(my) || !hasContact(my, opp)) return 0
        val k = key(my, opp)
        hitMemo[k]?.let { return it }
        var n = 0
        for (r in rolls) {
            var hit = false
            for (order in orders(r[0], r[1])) {
                if (hitDfs(my, order, 0, opp[0], opp.copyOf())) { hit = true; break }
            }
            if (hit) n += r[2]
        }
        hitMemo[k] = n
        return n
    }

    /** Rolls (of 36) for which every legal play leaves a shot. */
    fun shotLeavingRolls(my: IntArray, opp: IntArray?): Int {
        if (opp == null || isEmpty(my) || !hasContact(my, opp)) return 0
        val k = key(my, opp)
        nextMemo[k]?.let { return it }
        var n = 0
        for (r in rolls) {
            val plays = legalPlays(my, opp, r[0], r[1])
            if (plays.isNotEmpty() && plays.all { hitRolls(it.my, it.opp) > 0 }) n += r[2]
        }
        nextMemo[k] = n
        return n
    }

    // ------------------------------------------------------------------ safe bear-off (contact)

    /** [probability of bearing off without ever leaving a shot, sum over those scenarios of prob * rolls]. */
    private fun safe(my: IntArray, opp: IntArray?): DoubleArray {
        if (isEmpty(my)) return doubleArrayOf(1.0, 0.0)
        if (!hasContact(my, opp)) return doubleArrayOf(1.0, raceRolls(my))
        val k = key(my, opp)
        safeMemo[k]?.let { return it }
        var ps = 0.0
        var rs = 0.0
        var stay = 0.0          // rolls with no legal play at all: the roll is lost, the position is unchanged
        for (r in rolls) {
            val plays = legalPlays(my, opp, r[0], r[1]).filter { hitRolls(it.my, it.opp) == 0 }
            if (plays.isEmpty()) continue
            val w = r[2] / 36.0
            if (plays.size == 1 && plays[0].text == NO_PLAY) { stay += w; continue }
            var best = plays[0]
            var bestE = raceRolls(best.my)
            for (pl in plays) { val e = raceRolls(pl.my); if (e < bestE) { best = pl; bestE = e } }
            val sub = safe(best.my, best.opp)
            ps += w * sub[0]
            rs += w * (sub[0] + sub[1])
        }
        if (stay > 0.0) {
            // S = others + stay * S  (and one more roll for every lost roll)
            val f = 1.0 - stay
            val psAll = ps / f
            rs = (rs + stay * psAll) / f
            ps = psAll
        }
        val res = doubleArrayOf(ps, rs)
        safeMemo[k] = res
        return res
    }

    // ------------------------------------------------------------------ entry point

    /** Applies when all 15 of your checkers are on your points 1..6, it is your roll and the dice are set. */
    fun applies(p: Position): Boolean {
        if (!p.hasDice || p.turn != 0) return false
        var n = 0
        for (z in 1..6) n += Editor.humanAt(p.board, z)
        return n == 15
    }

    @Synchronized
    fun analyse(p: Position): BearoffReport? {
        if (!applies(p)) return null
        val my = IntArray(7)
        for (z in 1..6) my[z] = Editor.humanAt(p.board, z)
        val opp = IntArray(7)
        opp[0] = p.board[24]
        for (z in 1..6) opp[z] = Editor.engineAt(p.board, z)
        val contact = hasContact(my, opp)
        val oppUse: IntArray? = if (contact) opp else null

        val asIs = if (contact) shotLeavingRolls(my, opp) else 0
        val moves = legalPlays(my, opp, p.d0, p.d1).map { pl ->
            val m = pl.my
            val o = pl.opp ?: opp
            val c = hasContact(m, o)
            if (!c) {
                BearoffMove(pl.text, PIPS_PER_ROLL * raceRolls(m), null, 0, 0, pl.hit)
            } else {
                val s = safe(m, o)
                val epc = if (s[0] > 1e-12) PIPS_PER_ROLL * s[1] / s[0] else null
                BearoffMove(pl.text, epc, s[0], hitRolls(m, o), shotLeavingRolls(m, o), pl.hit)
            }
        }.sortedWith(compareBy({ it.epc == null }, { it.epc ?: 0.0 }))
        return BearoffReport(contact, asIs, moves)
    }
}
