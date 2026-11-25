package com.game.caroxo.an.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.game.caroxo.an.R
import com.game.caroxo.an.audio.SoundAndHapticsManager
import com.game.caroxo.an.data.FirebaseGameRepository
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.engine.CaroAiEngine
import com.game.caroxo.an.engine.CaroRulesEngine
import com.game.caroxo.an.model.BoardPoint
import com.game.caroxo.an.model.BoardSurface
import com.game.caroxo.an.model.ChatMessage
import com.game.caroxo.an.model.GameMode
import com.game.caroxo.an.model.GameRoom
import com.game.caroxo.an.model.GameStatus
import com.game.caroxo.an.model.MoveRecord
import com.game.caroxo.an.model.Piece
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.PlayerProfile
import com.game.caroxo.an.model.UiText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameUiState(
    val mode: GameMode = GameMode.AI_MEDIUM,
    val boardSize: Int = 15,
    val blockTwoEnds: Boolean = true,
    val board: Array<IntArray> = Array(15) { IntArray(15) },
    val moveHistory: List<MoveRecord> = emptyList(),
    val currentTurn: Piece = Piece.X,
    val status: GameStatus = GameStatus.PLAYING,
    val lastMove: BoardPoint? = null,
    val hintPoint: BoardPoint? = null,
    val winLine: List<BoardPoint> = emptyList(),
    val playerXName: UiText = UiText.Resource(R.string.player_x_default),
    val playerXAvatarId: String = "ninja",
    val playerXStyle: PieceStyle = PieceStyle.CLASSIC,
    val playerOName: UiText = UiText.Resource(R.string.player_o_default),
    val playerOAvatarId: String = "robot",
    val playerOStyle: PieceStyle = PieceStyle.CLASSIC,
    val playerXScore: Int = 0,
    val playerOScore: Int = 0,
    val boardSurface: BoardSurface = BoardSurface.MINIMAL_CREAM,
    val isAiThinking: Boolean = false,
    val canUndo: Boolean = false,
    val canHint: Boolean = true,
    val isMyTurn: Boolean = true,
    val onlineRoomCode: String? = null,
    val myOnlinePiece: Piece = Piece.X,
    val remainingSeconds: Int = 30,
    val pointsDeltaText: UiText? = null,
    val timeoutWinnerName: String? = null
) {
    override fun equals(other: Any?): Boolean = false
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed interface GameEffect {
    data class ToastMessage(val text: UiText) : GameEffect
}

class GameViewModel(
    private val repository: FirebaseGameRepository,
    private val preferencesManager: PreferencesManager,
    private val soundManager: SoundAndHapticsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<GameEffect>()
    val effects: SharedFlow<GameEffect> = _effects.asSharedFlow()

    private val _roomMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val roomMessages: StateFlow<List<ChatMessage>> = _roomMessages.asStateFlow()

    private val _unreadChatCount = MutableStateFlow(0)
    val unreadChatCount: StateFlow<Int> = _unreadChatCount.asStateFlow()

    private val _isSendingRoomChat = MutableStateFlow(false)
    val isSendingRoomChat: StateFlow<Boolean> = _isSendingRoomChat.asStateFlow()

    private var aiJob: Job? = null
    private var hintJob: Job? = null
    private var roomJob: Job? = null
    private var roomChatJob: Job? = null
    private var timerJob: Job? = null
    private var recordedRoomOutcome: String? = null
    private var lastSubmittedMoveCount = -1
    private var isRoomChatOpen = false
    private val seenRoomMessageIds = mutableSetOf<String>()
    private var currentRoomMembers: List<String> = emptyList()
    private var gameStartedAtMs = System.currentTimeMillis()
    private var recordedMatchHistory = false

    private fun recordOfflineMatchOutcome(won: Boolean, draw: Boolean, opponentName: String, mode: GameMode) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (recordedMatchHistory) return
        recordedMatchHistory = true
        val duration = ((System.currentTimeMillis() - gameStartedAtMs) / 1000L).coerceAtLeast(0L)
        val resultStr = when {
            draw -> "DRAW"
            won -> "WIN"
            else -> "LOSS"
        }
        viewModelScope.launch {
            try {
                repository.recordMatch(
                    opponentNickname = opponentName,
                    result = resultStr,
                    mode = mode.name,
                    durationSeconds = duration
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record offline match history", e)
            }
        }
    }

    private fun recordOnlineMatchHistoryIfNeeded(room: GameRoom, myUid: String, status: GameStatus, myPiece: Piece) {
        if (room.winnerUid == "CANCELLED") return
        if (recordedMatchHistory) return
        recordedMatchHistory = true

        val isDraw = status == GameStatus.DRAW
        val iWon = (status == GameStatus.X_WON && myPiece == Piece.X) ||
            (status == GameStatus.O_WON && myPiece == Piece.O)
        val resultStr = when {
            isDraw -> "DRAW"
            iWon -> "WIN"
            else -> "LOSS"
        }
        val opponentName = if (room.hostUid == myUid) room.guestNickname.ifBlank { "Opponent" } else room.hostNickname.ifBlank { "Opponent" }
        val duration = ((System.currentTimeMillis() - (room.createdAt?.toDate()?.time ?: System.currentTimeMillis())) / 1000L).coerceAtLeast(0L)

        viewModelScope.launch {
            try {
                repository.recordMatch(
                    opponentNickname = opponentName,
                    result = resultStr,
                    mode = "ONLINE",
                    durationSeconds = duration
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record online match history", e)
            }
        }
    }

    init {
        viewModelScope.launch {
            preferencesManager.settings.collect { settings ->
                val cur = _uiState.value
                val isSignedIn = FirebaseAuth.getInstance().currentUser != null
                val safeStyle = if (settings.pieceStyle.isVip && !isSignedIn) PieceStyle.CLASSIC else settings.pieceStyle
                if (cur.mode != GameMode.ONLINE) {
                    _uiState.value = cur.copy(
                        boardSurface = settings.boardSurface,
                        playerXStyle = safeStyle,
                        playerOStyle = safeStyle
                    )
                } else {
                    _uiState.value = cur.copy(boardSurface = settings.boardSurface)
                }
            }
        }
    }

    fun startOfflineGame(
        mode: GameMode,
        boardSize: Int,
        blockTwoEnds: Boolean,
        aiDifficultyLabel: String = ""
    ) {
        cancelOnlineJobs()
        aiJob?.cancel()
        hintJob?.cancel()
        recordedRoomOutcome = null
        gameStartedAtMs = System.currentTimeMillis()
        recordedMatchHistory = false

        val settings = preferencesManager.settings.value
        val validSize = if (boardSize == 10 || boardSize == 15 || boardSize == 20) boardSize else 15
        val isSignedIn = FirebaseAuth.getInstance().currentUser != null
        val safeStyle = if (settings.pieceStyle.isVip && !isSignedIn) PieceStyle.CLASSIC else settings.pieceStyle
        val xName = UiText.Dynamic(settings.localNickname)
        val oName: UiText = when (mode) {
            GameMode.AI_EASY -> UiText.Resource(R.string.player_ai_easy)
            GameMode.AI_MEDIUM -> UiText.Resource(R.string.player_ai_medium)
            GameMode.AI_HARD -> UiText.Resource(R.string.player_ai_hard)
            else -> UiText.Resource(R.string.player_o_default)
        }

        _uiState.value = GameUiState(
            mode = mode,
            boardSize = validSize,
            blockTwoEnds = blockTwoEnds,
            board = Array(validSize) { IntArray(validSize) },
            moveHistory = emptyList(),
            currentTurn = Piece.X,
            status = GameStatus.PLAYING,
            lastMove = null,
            hintPoint = null,
            winLine = emptyList(),
            playerXName = xName,
            playerXAvatarId = settings.localAvatarId,
            playerXStyle = safeStyle,
            playerOName = oName,
            playerOAvatarId = if (mode.isAi()) "robot" else "cat",
            playerOStyle = safeStyle,
            playerXScore = 0,
            playerOScore = 0,
            boardSurface = settings.boardSurface,
            isAiThinking = false,
            canUndo = false,
            canHint = true,
            isMyTurn = true,
            onlineRoomCode = null
        )
    }

    fun startOnlineGame(roomCode: String) {
        cancelOnlineJobs()
        aiJob?.cancel()
        hintJob?.cancel()
        recordedRoomOutcome = null
        lastSubmittedMoveCount = -1
        gameStartedAtMs = System.currentTimeMillis()
        recordedMatchHistory = false

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            viewModelScope.launch {
                _effects.emit(GameEffect.ToastMessage(UiText.Resource(R.string.error_sign_in_before_room)))
            }
            return
        }

        val settings = preferencesManager.settings.value
        _uiState.value = GameUiState(
            mode = GameMode.ONLINE,
            boardSize = 15,
            blockTwoEnds = true,
            board = Array(15) { IntArray(15) },
            status = GameStatus.WAITING,
            playerXName = UiText.Dynamic(settings.localNickname),
            playerXAvatarId = settings.localAvatarId,
            playerXStyle = settings.pieceStyle,
            playerOName = UiText.Resource(R.string.waiting_opponent),
            playerOAvatarId = "robot",
            playerOStyle = PieceStyle.CLASSIC,
            boardSurface = settings.boardSurface,
            canUndo = false,
            canHint = false,
            isMyTurn = false,
            onlineRoomCode = roomCode,
            remainingSeconds = 30
        )

        roomJob = viewModelScope.launch {
            repository.observeRoom(roomCode)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val root = firestoreError ?: error
                    Log.w(TAG, "Online room observer error", root)
                    val msg = root.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.error_room_disconnected)
                    _effects.emit(GameEffect.ToastMessage(msg))
                }
                .collect { room ->
                    if (room != null) {
                        applyRoomSnapshot(room, uid)
                    }
                }
        }

        roomChatJob = viewModelScope.launch {
            repository.observeRoomChat(roomCode)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Online room chat observer error", firestoreError ?: error)
                }
                .collect { messages ->
                    _roomMessages.value = messages
                    if (isRoomChatOpen) {
                        messages.forEach { if (it.id.isNotBlank()) seenRoomMessageIds.add(it.id) }
                        _unreadChatCount.value = 0
                    } else {
                        val unread = messages.count {
                            it.senderUid != uid && it.id.isNotBlank() && it.id !in seenRoomMessageIds
                        }
                        _unreadChatCount.value = unread
                    }
                }
        }
    }

    private fun applyRoomSnapshot(room: GameRoom, myUid: String) {
        updateTrackedOpponent(room, myUid)
        val prevState = _uiState.value
        val size = if (room.boardSize == 10 || room.boardSize == 15 || room.boardSize == 20) room.boardSize else 15
        val board = Array(size) { IntArray(size) }
        val history = mutableListOf<MoveRecord>()

        for (encoded in room.moves) {
            val parts = encoded.split(",")
            if (parts.size == 3) {
                val r = parts[0].toIntOrNull()
                val c = parts[1].toIntOrNull()
                val pCode = parts[2].toIntOrNull()
                if (r != null && c != null && pCode != null && r in 0 until size && c in 0 until size) {
                    board[r][c] = pCode
                    history.add(MoveRecord(r, c, Piece.fromCode(pCode)))
                }
            }
        }

        val lastMovePt = if (room.lastMoveRow in 0 until size && room.lastMoveCol in 0 until size) {
            BoardPoint(room.lastMoveRow, room.lastMoveCol)
        } else null

        if (history.size > prevState.moveHistory.size && history.isNotEmpty()) {
            soundManager.playPlacePiece()
        }

        val myPiece = if (room.hostUid == myUid) Piece.X else Piece.O
        val turnPiece = if (room.currentTurn == "O") Piece.O else Piece.X
        val winRes = CaroRulesEngine.checkFullBoardWin(board, size, room.blockTwoEnds)

        val status = when (room.status) {
            "WAITING" -> GameStatus.WAITING
            "FINISHED" -> when (room.winnerUid) {
                room.hostUid -> GameStatus.X_WON
                room.guestUid -> GameStatus.O_WON
                "DRAW" -> GameStatus.DRAW
                else -> GameStatus.DRAW
            }
            else -> GameStatus.PLAYING
        }

        val isMyTurnNow = status == GameStatus.PLAYING && turnPiece == myPiece
        val justFinished = status != GameStatus.PLAYING && status != GameStatus.WAITING && prevState.status == GameStatus.PLAYING

        val timeoutWinner = if (status != GameStatus.PLAYING && status != GameStatus.WAITING && winRes == null && room.winnerUid != "DRAW" && room.winnerUid != "CANCELLED") {
            if (room.winnerUid == room.hostUid) room.hostNickname else room.guestNickname
        } else null

        _uiState.value = prevState.copy(
            mode = GameMode.ONLINE,
            boardSize = size,
            blockTwoEnds = room.blockTwoEnds,
            board = board,
            moveHistory = history,
            currentTurn = turnPiece,
            status = status,
            lastMove = lastMovePt,
            hintPoint = null,
            winLine = winRes?.line ?: emptyList(),
            playerXName = if (room.hostNickname.isNotBlank()) UiText.Dynamic(room.hostNickname) else UiText.Resource(R.string.player_x_default),
            playerXAvatarId = room.hostAvatarId.ifBlank { "ninja" },
            playerXStyle = PieceStyle.fromKey(room.hostStyle),
            playerOName = if (room.guestNickname.isNotBlank()) UiText.Dynamic(room.guestNickname) else UiText.Resource(R.string.waiting_opponent),
            playerOAvatarId = room.guestAvatarId.ifBlank { "robot" },
            playerOStyle = PieceStyle.fromKey(room.guestStyle),
            canUndo = false,
            canHint = false,
            isMyTurn = isMyTurnNow,
            onlineRoomCode = room.roomCode,
            myOnlinePiece = myPiece,
            timeoutWinnerName = timeoutWinner
        )

        if (status == GameStatus.PLAYING) {
            restartTurnCountdown(room, myUid)
        } else {
            timerJob?.cancel()
        }

        if (status == GameStatus.X_WON || status == GameStatus.O_WON || status == GameStatus.DRAW) {
            if (justFinished) {
                val iWon = (status == GameStatus.X_WON && myPiece == Piece.X) ||
                    (status == GameStatus.O_WON && myPiece == Piece.O)
                if (iWon) soundManager.playWin() else if (status != GameStatus.DRAW) soundManager.playLose()
            }
            recordOnlinePointsOnce(room, myUid, status, myPiece)
        }
    }

    private fun restartTurnCountdown(room: GameRoom, myUid: String) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val startedMs = room.turnStartedAt?.toDate()?.time ?: System.currentTimeMillis()
            while (true) {
                val elapsed = ((System.currentTimeMillis() - startedMs) / 1000L).toInt().coerceAtLeast(0)
                val remaining = (30 - elapsed).coerceIn(0, 30)
                _uiState.value = _uiState.value.copy(remainingSeconds = remaining)

                if (remaining == 0) {
                    // Active player or host resolves timeout cleanly
                    val cur = _uiState.value
                    if (cur.status == GameStatus.PLAYING) {
                        val timedOutWinnerUid = if (room.currentTurn == "X") room.guestUid else room.hostUid
                        if (timedOutWinnerUid.isNotBlank() && myUid in room.memberUids) {
                            repository.finishRoom(room.roomCode, timedOutWinnerUid)
                        }
                    }
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun recordOnlinePointsOnce(
        room: GameRoom,
        myUid: String,
        status: GameStatus,
        myPiece: Piece
    ) {
        if (room.winnerUid == "CANCELLED") return
        if (recordedRoomOutcome == room.roomCode) return
        recordedRoomOutcome = room.roomCode

        recordOnlineMatchHistoryIfNeeded(room, myUid, status, myPiece)

        val isDraw = status == GameStatus.DRAW
        val iWon = (status == GameStatus.X_WON && myPiece == Piece.X) ||
            (status == GameStatus.O_WON && myPiece == Piece.O)

        viewModelScope.launch {
            val res = repository.recordMatchOutcome(won = iWon, draw = isDraw)
            res.onSuccess { delta ->
                val deltaText = when {
                    isDraw -> UiText.Resource(R.string.result_points_delta_draw)
                    delta > 0 -> UiText.Resource(R.string.result_points_delta_win_fmt, delta)
                    else -> UiText.Resource(R.string.result_points_delta_loss_fmt, delta)
                }
                _uiState.value = _uiState.value.copy(pointsDeltaText = deltaText)
            }
        }
    }

    fun onCellTapped(row: Int, col: Int) {
        val state = _uiState.value
        if (state.status != GameStatus.PLAYING) return
        if (state.isAiThinking) return
        if (row !in 0 until state.boardSize || col !in 0 until state.boardSize) return
        if (state.board[row][col] != Piece.EMPTY.code) {
            soundManager.playError()
            return
        }

        if (state.mode == GameMode.ONLINE) {
            submitOnlineMove(row, col, state)
        } else {
            applyOfflineMove(row, col)
        }
    }

    private fun submitOnlineMove(row: Int, col: Int, state: GameUiState) {
        val roomCode = state.onlineRoomCode ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!state.isMyTurn) {
            soundManager.playError()
            return
        }
        val moveCount = state.moveHistory.size
        if (lastSubmittedMoveCount == moveCount) return
        lastSubmittedMoveCount = moveCount

        val piece = state.myOnlinePiece
        val boardCopy = Array(state.boardSize) { r -> state.board[r].clone() }
        boardCopy[row][col] = piece.code

        val winRes = CaroRulesEngine.checkWinAround(boardCopy, state.boardSize, row, col, state.blockTwoEnds)
        val isDraw = winRes == null && CaroRulesEngine.isBoardFull(boardCopy, state.boardSize)

        val newMoves = state.moveHistory.map { "${it.row},${it.col},${it.piece.code}" } + "$row,$col,${piece.code}"
        val nextTurn = if (piece == Piece.X) "O" else "X"
        val newStatus = if (winRes != null || isDraw) "FINISHED" else "PLAYING"
        val winnerUid = when {
            winRes != null -> uid
            isDraw -> "DRAW"
            else -> ""
        }

        soundManager.playPlacePiece()
        viewModelScope.launch {
            val result = repository.submitOnlineMove(
                roomCode = roomCode,
                row = row,
                col = col,
                piece = piece,
                updatedMoves = newMoves,
                nextTurn = nextTurn,
                newStatus = newStatus,
                winnerUid = winnerUid
            )
            result.onFailure {
                lastSubmittedMoveCount = -1
                soundManager.playError()
                _effects.emit(GameEffect.ToastMessage(UiText.Resource(R.string.error_submit_move)))
            }
        }
    }

    private fun applyOfflineMove(row: Int, col: Int) {
        val state = _uiState.value
        val piece = state.currentTurn
        val newBoard = Array(state.boardSize) { r -> state.board[r].clone() }
        newBoard[row][col] = piece.code
        val newHistory = state.moveHistory + MoveRecord(row, col, piece)

        soundManager.playPlacePiece()

        val winRes = CaroRulesEngine.checkWinAround(newBoard, state.boardSize, row, col, state.blockTwoEnds)
        if (winRes != null) {
            val xWon = winRes.winner == Piece.X
            if (state.mode.isAi() && !xWon) {
                soundManager.playLose()
            } else {
                soundManager.playWin()
            }
            val opponentName = if (state.mode.isAi()) {
                when (state.mode) {
                    GameMode.AI_EASY -> "AI (Easy)"
                    GameMode.AI_MEDIUM -> "AI (Medium)"
                    GameMode.AI_HARD -> "AI (Hard)"
                    else -> "AI"
                }
            } else {
                "Player O"
            }
            recordOfflineMatchOutcome(won = xWon, draw = false, opponentName = opponentName, mode = state.mode)

            _uiState.value = state.copy(
                board = newBoard,
                moveHistory = newHistory,
                lastMove = BoardPoint(row, col),
                hintPoint = null,
                winLine = winRes.line,
                status = if (xWon) GameStatus.X_WON else GameStatus.O_WON,
                playerXScore = if (xWon) state.playerXScore + 1 else state.playerXScore,
                playerOScore = if (!xWon) state.playerOScore + 1 else state.playerOScore,
                isAiThinking = false,
                canUndo = true,
                isMyTurn = false
            )
            return
        }

        if (CaroRulesEngine.isBoardFull(newBoard, state.boardSize)) {
            val opponentName = if (state.mode.isAi()) {
                when (state.mode) {
                    GameMode.AI_EASY -> "AI (Easy)"
                    GameMode.AI_MEDIUM -> "AI (Medium)"
                    GameMode.AI_HARD -> "AI (Hard)"
                    else -> "AI"
                }
            } else {
                "Player O"
            }
            recordOfflineMatchOutcome(won = false, draw = true, opponentName = opponentName, mode = state.mode)

            _uiState.value = state.copy(
                board = newBoard,
                moveHistory = newHistory,
                lastMove = BoardPoint(row, col),
                hintPoint = null,
                status = GameStatus.DRAW,
                isAiThinking = false,
                canUndo = true,
                isMyTurn = false
            )
            return
        }

        val nextTurn = piece.opponent()
        val aiNext = state.mode.isAi() && nextTurn == Piece.O

        _uiState.value = state.copy(
            board = newBoard,
            moveHistory = newHistory,
            currentTurn = nextTurn,
            lastMove = BoardPoint(row, col),
            hintPoint = null,
            isAiThinking = aiNext,
            canUndo = newHistory.isNotEmpty() && !aiNext,
            isMyTurn = !aiNext
        )

        if (aiNext) {
            triggerAiTurn()
        }
    }

    private fun triggerAiTurn() {
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            delay(260L)
            val snapshot = _uiState.value
            val boardCopy = Array(snapshot.boardSize) { r -> snapshot.board[r].clone() }
            val move = withContext(Dispatchers.Default) {
                CaroAiEngine.findBestMove(
                    board = boardCopy,
                    size = snapshot.boardSize,
                    aiPiece = Piece.O,
                    difficulty = snapshot.mode,
                    blockTwoEnds = snapshot.blockTwoEnds
                )
            }
            if (move != null && _uiState.value.status == GameStatus.PLAYING) {
                applyOfflineMove(move.row, move.col)
            } else {
                _uiState.value = _uiState.value.copy(isAiThinking = false, isMyTurn = true)
            }
        }
    }

    fun undoMove() {
        val state = _uiState.value
        if (state.mode == GameMode.ONLINE || state.isAiThinking) return
        if (state.moveHistory.isEmpty()) return

        soundManager.playUiClick()
        val stepsToPop = if (state.mode.isAi() && state.moveHistory.size >= 2 && state.currentTurn == Piece.X) 2 else 1
        val remaining = state.moveHistory.dropLast(stepsToPop)
        val newBoard = Array(state.boardSize) { IntArray(state.boardSize) }
        for (mv in remaining) {
            newBoard[mv.row][mv.col] = mv.piece.code
        }
        val lastPt = remaining.lastOrNull()?.let { BoardPoint(it.row, it.col) }
        val nextTurn = if (remaining.lastOrNull()?.piece == Piece.X) Piece.O else Piece.X

        _uiState.value = state.copy(
            board = newBoard,
            moveHistory = remaining,
            currentTurn = nextTurn,
            status = GameStatus.PLAYING,
            lastMove = lastPt,
            hintPoint = null,
            winLine = emptyList(),
            canUndo = remaining.isNotEmpty(),
            isMyTurn = true
        )
    }

    fun requestHint() {
        val state = _uiState.value
        if (state.mode == GameMode.ONLINE || state.status != GameStatus.PLAYING || state.isAiThinking) return
        soundManager.playUiClick()
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            val boardCopy = Array(state.boardSize) { r -> state.board[r].clone() }
            val hint = withContext(Dispatchers.Default) {
                CaroAiEngine.findHintMove(
                    board = boardCopy,
                    size = state.boardSize,
                    forPiece = state.currentTurn,
                    blockTwoEnds = state.blockTwoEnds
                )
            }
            _uiState.value = _uiState.value.copy(hintPoint = hint)
        }
    }

    fun resetOfflineGame() {
        val state = _uiState.value
        if (state.mode == GameMode.ONLINE) return
        aiJob?.cancel()
        hintJob?.cancel()
        soundManager.playUiClick()
        _uiState.value = state.copy(
            board = Array(state.boardSize) { IntArray(state.boardSize) },
            moveHistory = emptyList(),
            currentTurn = Piece.X,
            status = GameStatus.PLAYING,
            lastMove = null,
            hintPoint = null,
            winLine = emptyList(),
            isAiThinking = false,
            canUndo = false,
            isMyTurn = true
        )
    }

    fun resignOrLeaveOnlineGame(onDone: () -> Unit) {
        val state = _uiState.value
        val roomCode = state.onlineRoomCode
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        if (state.mode != GameMode.ONLINE || roomCode == null || myUid == null) {
            cancelOnlineJobs()
            onDone()
            return
        }

        if (state.status == GameStatus.WAITING) {
            viewModelScope.launch {
                repository.finishRoom(roomCode, "CANCELLED")
                cancelOnlineJobs()
                onDone()
            }
        } else if (state.status == GameStatus.PLAYING) {
            viewModelScope.launch {
                val opponentUid = currentOpponentUid
                if (!opponentUid.isNullOrBlank()) {
                    repository.finishRoom(roomCode, opponentUid)
                    repository.recordMatchOutcome(won = false, draw = false)
                }
                cancelOnlineJobs()
                onDone()
            }
        } else {
            cancelOnlineJobs()
            onDone()
        }
    }

    private var currentOpponentUid: String? = null

    fun updateTrackedOpponent(room: GameRoom, myUid: String) {
        currentOpponentUid = if (room.hostUid == myUid) room.guestUid else room.hostUid
        currentRoomMembers = room.memberUids
    }

    fun setRoomChatOpen(open: Boolean) {
        isRoomChatOpen = open
        if (open) {
            _roomMessages.value.forEach { if (it.id.isNotBlank()) seenRoomMessageIds.add(it.id) }
            _unreadChatCount.value = 0
        }
    }

    fun sendRoomChatMessage(rawText: String, onSent: () -> Unit = {}) {
        val state = _uiState.value
        val roomCode = state.onlineRoomCode ?: return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val cleanText = rawText.trim()
        if (cleanText.isEmpty()) {
            viewModelScope.launch {
                _effects.emit(GameEffect.ToastMessage(UiText.Resource(R.string.chat_empty_input)))
            }
            return
        }
        if (cleanText.length > 300) {
            viewModelScope.launch {
                _effects.emit(GameEffect.ToastMessage(UiText.Resource(R.string.chat_too_long)))
            }
            return
        }
        if (_isSendingRoomChat.value) return

        val members = currentRoomMembers.ifEmpty { listOf(user.uid) }
        val settings = preferencesManager.settings.value
        val senderProfile = PlayerProfile(
            userId = user.uid,
            nickname = settings.localNickname.ifBlank { user.displayName ?: "Player" },
            avatarId = settings.localAvatarId,
            pieceStyle = settings.pieceStyle.key
        )

        viewModelScope.launch {
            _isSendingRoomChat.value = true
            val result = repository.sendRoomMessage(
                roomCode = roomCode,
                memberUids = members,
                text = cleanText,
                senderProfile = senderProfile
            )
            _isSendingRoomChat.value = false
            result.onSuccess {
                onSent()
            }.onFailure { err ->
                val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.chat_error_send)
                _effects.emit(GameEffect.ToastMessage(msg))
            }
        }
    }

    fun cancelOnlineJobs() {
        roomJob?.cancel()
        roomJob = null
        roomChatJob?.cancel()
        roomChatJob = null
        timerJob?.cancel()
        timerJob = null
        currentOpponentUid = null
        currentRoomMembers = emptyList()
        isRoomChatOpen = false
        seenRoomMessageIds.clear()
        _roomMessages.value = emptyList()
        _unreadChatCount.value = 0
        _isSendingRoomChat.value = false
    }

    override fun onCleared() {
        super.onCleared()
        cancelOnlineJobs()
        aiJob?.cancel()
        hintJob?.cancel()
    }

    class Factory(
        private val repository: FirebaseGameRepository,
        private val preferencesManager: PreferencesManager,
        private val soundManager: SoundAndHapticsManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GameViewModel(repository, preferencesManager, soundManager) as T
        }
    }

    private companion object {
        const val TAG = "GameVM"
    }
}
