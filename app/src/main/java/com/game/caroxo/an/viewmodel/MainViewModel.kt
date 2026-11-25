package com.game.caroxo.an.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.game.caroxo.an.R
import com.game.caroxo.an.data.AuthManager
import com.game.caroxo.an.data.FirebaseGameRepository
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.model.ChatMessage
import com.game.caroxo.an.model.LeaderboardEntry
import com.game.caroxo.an.model.MatchRecord
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.PlayerProfile
import com.game.caroxo.an.model.UiText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: UiText) : UiState<Nothing>
}

sealed interface LobbyEvent {
    data class OpenOnlineRoom(val roomCode: String) : LobbyEvent
    data class ShowMessage(val message: UiText) : LobbyEvent
}

class MainViewModel(
    private val repository: FirebaseGameRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _currentUser = MutableStateFlow(FirebaseAuth.getInstance().currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _myProfile = MutableStateFlow<PlayerProfile?>(null)
    val myProfile: StateFlow<PlayerProfile?> = _myProfile.asStateFlow()

    private val _myCurrentRank = MutableStateFlow<Int?>(null)
    val myCurrentRank: StateFlow<Int?> = _myCurrentRank.asStateFlow()

    private val _leaderboardState = MutableStateFlow<UiState<List<LeaderboardEntry>>>(UiState.Idle)
    val leaderboardState: StateFlow<UiState<List<LeaderboardEntry>>> = _leaderboardState.asStateFlow()

    private val _globalChatState = MutableStateFlow<UiState<List<ChatMessage>>>(UiState.Idle)
    val globalChatState: StateFlow<UiState<List<ChatMessage>>> = _globalChatState.asStateFlow()

    private val _matchHistoryState = MutableStateFlow<UiState<List<MatchRecord>>>(UiState.Idle)
    val matchHistoryState: StateFlow<UiState<List<MatchRecord>>> = _matchHistoryState.asStateFlow()

    private val _isSendingGlobalChat = MutableStateFlow(false)
    val isSendingGlobalChat: StateFlow<Boolean> = _isSendingGlobalChat.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _events = MutableSharedFlow<LobbyEvent>()
    val events: SharedFlow<LobbyEvent> = _events.asSharedFlow()

    private var profileJob: Job? = null
    private var leaderboardJob: Job? = null
    private var globalChatJob: Job? = null
    private var matchHistoryJob: Job? = null
    private var activeObservedUid: String? = null

    init {
        viewModelScope.launch {
            AuthManager.authStateFlow().collect { user ->
                _currentUser.value = user
                if (user == null || user.uid.isBlank()) {
                    stopAuthenticatedListeners()
                } else if (activeObservedUid != user.uid) {
                    startAuthenticatedListeners(user)
                }
            }
        }
    }

    private fun startAuthenticatedListeners(user: FirebaseUser) {
        stopAuthenticatedListeners()
        val uid = user.uid
        activeObservedUid = uid

        viewModelScope.launch {
            val settings = preferencesManager.settings.value
            val fallbackNick = user.displayName?.takeIf { it.isNotBlank() } ?: settings.localNickname
            repository.ensureProfileInitialized(
                defaultNickname = fallbackNick,
                defaultAvatarId = settings.localAvatarId,
                defaultPieceStyle = settings.pieceStyle
            )
        }

        profileJob = viewModelScope.launch {
            repository.observeMyProfile(uid)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Profile observer error", firestoreError ?: error)
                }
                .collect { profile ->
                    _myProfile.value = profile
                    if (profile != null) {
                        val currentStyle = PieceStyle.fromKey(profile.pieceStyle)
                        val lbReady = _leaderboardState.value is UiState.Success
                        val effectiveStyle = if (lbReady && !currentStyle.isUnlockedFor(true, _myCurrentRank.value)) {
                            PieceStyle.CLASSIC
                        } else {
                            currentStyle
                        }
                        preferencesManager.setLocalProfile(
                            nickname = profile.nickname,
                            avatarId = profile.avatarId,
                            pieceStyle = effectiveStyle
                        )
                        if (lbReady && effectiveStyle != currentStyle) {
                            repository.updateProfile(profile.nickname, profile.avatarId, effectiveStyle)
                        }
                        _myCurrentRank.value?.let { currentRank ->
                            syncPeakRankIfImproved(profile, currentRank)
                        }
                    }
                }
        }

        _leaderboardState.value = UiState.Loading
        leaderboardJob = viewModelScope.launch {
            repository.observeLeaderboardTop20()
                .map<List<LeaderboardEntry>, UiState<List<LeaderboardEntry>>> { UiState.Success(it) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Leaderboard observer error", rootError)
                    val msg = rootError.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.error_leaderboard_load)
                    emit(UiState.Error(msg))
                }
                .collect { state ->
                    _leaderboardState.value = state
                    if (state is UiState.Success) {
                        val rankIndex = state.data.indexOfFirst { it.userId == uid }
                        val currentRank = if (rankIndex >= 0) rankIndex + 1 else null
                        _myCurrentRank.value = currentRank
                        val profile = _myProfile.value
                        if (profile != null) {
                            if (currentRank != null) {
                                syncPeakRankIfImproved(profile, currentRank)
                            }
                            val savedStyle = PieceStyle.fromKey(profile.pieceStyle)
                            if (!savedStyle.isUnlockedFor(true, currentRank)) {
                                preferencesManager.setPieceStyle(PieceStyle.CLASSIC)
                                repository.updateProfile(profile.nickname, profile.avatarId, PieceStyle.CLASSIC)
                            }
                        } else {
                            val localStyle = preferencesManager.settings.value.pieceStyle
                            if (!localStyle.isUnlockedFor(true, currentRank)) {
                                preferencesManager.setPieceStyle(PieceStyle.CLASSIC)
                            }
                        }
                    }
                }
        }

        _globalChatState.value = UiState.Loading
        globalChatJob = viewModelScope.launch {
            repository.observeGlobalChat()
                .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Global chat observer error", rootError)
                    val msg = rootError.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.chat_error_title)
                    emit(UiState.Error(msg))
                }
                .collect { state ->
                    _globalChatState.value = state
                }
        }

        _matchHistoryState.value = UiState.Loading
        matchHistoryJob = viewModelScope.launch {
            repository.observeMatchHistory(uid)
                .map<List<MatchRecord>, UiState<List<MatchRecord>>> { UiState.Success(it) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Match history observer error", rootError)
                    val msg = rootError.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.error_leaderboard_load)
                    emit(UiState.Error(msg))
                }
                .collect { state ->
                    _matchHistoryState.value = state
                }
        }
    }

    private fun syncPeakRankIfImproved(profile: PlayerProfile, currentRank: Int) {
        if (currentRank <= 0) return
        val needsRankUpdate = profile.bestRank <= 0L || currentRank.toLong() < profile.bestRank
        val needsPointsUpdate = profile.points > profile.bestPoints
        if (needsRankUpdate || needsPointsUpdate) {
            val nextBestRank = if (profile.bestRank <= 0L) {
                currentRank.toLong()
            } else {
                minOf(profile.bestRank, currentRank.toLong())
            }
            val nextBestPoints = maxOf(profile.bestPoints, profile.points)
            viewModelScope.launch {
                repository.updatePeakRank(nextBestRank, nextBestPoints)
            }
        }
    }

    fun stopAuthenticatedListeners() {
        profileJob?.cancel()
        profileJob = null
        leaderboardJob?.cancel()
        leaderboardJob = null
        globalChatJob?.cancel()
        globalChatJob = null
        matchHistoryJob?.cancel()
        matchHistoryJob = null
        activeObservedUid = null
        _myProfile.value = null
        _myCurrentRank.value = null
        _leaderboardState.value = UiState.Idle
        _globalChatState.value = UiState.Idle
        _matchHistoryState.value = UiState.Idle
        if (preferencesManager.settings.value.pieceStyle.isVip) {
            preferencesManager.setPieceStyle(PieceStyle.CLASSIC)
        }
    }

    fun sendGlobalChatMessage(rawText: String, onSent: () -> Unit = {}) {
        val user = _currentUser.value ?: return
        val cleanText = rawText.trim()
        if (cleanText.isEmpty()) {
            viewModelScope.launch {
                _events.emit(LobbyEvent.ShowMessage(UiText.Resource(R.string.chat_empty_input)))
            }
            return
        }
        if (cleanText.length > 300) {
            viewModelScope.launch {
                _events.emit(LobbyEvent.ShowMessage(UiText.Resource(R.string.chat_too_long)))
            }
            return
        }
        if (_isSendingGlobalChat.value) return

        viewModelScope.launch {
            _isSendingGlobalChat.value = true
            val settings = preferencesManager.settings.value
            val profile = _myProfile.value ?: PlayerProfile(
                userId = user.uid,
                nickname = user.displayName?.takeIf { it.isNotBlank() } ?: settings.localNickname,
                avatarId = settings.localAvatarId,
                pieceStyle = settings.pieceStyle.key
            )
            val result = repository.sendGlobalMessage(cleanText, profile)
            _isSendingGlobalChat.value = false
            result.onSuccess {
                onSent()
            }.onFailure { err ->
                val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.chat_error_send)
                _events.emit(LobbyEvent.ShowMessage(msg))
            }
        }
    }

    fun saveProfile(nickname: String, avatarId: String, pieceStyle: PieceStyle) {
        val cleanNick = nickname.trim()
        if (cleanNick.isEmpty() || cleanNick.length > 24) {
            viewModelScope.launch {
                _events.emit(LobbyEvent.ShowMessage(UiText.Resource(R.string.profile_nickname_error)))
            }
            return
        }
        val user = _currentUser.value
        val currentRank = _myCurrentRank.value
        if (!pieceStyle.isUnlockedFor(user != null, currentRank)) {
            val lockMsg = if (user == null) {
                UiText.Resource(R.string.vip_style_locked_sign_in)
            } else {
                UiText.Resource(R.string.vip_style_locked_rank_fmt, pieceStyle.requiredMaxRank ?: 3)
            }
            viewModelScope.launch {
                _events.emit(LobbyEvent.ShowMessage(lockMsg))
            }
            return
        }
        preferencesManager.setLocalProfile(cleanNick, avatarId, pieceStyle)
        if (user == null) {
            viewModelScope.launch {
                _events.emit(LobbyEvent.ShowMessage(UiText.Resource(R.string.profile_saved_local)))
            }
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.updateProfile(cleanNick, avatarId, pieceStyle)
            _isBusy.value = false
            if (result.isSuccess) {
                _events.emit(LobbyEvent.ShowMessage(UiText.Resource(R.string.profile_saved)))
            } else {
                val errMsg = result.exceptionOrNull()?.localizedMessage?.takeIf { it.isNotBlank() }
                    ?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.error_sync_profile, user.uid.take(4))
                _events.emit(LobbyEvent.ShowMessage(errMsg))
            }
        }
    }

    fun createOnlineRoom(boardSize: Int, blockTwoEnds: Boolean) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            val settings = preferencesManager.settings.value
            val profile = _myProfile.value ?: PlayerProfile(
                userId = user.uid,
                nickname = settings.localNickname,
                avatarId = settings.localAvatarId,
                pieceStyle = settings.pieceStyle.key
            )
            val result = repository.createRoom(boardSize, blockTwoEnds, profile)
            _isBusy.value = false
            result.onSuccess { code ->
                _events.emit(LobbyEvent.OpenOnlineRoom(code))
            }.onFailure { err ->
                val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.error_create_room)
                _events.emit(LobbyEvent.ShowMessage(msg))
            }
        }
    }

    fun joinOnlineRoom(roomCode: String) {
        val user = _currentUser.value ?: return
        val trimmed = roomCode.trim()
        if (!trimmed.matches(Regex("^[0-9]{6}$"))) {
            viewModelScope.launch {
                _events.emit(LobbyEvent.ShowMessage(UiText.Resource(R.string.error_invalid_room_code)))
            }
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val settings = preferencesManager.settings.value
            val profile = _myProfile.value ?: PlayerProfile(
                userId = user.uid,
                nickname = settings.localNickname,
                avatarId = settings.localAvatarId,
                pieceStyle = settings.pieceStyle.key
            )
            val result = repository.joinRoom(trimmed, profile)
            _isBusy.value = false
            result.onSuccess {
                _events.emit(LobbyEvent.OpenOnlineRoom(trimmed))
            }.onFailure { err ->
                val msg = when (err.message) {
                    "CANNOT_JOIN_OWN_ROOM" -> UiText.Resource(R.string.error_cannot_join_own_room)
                    "ROOM_NOT_FOUND", "ROOM_NOT_AVAILABLE" -> UiText.Resource(R.string.error_room_not_found)
                    else -> err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.error_join_room)
                }
                _events.emit(LobbyEvent.ShowMessage(msg))
            }
        }
    }

    class Factory(
        private val repository: FirebaseGameRepository,
        private val preferencesManager: PreferencesManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository, preferencesManager) as T
        }
    }

    private companion object {
        const val TAG = "MainVM"
    }
}
