package com.game.caroxo.an.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.game.caroxo.an.R
import com.game.caroxo.an.data.AuthManager
import com.game.caroxo.an.data.FirebaseGameRepository
import com.game.caroxo.an.data.FirebaseSocialRepository
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.model.AdminConfig
import com.game.caroxo.an.model.ChatMessage
import com.game.caroxo.an.model.Friendship
import com.game.caroxo.an.model.GameInvite
import com.game.caroxo.an.model.LeaderboardEntry
import com.game.caroxo.an.model.MatchRecord
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.PlayerPresence
import com.game.caroxo.an.model.PlayerProfile
import com.game.caroxo.an.model.UiText
import com.game.caroxo.an.model.otherUserUid
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

data class ViewedPlayerProfile(
    val entry: LeaderboardEntry,
    val rank: Int?
)

class MainViewModel(
    private val repository: FirebaseGameRepository,
    private val socialRepository: FirebaseSocialRepository,
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

    private val _friendshipsState = MutableStateFlow<UiState<List<Friendship>>>(UiState.Idle)
    val friendshipsState: StateFlow<UiState<List<Friendship>>> = _friendshipsState.asStateFlow()

    private val _incomingGameInvites = MutableStateFlow<List<GameInvite>>(emptyList())
    val incomingGameInvites: StateFlow<List<GameInvite>> = _incomingGameInvites.asStateFlow()

    private val _pendingSocialCount = MutableStateFlow(0)
    val pendingSocialCount: StateFlow<Int> = _pendingSocialCount.asStateFlow()

    private val _friendsOnlineMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val friendsOnlineMap: StateFlow<Map<String, Boolean>> = _friendsOnlineMap.asStateFlow()

    private val _playerSearchState = MutableStateFlow<UiState<List<LeaderboardEntry>>>(UiState.Idle)
    val playerSearchState: StateFlow<UiState<List<LeaderboardEntry>>> = _playerSearchState.asStateFlow()

    private val _viewedPlayerProfileState = MutableStateFlow<UiState<ViewedPlayerProfile>>(UiState.Idle)
    val viewedPlayerProfileState: StateFlow<UiState<ViewedPlayerProfile>> = _viewedPlayerProfileState.asStateFlow()

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
    private var friendshipsJob: Job? = null
    private var gameInvitesJob: Job? = null
    private var presenceHeartbeatJob: Job? = null
    private var friendsPresenceJob: Job? = null
    private var searchJob: Job? = null
    private var profileLookupJob: Job? = null
    private var activeObservedUid: String? = null
    private var observedFriendUids: List<String> = emptyList()
    private var isAppForeground = false

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
                        val isAdmin = AdminConfig.isAdmin(uid)
                        val effectiveStyle = if (lbReady && !currentStyle.isUnlockedFor(true, _myCurrentRank.value, isAdmin)) {
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
                        launch {
                            socialRepository.syncMyInfoInAcceptedFriendships(profile)
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
                        val isAdmin = AdminConfig.isAdmin(uid)
                        if (profile != null) {
                            if (currentRank != null) {
                                syncPeakRankIfImproved(profile, currentRank)
                            }
                            val savedStyle = PieceStyle.fromKey(profile.pieceStyle)
                            if (!savedStyle.isUnlockedFor(true, currentRank, isAdmin)) {
                                preferencesManager.setPieceStyle(PieceStyle.CLASSIC)
                                repository.updateProfile(profile.nickname, profile.avatarId, PieceStyle.CLASSIC)
                            }
                        } else {
                            val localStyle = preferencesManager.settings.value.pieceStyle
                            if (!localStyle.isUnlockedFor(true, currentRank, isAdmin)) {
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

        _friendshipsState.value = UiState.Loading
        friendshipsJob = viewModelScope.launch {
            socialRepository.observeFriendships(uid)
                .map<List<Friendship>, UiState<List<Friendship>>> { UiState.Success(it) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Friendships observer error", rootError)
                    val msg = rootError.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.friends_error_load)
                    emit(UiState.Error(msg))
                }
                .collect { state ->
                    _friendshipsState.value = state
                    refreshPendingSocialCount(uid)
                    if (state is UiState.Success) {
                        val acceptedFriendUids = state.data
                            .filter { it.status == "ACCEPTED" }
                            .map { it.otherUserUid(uid) }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted()
                        refreshFriendsPresenceObserver(acceptedFriendUids)
                    }
                }
        }

        gameInvitesJob = viewModelScope.launch {
            socialRepository.observeIncomingGameInvites(uid)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Game invites observer error", firestoreError ?: error)
                }
                .collect { invites ->
                    _incomingGameInvites.value = invites
                    refreshPendingSocialCount(uid)
                }
        }

        if (isAppForeground) {
            startPresenceHeartbeat()
        }
    }

    fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
        if (activeObservedUid == null && _currentUser.value == null) return
        if (foreground) {
            startPresenceHeartbeat()
        } else {
            presenceHeartbeatJob?.cancel()
            presenceHeartbeatJob = null
            viewModelScope.launch {
                withContext(NonCancellable) {
                    socialRepository.updateMyPresence(false)
                }
            }
        }
    }

    suspend fun markOfflineBeforeSignOut() {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
        withContext(NonCancellable) {
            socialRepository.updateMyPresence(false)
        }
    }

    private fun startPresenceHeartbeat() {
        if (presenceHeartbeatJob?.isActive == true) return
        presenceHeartbeatJob = viewModelScope.launch {
            while (isActive) {
                socialRepository.updateMyPresence(true)
                delay(PRESENCE_HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun refreshFriendsPresenceObserver(friendUids: List<String>) {
        if (friendUids == observedFriendUids && friendsPresenceJob?.isActive == true) return
        observedFriendUids = friendUids
        friendsPresenceJob?.cancel()
        if (friendUids.isEmpty()) {
            _friendsOnlineMap.value = emptyMap()
            return
        }
        friendsPresenceJob = viewModelScope.launch {
            val presenceFlows = friendUids.map { friendUid ->
                socialRepository.observeFriendPresence(friendUid).map { presence -> friendUid to presence }
            }
            val rawMapFlow = combine(presenceFlows) { pairs -> pairs.toMap() }
            val tickerFlow = flow {
                while (true) {
                    emit(Unit)
                    delay(PRESENCE_TTL_CHECK_INTERVAL_MS)
                }
            }
            combine(rawMapFlow, tickerFlow) { map, _ ->
                val now = System.currentTimeMillis()
                map.mapValues { (_, presence) -> presence?.isCurrentlyOnline(now) == true }
            }.collect { statusMap ->
                _friendsOnlineMap.value = statusMap
            }
        }
    }

    private fun refreshPendingSocialCount(myUid: String) {
        val friendships = (_friendshipsState.value as? UiState.Success)?.data.orEmpty()
        val incomingRequests = friendships.count { it.status == "PENDING" && it.recipientUid == myUid }
        val pendingInvites = _incomingGameInvites.value.count { it.status == "PENDING" }
        _pendingSocialCount.value = incomingRequests + pendingInvites
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
        friendshipsJob?.cancel()
        friendshipsJob = null
        gameInvitesJob?.cancel()
        gameInvitesJob = null
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
        friendsPresenceJob?.cancel()
        friendsPresenceJob = null
        searchJob?.cancel()
        searchJob = null
        profileLookupJob?.cancel()
        profileLookupJob = null
        activeObservedUid = null
        observedFriendUids = emptyList()
        _myProfile.value = null
        _myCurrentRank.value = null
        _leaderboardState.value = UiState.Idle
        _globalChatState.value = UiState.Idle
        _matchHistoryState.value = UiState.Idle
        _friendshipsState.value = UiState.Idle
        _incomingGameInvites.value = emptyList()
        _pendingSocialCount.value = 0
        _friendsOnlineMap.value = emptyMap()
        _playerSearchState.value = UiState.Idle
        _viewedPlayerProfileState.value = UiState.Idle
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

    fun clearGlobalChatHistory() {
        val user = _currentUser.value ?: return
        if (!AdminConfig.isAdmin(user.uid)) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.clearGlobalChatHistory()
            _isBusy.value = false
            result.onSuccess { deletedCount ->
                val msg = if (deletedCount > 0) {
                    UiText.Resource(R.string.admin_chat_cleared_toast, deletedCount)
                } else {
                    UiText.Resource(R.string.admin_chat_already_empty_toast)
                }
                _events.emit(LobbyEvent.ShowMessage(msg))
            }.onFailure { err ->
                val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.admin_chat_clear_error)
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
        val isAdmin = AdminConfig.isAdmin(user?.uid)
        if (!pieceStyle.isUnlockedFor(user != null, currentRank, isAdmin)) {
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

    fun friendshipWith(targetUid: String): Friendship? {
        val friendships = (_friendshipsState.value as? UiState.Success)?.data.orEmpty()
        return friendships.firstOrNull { targetUid in it.userIds }
    }

    fun searchPlayers(query: String = "") {
        if (_currentUser.value == null) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _playerSearchState.value = UiState.Loading
            val res = socialRepository.searchPlayers(query)
            res.onSuccess { list ->
                _playerSearchState.value = UiState.Success(list)
            }.onFailure { err ->
                val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.friends_search_error)
                _playerSearchState.value = UiState.Error(msg)
            }
        }
    }

    fun loadPlayerProfile(
        targetUid: String,
        fallbackNickname: String? = null,
        fallbackAvatarId: String? = null
    ) {
        if (_currentUser.value == null) return
        profileLookupJob?.cancel()
        val cachedRank = (_leaderboardState.value as? UiState.Success)?.data
            ?.indexOfFirst { it.userId == targetUid }
            ?.takeIf { it >= 0 }
            ?.plus(1)
        val cachedEntry = (_leaderboardState.value as? UiState.Success)?.data
            ?.firstOrNull { it.userId == targetUid }
        if (cachedEntry != null) {
            _viewedPlayerProfileState.value = UiState.Success(ViewedPlayerProfile(cachedEntry, cachedRank))
        } else {
            _viewedPlayerProfileState.value = UiState.Loading
        }

        profileLookupJob = viewModelScope.launch {
            val res = socialRepository.fetchPublicPlayerProfile(targetUid)
            res.onSuccess { entry ->
                if (entry != null) {
                    _viewedPlayerProfileState.value = UiState.Success(ViewedPlayerProfile(entry, cachedRank))
                } else if (!fallbackNickname.isNullOrBlank()) {
                    _viewedPlayerProfileState.value = UiState.Success(
                        ViewedPlayerProfile(
                            entry = LeaderboardEntry(
                                userId = targetUid,
                                nickname = fallbackNickname,
                                avatarId = fallbackAvatarId.orEmpty().ifBlank { "ninja" },
                                publicEntry = false
                            ),
                            rank = null
                        )
                    )
                } else {
                    _viewedPlayerProfileState.value = UiState.Error(UiText.Resource(R.string.player_profile_not_found))
                }
            }.onFailure { err ->
                if (cachedEntry == null) {
                    val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.player_profile_not_found)
                    _viewedPlayerProfileState.value = UiState.Error(msg)
                }
            }
        }
    }

    fun sendFriendRequest(targetEntry: LeaderboardEntry) {
        val user = _currentUser.value ?: return
        if (targetEntry.userId == user.uid) return
        viewModelScope.launch {
            _isBusy.value = true
            val profile = currentFallbackProfile(user)
            val result = socialRepository.sendFriendRequest(profile, targetEntry)
            _isBusy.value = false
            result.onSuccess { status ->
                val msg = if (status == "ACCEPTED") {
                    UiText.Resource(R.string.friend_request_accepted_toast)
                } else {
                    UiText.Resource(R.string.friend_request_sent_toast, targetEntry.nickname)
                }
                _events.emit(LobbyEvent.ShowMessage(msg))
            }.onFailure { err ->
                val msg = when (err.message) {
                    "ALREADY_FRIENDS" -> UiText.Resource(R.string.friend_error_already_friends)
                    "REQUEST_ALREADY_SENT" -> UiText.Resource(R.string.friend_error_request_already_sent)
                    "CANNOT_ADD_SELF" -> UiText.Resource(R.string.friend_error_add_self)
                    else -> err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.friend_error_generic)
                }
                _events.emit(LobbyEvent.ShowMessage(msg))
            }
        }
    }

    fun acceptFriendRequest(friendshipId: String) {
        if (_currentUser.value == null) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = socialRepository.acceptFriendRequest(friendshipId)
            _isBusy.value = false
            res.onSuccess {
                _events.emit(LobbyEvent.ShowMessage(UiText.Resource(R.string.friend_request_accepted_toast)))
            }.onFailure { err ->
                val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.friend_error_generic)
                _events.emit(LobbyEvent.ShowMessage(msg))
            }
        }
    }

    fun removeOrDeclineFriendship(friendshipId: String, successMessageRes: Int) {
        if (_currentUser.value == null) return
        viewModelScope.launch {
            _isBusy.value = true
            val res = socialRepository.removeOrDeclineFriendship(friendshipId)
            _isBusy.value = false
            res.onSuccess {
                _events.emit(LobbyEvent.ShowMessage(UiText.Resource(successMessageRes)))
            }.onFailure { err ->
                val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.friend_error_generic)
                _events.emit(LobbyEvent.ShowMessage(msg))
            }
        }
    }

    fun inviteFriendToPlay(
        friendUid: String,
        friendNickname: String,
        boardSize: Int,
        blockTwoEnds: Boolean
    ) {
        val user = _currentUser.value ?: return
        if (friendUid == user.uid) return
        viewModelScope.launch {
            _isBusy.value = true
            val profile = currentFallbackProfile(user)
            val roomRes = repository.createRoom(boardSize, blockTwoEnds, profile)
            roomRes.onSuccess { roomCode ->
                val inviteRes = socialRepository.sendGameInvite(
                    senderProfile = profile,
                    recipientUid = friendUid,
                    recipientNickname = friendNickname,
                    roomCode = roomCode,
                    boardSize = boardSize,
                    blockTwoEnds = blockTwoEnds
                )
                _isBusy.value = false
                inviteRes.onSuccess {
                    _events.emit(LobbyEvent.ShowMessage(UiText.Resource(R.string.invite_sent_toast, friendNickname)))
                    _events.emit(LobbyEvent.OpenOnlineRoom(roomCode))
                }.onFailure { err ->
                    repository.finishRoom(roomCode, "CANCELLED")
                    val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.invite_error_send)
                    _events.emit(LobbyEvent.ShowMessage(msg))
                }
            }.onFailure { err ->
                _isBusy.value = false
                val msg = err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                    ?: UiText.Resource(R.string.error_create_room)
                _events.emit(LobbyEvent.ShowMessage(msg))
            }
        }
    }

    fun acceptGameInvite(invite: GameInvite) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            val profile = currentFallbackProfile(user)
            val joinRes = repository.joinRoom(invite.roomCode, profile)
            joinRes.onSuccess {
                socialRepository.respondToGameInvite(invite.id, accept = true)
                _isBusy.value = false
                _events.emit(LobbyEvent.OpenOnlineRoom(invite.roomCode))
            }.onFailure { err ->
                socialRepository.respondToGameInvite(invite.id, accept = false)
                _isBusy.value = false
                val msg = when (err.message) {
                    "CANNOT_JOIN_OWN_ROOM" -> UiText.Resource(R.string.error_cannot_join_own_room)
                    "ROOM_NOT_FOUND", "ROOM_NOT_AVAILABLE" -> UiText.Resource(R.string.invite_room_expired)
                    else -> err.localizedMessage?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                        ?: UiText.Resource(R.string.error_join_room)
                }
                _events.emit(LobbyEvent.ShowMessage(msg))
            }
        }
    }

    fun declineGameInvite(invite: GameInvite) {
        if (_currentUser.value == null) return
        viewModelScope.launch {
            socialRepository.respondToGameInvite(invite.id, accept = false)
        }
    }

    private fun currentFallbackProfile(user: FirebaseUser): PlayerProfile {
        val settings = preferencesManager.settings.value
        return _myProfile.value ?: PlayerProfile(
            userId = user.uid,
            nickname = user.displayName?.takeIf { it.isNotBlank() } ?: settings.localNickname,
            avatarId = settings.localAvatarId,
            pieceStyle = settings.pieceStyle.key
        )
    }

    class Factory(
        private val repository: FirebaseGameRepository,
        private val socialRepository: FirebaseSocialRepository,
        private val preferencesManager: PreferencesManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository, socialRepository, preferencesManager) as T
        }
    }

    private companion object {
        const val TAG = "MainVM"
        const val PRESENCE_HEARTBEAT_INTERVAL_MS = 30_000L
        const val PRESENCE_TTL_CHECK_INTERVAL_MS = 15_000L
    }
}
