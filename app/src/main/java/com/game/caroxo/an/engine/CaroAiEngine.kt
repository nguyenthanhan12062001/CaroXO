package com.game.caroxo.an.engine

import com.game.caroxo.an.model.BoardPoint
import com.game.caroxo.an.model.GameMode
import com.game.caroxo.an.model.Piece
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object CaroAiEngine {

    private val directions = arrayOf(
        intArrayOf(0, 1),
        intArrayOf(1, 0),
        intArrayOf(1, 1),
        intArrayOf(1, -1)
    )

    private const val WIN_SCORE = 1_000_000_000L

    fun findBestMove(
        board: Array<IntArray>,
        size: Int,
        aiPiece: Piece,
        difficulty: GameMode,
        blockTwoEnds: Boolean
    ): BoardPoint? {
        val candidates = getCandidateMoves(board, size, radius = 2)
        if (candidates.isEmpty()) {
            val center = size / 2
            return if (board[center][center] == Piece.EMPTY.code) BoardPoint(center, center) else null
        }

        val aiCode = aiPiece.code
        val humanCode = aiPiece.opponent().code

        // 1. Immediate win check across all difficulties
        for (pt in candidates) {
            board[pt.row][pt.col] = aiCode
            val win = CaroRulesEngine.checkWinAround(board, size, pt.row, pt.col, blockTwoEnds)
            board[pt.row][pt.col] = Piece.EMPTY.code
            if (win != null) return pt
        }

        // 2. Immediate block against opponent winning next move
        for (pt in candidates) {
            board[pt.row][pt.col] = humanCode
            val oppWin = CaroRulesEngine.checkWinAround(board, size, pt.row, pt.col, blockTwoEnds)
            board[pt.row][pt.col] = Piece.EMPTY.code
            if (oppWin != null) return pt
        }

        return when (difficulty) {
            GameMode.AI_EASY -> pickEasyMove(board, size, candidates, aiCode, humanCode, blockTwoEnds)
            GameMode.AI_MEDIUM -> pickMediumMove(board, size, candidates, aiCode, humanCode, blockTwoEnds)
            GameMode.AI_HARD -> pickHardMinimaxMove(board, size, candidates, aiCode, humanCode, blockTwoEnds)
            else -> pickMediumMove(board, size, candidates, aiCode, humanCode, blockTwoEnds)
        }
    }

    fun findHintMove(
        board: Array<IntArray>,
        size: Int,
        forPiece: Piece,
        blockTwoEnds: Boolean
    ): BoardPoint? = findBestMove(board, size, forPiece, GameMode.AI_HARD, blockTwoEnds)

    private fun pickEasyMove(
        board: Array<IntArray>,
        size: Int,
        candidates: List<BoardPoint>,
        aiCode: Int,
        humanCode: Int,
        blockTwoEnds: Boolean
    ): BoardPoint {
        val near = getCandidateMoves(board, size, radius = 1).ifEmpty { candidates }
        // Occasionally pick a reasonable block for open 3, otherwise local play
        val scored = near.map { pt ->
            pt to scoreCell(board, size, pt.row, pt.col, aiCode, humanCode, blockTwoEnds)
        }.sortedByDescending { it.second }
        val poolSize = min(4, scored.size)
        return scored[Random.nextInt(poolSize)].first
    }

    private fun pickMediumMove(
        board: Array<IntArray>,
        size: Int,
        candidates: List<BoardPoint>,
        aiCode: Int,
        humanCode: Int,
        blockTwoEnds: Boolean
    ): BoardPoint {
        val scored = candidates.map { pt ->
            pt to scoreCell(board, size, pt.row, pt.col, aiCode, humanCode, blockTwoEnds)
        }.sortedByDescending { it.second }

        val bestScore = scored.first().second
        val top = scored.takeWhile { it.second >= bestScore * 0.92 }.take(3)
        return top[Random.nextInt(top.size)].first
    }

    private fun pickHardMinimaxMove(
        board: Array<IntArray>,
        size: Int,
        candidates: List<BoardPoint>,
        aiCode: Int,
        humanCode: Int,
        blockTwoEnds: Boolean
    ): BoardPoint {
        val ranked = candidates.map { pt ->
            pt to scoreCell(board, size, pt.row, pt.col, aiCode, humanCode, blockTwoEnds)
        }.sortedByDescending { it.second }

        // Check immediate unstoppable open-4 attack before deeper search
        val topMoves = ranked.take(12).map { it.first }
        var bestMove = topMoves.first()
        var bestVal = Long.MIN_VALUE / 2
        val searchDepth = if (size >= 20) 2 else 3

        var alpha = Long.MIN_VALUE / 2
        val beta = Long.MAX_VALUE / 2

        for (move in topMoves) {
            board[move.row][move.col] = aiCode
            val value = minimax(
                board = board,
                size = size,
                depth = searchDepth - 1,
                isMaximizing = false,
                alpha = alpha,
                beta = beta,
                aiCode = aiCode,
                humanCode = humanCode,
                blockTwoEnds = blockTwoEnds,
                lastRow = move.row,
                lastCol = move.col
            )
            board[move.row][move.col] = Piece.EMPTY.code
            if (value > bestVal) {
                bestVal = value
                bestMove = move
            }
            alpha = max(alpha, bestVal)
        }
        return bestMove
    }

    private fun minimax(
        board: Array<IntArray>,
        size: Int,
        depth: Int,
        isMaximizing: Boolean,
        alpha: Long,
        beta: Long,
        aiCode: Int,
        humanCode: Int,
        blockTwoEnds: Boolean,
        lastRow: Int,
        lastCol: Int
    ): Long {
        val win = CaroRulesEngine.checkWinAround(board, size, lastRow, lastCol, blockTwoEnds)
        if (win != null) {
            return if (win.winner.code == aiCode) {
                WIN_SCORE + depth * 10_000L
            } else {
                -WIN_SCORE - depth * 10_000L
            }
        }
        if (depth <= 0) {
            return evaluateBoard(board, size, aiCode, humanCode, blockTwoEnds)
        }

        val candidates = getCandidateMoves(board, size, radius = 2)
        if (candidates.isEmpty()) return 0L

        val activeCode = if (isMaximizing) aiCode else humanCode
        val otherCode = if (isMaximizing) humanCode else aiCode
        val ordered = candidates.map { pt ->
            pt to scoreCell(board, size, pt.row, pt.col, activeCode, otherCode, blockTwoEnds)
        }.sortedByDescending { it.second }.take(8).map { it.first }

        var a = alpha
        var b = beta

        if (isMaximizing) {
            var maxEval = Long.MIN_VALUE / 2
            for (move in ordered) {
                board[move.row][move.col] = aiCode
                val eval = minimax(
                    board, size, depth - 1, false, a, b,
                    aiCode, humanCode, blockTwoEnds, move.row, move.col
                )
                board[move.row][move.col] = Piece.EMPTY.code
                maxEval = max(maxEval, eval)
                a = max(a, eval)
                if (b <= a) break
            }
            return maxEval
        } else {
            var minEval = Long.MAX_VALUE / 2
            for (move in ordered) {
                board[move.row][move.col] = humanCode
                val eval = minimax(
                    board, size, depth - 1, true, a, b,
                    aiCode, humanCode, blockTwoEnds, move.row, move.col
                )
                board[move.row][move.col] = Piece.EMPTY.code
                minEval = min(minEval, eval)
                b = min(b, eval)
                if (b <= a) break
            }
            return minEval
        }
    }

    private fun evaluateBoard(
        board: Array<IntArray>,
        size: Int,
        aiCode: Int,
        humanCode: Int,
        blockTwoEnds: Boolean
    ): Long {
        var aiTotal = 0L
        var humanTotal = 0L
        for (r in 0 until size) {
            for (c in 0 until size) {
                val cell = board[r][c]
                if (cell == aiCode) {
                    aiTotal += evaluatePointPatterns(board, size, r, c, aiCode, humanCode, blockTwoEnds)
                } else if (cell == humanCode) {
                    humanTotal += evaluatePointPatterns(board, size, r, c, humanCode, aiCode, blockTwoEnds)
                }
            }
        }
        return aiTotal - (humanTotal * 12L) / 10L
    }

    private fun scoreCell(
        board: Array<IntArray>,
        size: Int,
        row: Int,
        col: Int,
        attackCode: Int,
        defendCode: Int,
        blockTwoEnds: Boolean
    ): Long {
        board[row][col] = attackCode
        val attackScore = evaluatePointPatterns(board, size, row, col, attackCode, defendCode, blockTwoEnds)
        board[row][col] = defendCode
        val defendScore = evaluatePointPatterns(board, size, row, col, defendCode, attackCode, blockTwoEnds)
        board[row][col] = Piece.EMPTY.code

        val center = size / 2
        val centerBonus = (size - (kotlin.math.abs(row - center) + kotlin.math.abs(col - center))).toLong()
        return attackScore + (defendScore * 11L) / 10L + centerBonus
    }

    private fun evaluatePointPatterns(
        board: Array<IntArray>,
        size: Int,
        row: Int,
        col: Int,
        myCode: Int,
        oppCode: Int,
        blockTwoEnds: Boolean
    ): Long {
        var total = 0L
        var openThreeCount = 0
        var fourCount = 0

        for (dir in directions) {
            val dr = dir[0]
            val dc = dir[1]
            var count = 1

            var r = row + dr
            var c = col + dc
            while (r in 0 until size && c in 0 until size && board[r][c] == myCode) {
                count++
                r += dr
                c += dc
            }
            val endBlockedByOpp = r in 0 until size && c in 0 until size && board[r][c] == oppCode
            val endOpen = r in 0 until size && c in 0 until size && board[r][c] == Piece.EMPTY.code

            r = row - dr
            c = col - dc
            while (r in 0 until size && c in 0 until size && board[r][c] == myCode) {
                count++
                r -= dr
                c -= dc
            }
            val startBlockedByOpp = r in 0 until size && c in 0 until size && board[r][c] == oppCode
            val startOpen = r in 0 until size && c in 0 until size && board[r][c] == Piece.EMPTY.code

            val openEnds = (if (startOpen) 1 else 0) + (if (endOpen) 1 else 0)

            total += when {
                count >= 5 -> {
                    if (blockTwoEnds && startBlockedByOpp && endBlockedByOpp) 10L
                    else 500_000_000L
                }
                count == 4 -> {
                    when (openEnds) {
                        2 -> {
                            fourCount++
                            2_500_000L
                        }
                        1 -> {
                            fourCount++
                            220_000L
                        }
                        else -> 50L
                    }
                }
                count == 3 -> {
                    when (openEnds) {
                        2 -> {
                            openThreeCount++
                            180_000L
                        }
                        1 -> 8_000L
                        else -> 20L
                    }
                }
                count == 2 -> {
                    when (openEnds) {
                        2 -> 3_000L
                        1 -> 350L
                        else -> 5L
                    }
                }
                else -> if (openEnds == 2) 40L else 10L
            }
        }

        // Double threat (fork) bonus: 2 open-3s or 4 + open-3
        if (openThreeCount >= 2) total += 650_000L
        if (fourCount >= 1 && openThreeCount >= 1) total += 1_500_000L

        return total
    }

    private fun getCandidateMoves(board: Array<IntArray>, size: Int, radius: Int): List<BoardPoint> {
        val visited = Array(size) { BooleanArray(size) }
        val result = mutableListOf<BoardPoint>()
        var hasPieces = false

        for (r in 0 until size) {
            for (c in 0 until size) {
                if (board[r][c] != Piece.EMPTY.code) {
                    hasPieces = true
                    val rMin = max(0, r - radius)
                    val rMax = min(size - 1, r + radius)
                    val cMin = max(0, c - radius)
                    val cMax = min(size - 1, c + radius)
                    for (nr in rMin..rMax) {
                        for (nc in cMin..cMax) {
                            if (board[nr][nc] == Piece.EMPTY.code && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                result.add(BoardPoint(nr, nc))
                            }
                        }
                    }
                }
            }
        }

        if (!hasPieces) {
            val center = size / 2
            return listOf(BoardPoint(center, center))
        }
        return result
    }
}
