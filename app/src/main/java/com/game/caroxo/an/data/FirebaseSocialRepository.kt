package com.game.caroxo.an.data

import android.content.Context
import com.game.caroxo.an.R
import com.game.caroxo.an.model.Friendship
import com.game.caroxo.an.model.GameInvite
import com.game.caroxo.an.model.LeaderboardEntry
import com.game.caroxo.an.model.PlayerPresence
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

class FirebaseSocialRepository(
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
            ?: throw IllegalStateException("User must be signed in before accessing Firestore social features.")
    }

    fun deterministicFriendshipId(uidA: String, uidB: String): String {
        return if (uidA < uidB) "${uidA}_${uidB}" else "${uidB}_${uidA}"
    }

    fun observeFriendships(userId: String): Flow<List<Friendship>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "friendships"
        emitAll(
            db.collection(path)
                .whereArrayContains("userIds", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Friendship::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }
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

    fun observeIncomingGameInvites(userId: String): Flow<List<GameInvite>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "game_invites"
        emitAll(
            db.collection(path)
                .whereEqualTo("recipientUid", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        GameInvite::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                        .filter { it.status == "PENDING" }
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
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

    suspend fun fetchPublicPlayerProfile(targetUid: String): Result<LeaderboardEntry?> {
        val docPath = "leaderboard/$targetUid"
        return try {
            requireUserId()
            require(targetUid.matches(Regex("^[a-zA-Z0-9_\\-]{1,128}$"))) { "PLAYER_NOT_FOUND" }
            val snap = db.collection("leaderboard").document(targetUid).get().await()
            val entry = if (snap.exists()) {
                snap.toObject(
                    LeaderboardEntry::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            } else {
                null
            }
            Result.success(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                // leaderboard/{uid} allow get fails on non-existent docs because resource == null
                return Result.success(null)
            }
            handleFirestoreError(e, OperationType.GET, docPath)
            Result.failure(e)
        }
    }

    suspend fun searchPlayers(query: String): Result<List<LeaderboardEntry>> {
        val path = "leaderboard"
        return try {
            val myUid = requireUserId()
            val cleanQuery = query.trim()

            var exactById: LeaderboardEntry? = null
            if (cleanQuery.isNotEmpty() && cleanQuery.matches(Regex("^[a-zA-Z0-9_\\-]{6,128}$"))) {
                exactById = try {
                    val snap = db.collection(path).document(cleanQuery).get().await()
                    if (snap.exists()) {
                        snap.toObject(
                            LeaderboardEntry::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                } catch (_: Exception) {
                    null
                }
            }

            val topSnap = db.collection(path)
                .whereEqualTo("publicEntry", true)
                .orderBy("points", Query.Direction.DESCENDING)
                .limit(100)
                .get()
                .await()

            val allEntries = topSnap.toObjects(
                LeaderboardEntry::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ).filter { it.userId != myUid }

            val results = if (cleanQuery.isEmpty()) {
                allEntries.take(25)
            } else {
                val filtered = allEntries.filter { entry ->
                    entry.nickname.contains(cleanQuery, ignoreCase = true) ||
                        entry.userId.equals(cleanQuery, ignoreCase = true)
                }
                if (exactById != null && exactById.userId != myUid && filtered.none { it.userId == exactById.userId }) {
                    listOf(exactById) + filtered
                } else {
                    filtered
                }
            }
            Result.success(results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun sendFriendRequest(
        myProfile: PlayerProfile,
        targetEntry: LeaderboardEntry
    ): Result<String> {
        var docPath = "friendships"
        return try {
            val uid = requireUserId()
            val targetUid = targetEntry.userId.trim()
            if (targetUid.isBlank() || targetUid == uid) {
                throw IllegalStateException("CANNOT_ADD_SELF")
            }

            val docId = deterministicFriendshipId(uid, targetUid)
            val ref = db.collection("friendships").document(docId)
            docPath = ref.path

            val outcome = db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (snap.exists()) {
                    val existing = snap.toObject(
                        Friendship::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    if (existing != null) {
                        if (existing.status == "ACCEPTED") {
                            throw IllegalStateException("ALREADY_FRIENDS")
                        }
                        if (existing.requesterUid == uid) {
                            throw IllegalStateException("REQUEST_ALREADY_SENT")
                        }
                        // Target had already sent us a pending request -> accept immediately
                        tx.update(
                            ref,
                            mapOf(
                                "status" to "ACCEPTED",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        return@runTransaction "ACCEPTED"
                    }
                }

                val u1IsMe = uid < targetUid
                val u1Uid = if (u1IsMe) uid else targetUid
                val u1Nick = (if (u1IsMe) myProfile.nickname else targetEntry.nickname)
                    .trim().take(24).ifBlank { "Player" }
                val u1Avatar = (if (u1IsMe) myProfile.avatarId else targetEntry.avatarId)
                    .trim().take(32).ifBlank { "ninja" }
                val u1Points = (if (u1IsMe) myProfile.points else targetEntry.points)
                    .coerceIn(0L, 10_000_000L)

                val u2Uid = if (u1IsMe) targetUid else uid
                val u2Nick = (if (u1IsMe) targetEntry.nickname else myProfile.nickname)
                    .trim().take(24).ifBlank { "Player" }
                val u2Avatar = (if (u1IsMe) targetEntry.avatarId else myProfile.avatarId)
                    .trim().take(32).ifBlank { "ninja" }
                val u2Points = (if (u1IsMe) targetEntry.points else myProfile.points)
                    .coerceIn(0L, 10_000_000L)

                val payload = mapOf(
                    "userIds" to listOf(u1Uid, u2Uid),
                    "requesterUid" to uid,
                    "recipientUid" to targetUid,
                    "status" to "PENDING",
                    "user1Uid" to u1Uid,
                    "user1Nickname" to u1Nick,
                    "user1AvatarId" to u1Avatar,
                    "user1Points" to u1Points,
                    "user2Uid" to u2Uid,
                    "user2Nickname" to u2Nick,
                    "user2AvatarId" to u2Avatar,
                    "user2Points" to u2Points,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.set(ref, payload)
                "PENDING"
            }.await()

            Result.success(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun acceptFriendRequest(friendshipId: String): Result<Unit> {
        val docPath = "friendships/$friendshipId"
        return try {
            requireUserId()
            db.collection("friendships").document(friendshipId)
                .update(
                    mapOf(
                        "status" to "ACCEPTED",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun removeOrDeclineFriendship(friendshipId: String): Result<Unit> {
        val docPath = "friendships/$friendshipId"
        return try {
            requireUserId()
            db.collection("friendships").document(friendshipId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun syncMyInfoInAcceptedFriendships(myProfile: PlayerProfile) {
        try {
            val uid = auth.currentUser?.uid ?: return
            val snap = db.collection("friendships")
                .whereArrayContains("userIds", uid)
                .get()
                .await()
            val accepted = snap.toObjects(Friendship::class.java)
                .filter { it.status == "ACCEPTED" }
            if (accepted.isEmpty()) return

            val cleanNick = myProfile.nickname.trim().take(24).ifBlank { "Player" }
            val cleanAvatar = myProfile.avatarId.trim().take(32).ifBlank { "ninja" }
            val cleanPoints = myProfile.points.coerceIn(0L, 10_000_000L)

            db.runBatch { batch ->
                for (item in accepted) {
                    val ref = db.collection("friendships").document(item.id)
                    if (item.user1Uid == uid &&
                        (item.user1Nickname != cleanNick || item.user1AvatarId != cleanAvatar || item.user1Points != cleanPoints)
                    ) {
                        batch.update(
                            ref,
                            mapOf(
                                "user1Nickname" to cleanNick,
                                "user1AvatarId" to cleanAvatar,
                                "user1Points" to cleanPoints,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    } else if (item.user2Uid == uid &&
                        (item.user2Nickname != cleanNick || item.user2AvatarId != cleanAvatar || item.user2Points != cleanPoints)
                    ) {
                        batch.update(
                            ref,
                            mapOf(
                                "user2Nickname" to cleanNick,
                                "user2AvatarId" to cleanAvatar,
                                "user2Points" to cleanPoints,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort denormalized snapshot refresh
        }
    }

    suspend fun sendGameInvite(
        senderProfile: PlayerProfile,
        recipientUid: String,
        recipientNickname: String,
        roomCode: String,
        boardSize: Int,
        blockTwoEnds: Boolean
    ): Result<String> {
        var docPath = "game_invites"
        return try {
            val uid = requireUserId()
            require(recipientUid.isNotBlank() && recipientUid != uid) { "Invalid invite recipient" }
            require(roomCode.matches(Regex("^[0-9]{6}$"))) { "Invalid room code" }
            val validSize = if (boardSize == 10 || boardSize == 15 || boardSize == 20) boardSize else 15

            val inviteRef = db.collection("game_invites").document()
            docPath = inviteRef.path

            val payload = mapOf(
                "senderUid" to uid,
                "senderNickname" to senderProfile.nickname.trim().take(24).ifBlank { "Player" },
                "senderAvatarId" to senderProfile.avatarId.trim().take(32).ifBlank { "ninja" },
                "recipientUid" to recipientUid.trim().take(128),
                "recipientNickname" to recipientNickname.trim().take(24).ifBlank { "Friend" },
                "memberUids" to listOf(uid, recipientUid.trim().take(128)),
                "roomCode" to roomCode,
                "boardSize" to validSize,
                "blockTwoEnds" to blockTwoEnds,
                "status" to "PENDING",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            inviteRef.set(payload).await()
            Result.success(inviteRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun respondToGameInvite(inviteId: String, accept: Boolean): Result<Unit> {
        val docPath = "game_invites/$inviteId"
        return try {
            requireUserId()
            db.collection("game_invites").document(inviteId)
                .update(
                    mapOf(
                        "status" to if (accept) "ACCEPTED" else "DECLINED",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeGameInvite(inviteId: String): Flow<GameInvite?> = flow {
        require(inviteId.isNotBlank()) { "Invite ID must not be blank" }
        val docRef = db.collection("game_invites").document(inviteId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            GameInvite::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    emit(null)
                }
        )
    }

    suspend fun cancelRoomInvites(roomCode: String) {
        try {
            val uid = auth.currentUser?.uid ?: return
            val snap = db.collection("game_invites")
                .whereEqualTo("senderUid", uid)
                .get()
                .await()
            val pending = snap.documents.filter { doc ->
                doc.getString("roomCode") == roomCode && doc.getString("status") == "PENDING"
            }
            if (pending.isEmpty()) return
            db.runBatch { batch ->
                for (doc in pending) {
                    batch.update(
                        doc.reference,
                        mapOf(
                            "status" to "CANCELLED",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort invite cleanup
        }
    }

    suspend fun updateMyPresence(online: Boolean) {
        try {
            val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return
            db.collection("presence").document(uid)
                .set(
                    mapOf(
                        "userId" to uid,
                        "online" to online,
                        "lastActiveAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort heartbeat: avoid crashing or spamming logs if rules aren't published yet
        }
    }

    fun observeFriendPresence(friendUid: String): Flow<PlayerPresence?> = flow {
        require(friendUid.isNotBlank()) { "Friend UID must not be blank" }
        val docRef = db.collection("presence").document(friendUid)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            PlayerPresence::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    emit(null)
                }
        )
    }
}
