package com.game.caroxo.an.model

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import com.game.caroxo.an.R
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class PlayerProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val nickname: String = "CaroPlayer",
    val avatarId: String = "ninja",
    val pieceStyle: String = PieceStyle.CLASSIC.key,
    val wins: Long = 0L,
    val losses: Long = 0L,
    val draws: Long = 0L,
    val points: Long = 100L,
    val bestRank: Long = 0L,
    val bestPoints: Long = 100L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class LeaderboardEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val nickname: String = "CaroPlayer",
    val avatarId: String = "ninja",
    val pieceStyle: String = PieceStyle.CLASSIC.key,
    val wins: Long = 0L,
    val losses: Long = 0L,
    val draws: Long = 0L,
    val points: Long = 100L,
    val publicEntry: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GameRoom(
    @DocumentId val id: String = "",
    val roomCode: String = "",
    val hostUid: String = "",
    val guestUid: String = "",
    val memberUids: List<String> = emptyList(),
    val status: String = "WAITING",
    val boardSize: Int = 15,
    val blockTwoEnds: Boolean = true,
    val hostNickname: String = "Player X",
    val hostAvatarId: String = "ninja",
    val hostStyle: String = PieceStyle.CLASSIC.key,
    val guestNickname: String = "",
    val guestAvatarId: String = "robot",
    val guestStyle: String = PieceStyle.CLASSIC.key,
    val currentTurn: String = "X",
    val moves: List<String> = emptyList(),
    val lastMoveRow: Int = -1,
    val lastMoveCol: Int = -1,
    val winnerUid: String = "",
    @ServerTimestamp val turnStartedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val senderNickname: String = "CaroPlayer",
    val senderAvatarId: String = "ninja",
    val text: String = "",
    val roomCode: String = "",
    val memberUids: List<String> = emptyList(),
    val publicChannel: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class MatchRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val opponentNickname: String = "Opponent",
    val result: String = "WIN", // "WIN", "LOSS", "DRAW"
    val mode: String = "AI_MEDIUM",
    val durationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class AvatarOption(
    val id: String,
    @StringRes val labelRes: Int,
    @DrawableRes val drawableRes: Int
)

object AvatarCatalog {
    val all: List<AvatarOption> = listOf(
        AvatarOption("ninja", R.string.avatar_ninja, R.drawable.ic_avatar_ninja),
        AvatarOption("robot", R.string.avatar_robot, R.drawable.ic_avatar_robot),
        AvatarOption("cat", R.string.avatar_cat, R.drawable.ic_avatar_cat),
        AvatarOption("knight", R.string.avatar_knight, R.drawable.ic_avatar_knight),
        AvatarOption("wizard", R.string.avatar_wizard, R.drawable.ic_avatar_wizard),
        AvatarOption("panda", R.string.avatar_panda, R.drawable.ic_avatar_panda),
        AvatarOption("fox", R.string.avatar_fox, R.drawable.ic_avatar_fox),
        AvatarOption("astronaut", R.string.avatar_astronaut, R.drawable.ic_avatar_astronaut),
        AvatarOption("samurai", R.string.avatar_samurai, R.drawable.ic_avatar_samurai),
        AvatarOption("dragon", R.string.avatar_dragon, R.drawable.ic_avatar_dragon)
    )

    fun resolveDrawable(avatarId: String?): Int {
        return all.firstOrNull { it.id == avatarId }?.drawableRes ?: R.drawable.ic_avatar_ninja
    }

    fun resolveLabel(avatarId: String?): Int {
        return all.firstOrNull { it.id == avatarId }?.labelRes ?: R.string.avatar_ninja
    }
}
