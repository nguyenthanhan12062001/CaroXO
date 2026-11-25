package com.game.caroxo.an.data

import android.content.Context
import com.game.caroxo.an.R
import com.game.caroxo.an.model.ChatMessage
import com.game.caroxo.an.model.GameRoom
import com.game.caroxo.an.model.LeaderboardEntry
import com.game.caroxo.an.model.Piece
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.PlayerProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class FirebaseGameRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        if (context.applicationContext.getString(R.string.firestore_database_id) == "(default)") {
            FirebaseFirestore.getInstance()
        } else {
            FirebaseFirestore.getInstance(context.applicationContext.getString(R.string.firestore_database_id))
        }
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    suspend fun ensureProfileInitialized(
        defaultNickname: String,
        defaultAvatarId: String,
        defaultPieceStyle: PieceStyle
    ): Result<PlayerProfile> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            val userRef = db.collection("users").document(uid)
            val lbRef = db.collection("leaderboard").document(uid)
            docPath = userRef.path

            val snap = userRef.get().await()
            if (snap.exists()) {
                val existing = snap.toObject(
                    PlayerProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existing != null) {
                    // Ensure leaderboard entry also exists
                    val lbSnap = lbRef.get().await()
                    if (!lbSnap.exists()) {
                        val lbPayload = mapOf(
                            "userId" to uid,
                            "nickname" to existing.nickname,
                            "avatarId" to existing.avatarId,
                            "pieceStyle" to existing.pieceStyle,
                            "wins" to existing.wins,
                            "losses" to existing.losses,
                            "draws" to existing.draws,
                            "points" to existing.points,
                            "publicEntry" to true,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        lbRef.set(lbPayload).await()
                    }
                    return Result.success(existing)
                }
            }

            val cleanNick = defaultNickname.trim().take(24).ifBlank { "Player_${uid.take(5)}" }
            val userPayload = mapOf(
                "userId" to uid,
                "nickname" to cleanNick,
                "avatarId" to defaultAvatarId.ifBlank { "ninja" },
                "pieceStyle" to defaultPieceStyle.key,
                "wins" to 0L,
                "losses" to 0L,
                "draws" to 0L,
                "points" to 100L,
                "bestRank" to 0L,
                "bestPoints" to 100L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val lbPayload = mapOf(
                "userId" to uid,
                "nickname" to cleanNick,
                "avatarId" to defaultAvatarId.ifBlank { "ninja" },
                "pieceStyle" to defaultPieceStyle.key,
                "wins" to 0L,
                "losses" to 0L,
                "draws" to 0L,
                "points" to 100L,
                "publicEntry" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.runBatch { batch ->
                batch.set(userRef, userPayload)
                batch.set(lbRef, lbPayload)
            }.await()

            val created = PlayerProfile(
                id = uid,
                userId = uid,
                nickname = cleanNick,
                avatarId = defaultAvatarId.ifBlank { "ninja" },
                pieceStyle = defaultPieceStyle.key,
                wins = 0L,
                losses = 0L,
                draws = 0L,
                points = 100L,
                bestRank = 0L,
                bestPoints = 100L
            )
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    fun observeMyProfile(userId: String): Flow<PlayerProfile?> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val docRef = db.collection("users").document(userId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            PlayerProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun updateProfile(
        nickname: String,
        avatarId: String,
        pieceStyle: PieceStyle
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            val cleanNick = nickname.trim()
            require(cleanNick.length in 1..24) { "Nickname must be 1..24 characters" }
            val userRef = db.collection("users").document(uid)
            val lbRef = db.collection("leaderboard").document(uid)
            docPath = userRef.path

            val updates = mapOf(
                "nickname" to cleanNick,
                "avatarId" to avatarId,
                "pieceStyle" to pieceStyle.key,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.runBatch { batch ->
                batch.update(userRef, updates)
                batch.update(lbRef, updates)
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updatePeakRank(bestRank: Long, bestPoints: Long): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            val userRef = db.collection("users").document(uid)
            docPath = userRef.path
            db.runTransaction { tx ->
                val snap = tx.get(userRef)
                if (!snap.exists()) return@runTransaction
                val currentBestRank = snap.getLong("bestRank") ?: 0L
                val currentBestPoints = snap.getLong("bestPoints") ?: 100L
                val nextBestRank = when {
                    bestRank <= 0L -> currentBestRank
                    currentBestRank <= 0L -> bestRank
                    else -> minOf(currentBestRank, bestRank)
                }
                val nextBestPoints = maxOf(currentBestPoints, bestPoints)
                if (nextBestRank != currentBestRank || nextBestPoints != currentBestPoints ||
                    !snap.contains("bestRank") || !snap.contains("bestPoints")
                ) {
                    tx.update(
                        userRef,
                        mapOf(
                            "bestRank" to nextBestRank,
                            "bestPoints" to nextBestPoints,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeLeaderboardTop20(): Flow<List<LeaderboardEntry>> = flow {
        val uid = requireUserId()
        val path = "leaderboard"
        emitAll(
            db.collection(path)
                .whereEqualTo("publicEntry", true)
                .orderBy("points", Query.Direction.DESCENDING)
                .limit(20)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        LeaderboardEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun recordMatchOutcome(won: Boolean, draw: Boolean): Result<Long> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            val userRef = db.collection("users").document(uid)
            val lbRef = db.collection("leaderboard").document(uid)
            docPath = userRef.path

            val delta = db.runTransaction { tx ->
                val snap = tx.get(userRef)
                val currentWins = snap.getLong("wins") ?: 0L
                val currentLosses = snap.getLong("losses") ?: 0L
                val currentDraws = snap.getLong("draws") ?: 0L
                val currentPoints = snap.getLong("points") ?: 100L
                val currentBestPoints = snap.getLong("bestPoints") ?: currentPoints

                val (newWins, newLosses, newDraws, newPoints, pointChange) = when {
                    draw -> listOf(currentWins, currentLosses, currentDraws + 1L, currentPoints, 0L)
                    won -> listOf(currentWins + 1L, currentLosses, currentDraws, currentPoints + 10L, 10L)
                    else -> {
                        val updatedPts = (currentPoints - 5L).coerceAtLeast(0L)
                        listOf(currentWins, currentLosses + 1L, currentDraws, updatedPts, updatedPts - currentPoints)
                    }
                }
                val newBestPoints = maxOf(currentBestPoints, newPoints)

                val lbUpdateMap = mapOf(
                    "wins" to newWins,
                    "losses" to newLosses,
                    "draws" to newDraws,
                    "points" to newPoints,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val userUpdateMap = lbUpdateMap + ("bestPoints" to newBestPoints)
                tx.update(userRef, userUpdateMap)
                tx.update(lbRef, lbUpdateMap)
                pointChange
            }.await()
            Result.success(delta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createRoom(
        boardSize: Int,
        blockTwoEnds: Boolean,
        hostProfile: PlayerProfile
    ): Result<String> {
        var docPath = "rooms"
        return try {
            val uid = requireUserId()
            val validSize = if (boardSize == 10 || boardSize == 15 || boardSize == 20) boardSize else 15
            var generatedCode = ""
            repeat(5) {
                val candidate = Random.nextInt(100000, 999999).toString()
                val ref = db.collection("rooms").document(candidate)
                val existing = ref.get().await()
                if (!existing.exists()) {
                    generatedCode = candidate
                    return@repeat
                }
            }
            if (generatedCode.isEmpty()) {
                generatedCode = Random.nextInt(100000, 999999).toString()
            }
            val roomRef = db.collection("rooms").document(generatedCode)
            docPath = roomRef.path

            val payload = mapOf(
                "roomCode" to generatedCode,
                "hostUid" to uid,
                "guestUid" to "",
                "memberUids" to listOf(uid),
                "status" to "WAITING",
                "boardSize" to validSize,
                "blockTwoEnds" to blockTwoEnds,
                "hostNickname" to hostProfile.nickname.take(24).ifBlank { "Player X" },
                "hostAvatarId" to hostProfile.avatarId.ifBlank { "ninja" },
                "hostStyle" to PieceStyle.fromKey(hostProfile.pieceStyle).key,
                "guestNickname" to "",
                "guestAvatarId" to "",
                "guestStyle" to PieceStyle.CLASSIC.key,
                "currentTurn" to "X",
                "moves" to emptyList<String>(),
                "lastMoveRow" to -1,
                "lastMoveCol" to -1,
                "winnerUid" to "",
                "turnStartedAt" to FieldValue.serverTimestamp(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            roomRef.set(payload).await()
            Result.success(generatedCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun joinRoom(
        roomCode: String,
        guestProfile: PlayerProfile
    ): Result<GameRoom> {
        val docPath = "rooms/$roomCode"
        return try {
            val uid = requireUserId()
            require(roomCode.matches(Regex("^[0-9]{6}$"))) { "Invalid 6-digit room code" }
            val roomRef = db.collection("rooms").document(roomCode)

            val joinedRoom = db.runTransaction { tx ->
                val snap = tx.get(roomRef)
                if (!snap.exists()) {
                    throw IllegalStateException("ROOM_NOT_FOUND")
                }
                val room = snap.toObject(
                    GameRoom::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: throw IllegalStateException("ROOM_NOT_FOUND")

                if (room.hostUid == uid) {
                    throw IllegalStateException("CANNOT_JOIN_OWN_ROOM")
                }
                if (room.status != "WAITING" || room.guestUid.isNotEmpty()) {
                    throw IllegalStateException("ROOM_NOT_AVAILABLE")
                }

                val guestNick = guestProfile.nickname.take(24).ifBlank { "Player O" }
                val guestAvatar = guestProfile.avatarId.ifBlank { "robot" }
                val guestStyle = PieceStyle.fromKey(guestProfile.pieceStyle).key

                tx.update(
                    roomRef,
                    mapOf(
                        "guestUid" to uid,
                        "memberUids" to listOf(room.hostUid, uid),
                        "status" to "PLAYING",
                        "guestNickname" to guestNick,
                        "guestAvatarId" to guestAvatar,
                        "guestStyle" to guestStyle,
                        "turnStartedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                room.copy(
                    guestUid = uid,
                    memberUids = listOf(room.hostUid, uid),
                    status = "PLAYING",
                    guestNickname = guestNick,
                    guestAvatarId = guestAvatar,
                    guestStyle = guestStyle
                )
            }.await()
            Result.success(joinedRoom)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeRoom(roomCode: String): Flow<GameRoom?> = flow {
        val uid = requireUserId()
        val roomRef = db.collection("rooms").document(roomCode)
        emitAll(
            roomRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            GameRoom::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, roomRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun submitOnlineMove(
        roomCode: String,
        row: Int,
        col: Int,
        piece: Piece,
        updatedMoves: List<String>,
        nextTurn: String,
        newStatus: String,
        winnerUid: String
    ): Result<Unit> {
        val docPath = "rooms/$roomCode"
        return try {
            requireUserId()
            val roomRef = db.collection("rooms").document(roomCode)
            roomRef.update(
                mapOf(
                    "moves" to updatedMoves,
                    "lastMoveRow" to row,
                    "lastMoveCol" to col,
                    "currentTurn" to nextTurn,
                    "status" to newStatus,
                    "winnerUid" to winnerUid,
                    "turnStartedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun finishRoom(
        roomCode: String,
        winnerUid: String
    ): Result<Unit> {
        val docPath = "rooms/$roomCode"
        return try {
            requireUserId()
            val roomRef = db.collection("rooms").document(roomCode)
            roomRef.update(
                mapOf(
                    "status" to "FINISHED",
                    "winnerUid" to winnerUid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeGlobalChat(): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        val path = "global_chat"
        emitAll(
            db.collection(path)
                .whereEqualTo("publicChannel", true)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(60)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).reversed()
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun sendGlobalMessage(
        text: String,
        senderProfile: PlayerProfile
    ): Result<Unit> {
        var docPath = "global_chat"
        return try {
            val uid = requireUserId()
            val cleanText = text.trim()
            require(cleanText.length in 1..300) { "Chat message must be 1..300 characters" }

            val msgRef = db.collection("global_chat").document()
            docPath = msgRef.path
            val payload = mapOf(
                "senderUid" to uid,
                "senderNickname" to senderProfile.nickname.trim().take(24).ifBlank { "Player_${uid.take(5)}" },
                "senderAvatarId" to senderProfile.avatarId.ifBlank { "ninja" }.take(32),
                "text" to cleanText,
                "publicChannel" to true,
                "createdAt" to FieldValue.serverTimestamp()
            )
            msgRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    fun observeRoomChat(roomCode: String): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        val path = "rooms/$roomCode/messages"
        emitAll(
            db.collection("rooms")
                .document(roomCode)
                .collection("messages")
                .whereArrayContains("memberUids", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).reversed()
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun sendRoomMessage(
        roomCode: String,
        memberUids: List<String>,
        text: String,
        senderProfile: PlayerProfile
    ): Result<Unit> {
        var docPath = "rooms/$roomCode/messages"
        return try {
            val uid = requireUserId()
            require(roomCode.matches(Regex("^[0-9]{6}$"))) { "Invalid 6-digit room code" }
            require(uid in memberUids && memberUids.size in 1..2) { "Sender is not a room member" }
            val cleanText = text.trim()
            require(cleanText.length in 1..300) { "Chat message must be 1..300 characters" }

            val msgRef = db.collection("rooms")
                .document(roomCode)
                .collection("messages")
                .document()
            docPath = msgRef.path
            val payload = mapOf(
                "senderUid" to uid,
                "senderNickname" to senderProfile.nickname.trim().take(24).ifBlank { "Player_${uid.take(5)}" },
                "senderAvatarId" to senderProfile.avatarId.ifBlank { "ninja" }.take(32),
                "text" to cleanText,
                "roomCode" to roomCode,
                "memberUids" to memberUids,
                "createdAt" to FieldValue.serverTimestamp()
            )
            msgRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    fun observeMatchHistory(userId: String): Flow<List<com.game.caroxo.an.model.MatchRecord>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "users/$userId/match_history"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("match_history")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(10)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        com.game.caroxo.an.model.MatchRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun recordMatch(
        opponentNickname: String,
        result: String,
        mode: String,
        durationSeconds: Long
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/match_history"
        return try {
            val uid = requireUserId()
            val cleanOpponent = opponentNickname.trim().take(24).ifBlank { "Opponent" }
            val cleanResult = if (result in listOf("WIN", "LOSS", "DRAW")) result else "DRAW"
            val cleanMode = mode.take(32).ifBlank { "AI_MEDIUM" }
            val cleanDuration = durationSeconds.coerceIn(0L, 86400L)

            val matchRef = db.collection("users")
                .document(uid)
                .collection("match_history")
                .document()
            docPath = matchRef.path

            val payload = mapOf(
                "userId" to uid,
                "opponentNickname" to cleanOpponent,
                "result" to cleanResult,
                "mode" to cleanMode,
                "durationSeconds" to cleanDuration,
                "createdAt" to FieldValue.serverTimestamp()
            )
            matchRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }
}
