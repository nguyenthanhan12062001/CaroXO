package com.game.caroxo.an.engine

import com.game.caroxo.an.model.BoardPoint
import com.game.caroxo.an.model.Piece
import com.game.caroxo.an.model.WinResult

object CaroRulesEngine {

    private val directions = arrayOf(
        intArrayOf(0, 1),   // Horizontal
        intArrayOf(1, 0),   // Vertical
        intArrayOf(1, 1),   // Diagonal \
        intArrayOf(1, -1)   // Diagonal /
    )

    fun checkWinAround(
        board: Array<IntArray>,
        size: Int,
        row: Int,
        col: Int,
        blockTwoEnds: Boolean
    ): WinResult? {
        val code = board[row][col]
        if (code == Piece.EMPTY.code) return null
        val piece = Piece.fromCode(code)
        val opponentCode = piece.opponent().code

        for (dir in directions) {
            val dr = dir[0]
            val dc = dir[1]

            var startR = row
            var startC = col
            while (inBounds(size, startR - dr, startC - dc) && board[startR - dr][startC - dc] == code) {
                startR -= dr
                startC -= dc
            }

            var endR = row
            var endC = col
            while (inBounds(size, endR + dr, endC + dc) && board[endR + dr][endC + dc] == code) {
                endR += dr
                endC += dc
            }

            val line = mutableListOf<BoardPoint>()
            var r = startR
            var c = startC
            while (true) {
                line.add(BoardPoint(r, c))
                if (r == endR && c == endC) break
                r += dr
                c += dc
            }

            if (line.size >= 5) {
                if (blockTwoEnds) {
                    val beforeBlocked = inBounds(size, startR - dr, startC - dc) &&
                        board[startR - dr][startC - dc] == opponentCode
                    val afterBlocked = inBounds(size, endR + dr, endC + dc) &&
                        board[endR + dr][endC + dc] == opponentCode
                    if (beforeBlocked && afterBlocked) {
                        continue
                    }
                }
                return WinResult(winner = piece, line = line)
            }
        }
        return null
    }

    fun checkFullBoardWin(
        board: Array<IntArray>,
        size: Int,
        blockTwoEnds: Boolean
    ): WinResult? {
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (board[r][c] != Piece.EMPTY.code) {
                    val result = checkWinAround(board, size, r, c, blockTwoEnds)
                    if (result != null) return result
                }
            }
        }
        return null
    }

    fun isBoardFull(board: Array<IntArray>, size: Int): Boolean {
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (board[r][c] == Piece.EMPTY.code) return false
            }
        }
        return true
    }

    private fun inBounds(size: Int, r: Int, c: Int): Boolean =
        r in 0 until size && c in 0 until size
}
