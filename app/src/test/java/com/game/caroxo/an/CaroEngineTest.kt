package com.game.caroxo.an

import com.game.caroxo.an.engine.CaroAiEngine
import com.game.caroxo.an.engine.CaroRulesEngine
import com.game.caroxo.an.model.GameMode
import com.game.caroxo.an.model.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CaroEngineTest {

    @Test
    fun testFiveInARowHorizontalWinAndBlockTwoEnds() {
        val size = 15
        val board = Array(size) { IntArray(size) }
        // Place 5 X pieces in row 7 cols 4..8
        for (c in 4..8) {
            board[7][c] = Piece.X.code
        }

        // Open ends -> wins regardless of blockTwoEnds
        assertNotNull(CaroRulesEngine.checkWinAround(board, size, 7, 6, blockTwoEnds = true))

        // Block both ends with O pieces at col 3 and col 9
        board[7][3] = Piece.O.code
        board[7][9] = Piece.O.code

        assertNull(CaroRulesEngine.checkWinAround(board, size, 7, 6, blockTwoEnds = true))
        assertNotNull(CaroRulesEngine.checkWinAround(board, size, 7, 6, blockTwoEnds = false))
    }

    @Test
    fun testAiBlocksImmediateThreatAndTakesWin() {
        val size = 15
        val board = Array(size) { IntArray(size) }
        // Human X has 4 in a row at row 5 cols 3..6
        for (c in 3..6) {
            board[5][c] = Piece.X.code
        }
        val blockMove = CaroAiEngine.findBestMove(board, size, Piece.O, GameMode.AI_HARD, true)
        assertNotNull(blockMove)
        assertEquals(5, blockMove!!.row)
        assert(blockMove.col == 2 || blockMove.col == 7)

        // Now give AI O 4 in a row at col 10 rows 2..5 -> AI must win immediately
        for (r in 2..5) {
            board[r][10] = Piece.O.code
        }
        val winMove = CaroAiEngine.findBestMove(board, size, Piece.O, GameMode.AI_HARD, true)
        assertNotNull(winMove)
        assertEquals(10, winMove!!.col)
        assert(winMove.row == 1 || winMove.row == 6)
    }
}
