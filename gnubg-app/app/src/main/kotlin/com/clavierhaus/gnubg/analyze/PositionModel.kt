package com.clavierhaus.gnubg.analyze

/*
 * The editable position, in gnubg's own 50-int board encoding (the same one the
 * engine bridge uses):
 *
 *   b[0..23]   engine ("GNU", top player) checkers, b[q-1] = its own point q
 *   b[24]      engine checkers on the bar
 *   b[25..48]  human ("You", bottom player) checkers, b[25+p-1] = point p
 *   b[49]      human checkers on the bar
 *
 * The board is drawn like MyRace draws it: the human sits at the bottom, with the
 * home board bottom-right, and points are numbered 1..24 from the human's side.
 * A human checker on display point z lives at b[25+z-1]; an engine checker on the
 * same display point lives at b[24-z] (its own point 25-z).
 *
 * Nothing in this file judges backgammon. The editor only stops taps from making
 * what gnubg would refuse (a 16th checker, both colours on one point); gnubg's own
 * CheckPosition validates the result when it is installed.
 */

data class Position(
    val board: IntArray = IntArray(50),
    val d0: Int = 0,                 // 0 = no dice = a cube decision
    val d1: Int = 0,
    val turn: Int = 0,               // 0 = You on roll, 1 = GNU on roll
    val scoreH: Int = 0,
    val scoreE: Int = 0,
    val matchTo: Int = 7,            // 0 = money game
    val cube: Int = 1,
    val cubeOwner: Int = -1,         // -1 centred, 0 You, 1 GNU
    val crawford: Boolean = false,
) {
    val hasDice: Boolean get() = d0 > 0 && d1 > 0

    fun humanOnBoard(): Int = (0..24).sumOf { board[25 + it] }
    fun engineOnBoard(): Int = (0..24).sumOf { board[it] }
    fun humanOff(): Int = 15 - humanOnBoard()

    /** Pip counts, summed the way gnubg does: a checker on point p needs p pips, the bar counts 25. */
    fun pipsHuman(): Int = (1..24).sumOf { it * board[25 + it - 1] } + 25 * board[49]
    fun pipsEngine(): Int = (1..24).sumOf { it * board[it - 1] } + 25 * board[24]

    fun sameAs(o: Position): Boolean =
        board.contentEquals(o.board) && d0 == o.d0 && d1 == o.d1 && turn == o.turn &&
            scoreH == o.scoreH && scoreE == o.scoreE && matchTo == o.matchTo &&
            cube == o.cube && cubeOwner == o.cubeOwner && crawford == o.crawford
    fun engineOff(): Int = 15 - engineOnBoard()

    companion object {
        /** The standard opening position, in the editor's own encoding. */
        fun opening(): Position {
            val b = IntArray(50)
            // human: 24-point 2, 13-point 5, 8-point 3, 6-point 5
            b[25 + 23] = 2; b[25 + 12] = 5; b[25 + 7] = 3; b[25 + 5] = 5
            // engine, in its own frame: the same four points
            b[23] = 2; b[12] = 5; b[7] = 3; b[5] = 5
            return Position(board = b)
        }
    }
}

object Editor {
    private fun humanIdx(zone: Int) = 25 + (zone - 1)
    private fun engineIdx(zone: Int) = 24 - zone

    fun humanAt(b: IntArray, zone: Int) = b[humanIdx(zone)]
    fun engineAt(b: IntArray, zone: Int) = b[engineIdx(zone)]

    /**
     * Tap on point [zone] at stack height [level] (1 = next to the board edge).
     *  - tapping empty space above the stack grows it to that height,
     *  - tapping the top checker of a short stack removes it,
     *  - tapping the fifth slot of a full stack adds one more (the point then
     *    shows a number),
     *  - the other colour on the point is replaced.
     */
    fun tapPoint(p: Position, zone: Int, level: Int, human: Boolean): Position {
        val b = p.board.copyOf()
        val mine = if (human) humanIdx(zone) else engineIdx(zone)
        val theirs = if (human) engineIdx(zone) else humanIdx(zone)
        b[theirs] = 0
        val cur = b[mine]
        val base = if (human) 25 else 0
        val others = (0..24).sumOf { b[base + it] } - cur
        val room = 15 - others
        val wanted = when {
            level >= 5 && cur >= 5 -> cur + 1
            level == cur && cur < 5 -> cur - 1
            else -> level
        }
        b[mine] = wanted.coerceIn(0, maxOf(0, room))
        return p.copy(board = b)
    }

    fun clearPoint(p: Position, zone: Int): Position {
        val b = p.board.copyOf()
        b[humanIdx(zone)] = 0
        b[engineIdx(zone)] = 0
        return p.copy(board = b)
    }

    fun addToBar(p: Position, human: Boolean): Position {
        val b = p.board.copyOf()
        val bar = if (human) 49 else 24
        val total = if (human) p.humanOnBoard() else p.engineOnBoard()
        if (total < 15) b[bar]++ else if (b[bar] > 0) b[bar] = 0   // full: a tap clears
        return p.copy(board = b)
    }

    fun clearBar(p: Position, human: Boolean): Position {
        val b = p.board.copyOf()
        b[if (human) 49 else 24] = 0
        return p.copy(board = b)
    }

    fun clearBoard(p: Position): Position = p.copy(board = IntArray(50))

    /** Swap the two sides' checkers: whoever was on top is now at the bottom. The On roll setting is unchanged. */
    fun swapSides(p: Position): Position {
        val b = IntArray(50)
        for (i in 0..24) { b[i] = p.board[25 + i]; b[25 + i] = p.board[i] }
        return p.copy(
            board = b,
            // turn is left alone: the same side (You / GNU) stays on roll, so the
            // checkers that are on roll change along with the swap.
            scoreH = p.scoreE,
            scoreE = p.scoreH,
            cubeOwner = when (p.cubeOwner) { 0 -> 1; 1 -> 0; else -> -1 },
        )
    }

    /** Dice come in pairs or not at all: (1,0) is not a roll. */
    fun cycleDie(p: Position, which: Int): Position {
        if (which == 0 || p.d0 == 0) {
            val d0 = (p.d0 + 1) % 7
            val d1 = if (d0 == 0) 0 else if (p.d1 == 0) d0 else p.d1
            return p.copy(d0 = d0, d1 = d1)
        }
        return p.copy(d1 = if (p.d1 >= 6) 1 else p.d1 + 1)
    }

    fun clearDice(p: Position): Position = p.copy(d0 = 0, d1 = 0)

    fun setMatchTo(p: Position, matchTo: Int): Position {
        val m = matchTo.coerceIn(0, 25)
        val maxScore = if (m == 0) 0 else m - 1
        return p.copy(
            matchTo = m,
            scoreH = p.scoreH.coerceAtMost(maxScore),
            scoreE = p.scoreE.coerceAtMost(maxScore),
            crawford = if (m == 0) false else p.crawford,
        )
    }

    fun cycleCube(p: Position): Position {
        val c = if (p.cube >= 64) 1 else p.cube * 2
        return p.copy(cube = c, cubeOwner = if (c == 1) -1 else p.cubeOwner)
    }

    fun cycleCubeOwner(p: Position): Position {
        if (p.cube == 1) return p                    // a cube on 1 is always centred
        val next = when (p.cubeOwner) { -1 -> 0; 0 -> 1; else -> -1 }
        return p.copy(cubeOwner = next)
    }
}
