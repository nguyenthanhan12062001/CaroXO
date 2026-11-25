package com.game.caroxo.an.model

import android.content.Context
import androidx.annotation.StringRes

enum class Piece(val code: Int) {
    EMPTY(0),
    X(1),
    O(2);

    fun opponent(): Piece = when (this) {
        X -> O
        O -> X
        EMPTY -> EMPTY
    }

    companion object {
        fun fromCode(code: Int): Piece = when (code) {
            1 -> X
            2 -> O
            else -> EMPTY
        }
    }
}

enum class GameMode {
    PASS_AND_PLAY,
    AI_EASY,
    AI_MEDIUM,
    AI_HARD,
    ONLINE;

    fun isAi(): Boolean = this == AI_EASY || this == AI_MEDIUM || this == AI_HARD
}

enum class PieceStyle(
    val key: String,
    val requiredMaxRank: Int? = null
) {
    CLASSIC("CLASSIC"),
    NEON_CYBER("NEON_CYBER"),
    WOOD_CLASSIC("WOOD_CLASSIC"),
    SYMBOL("SYMBOL"),
    VIP_CRIMSON_EMBER("VIP_CRIMSON_EMBER", requiredMaxRank = 3),
    VIP_PLASMA_VORTEX("VIP_PLASMA_VORTEX", requiredMaxRank = 2),
    VIP_CELESTIAL_MONARCH("VIP_CELESTIAL_MONARCH", requiredMaxRank = 1);

    val isVip: Boolean get() = requiredMaxRank != null

    fun isUnlockedFor(isSignedIn: Boolean, currentRank: Int?): Boolean {
        val maxRank = requiredMaxRank ?: return true
        if (!isSignedIn || currentRank == null || currentRank <= 0) return false
        return currentRank <= maxRank
    }

    companion object {
        fun fromKey(raw: String?): PieceStyle =
            entries.firstOrNull { it.key == raw } ?: CLASSIC
    }
}

enum class RankTier {
    TOP_1_MONARCH,
    TOP_2_SOVEREIGN,
    TOP_3_VANGUARD,
    LEGEND,
    GRANDMASTER,
    MASTER,
    TACTICIAN,
    APPRENTICE,
    NOVICE;

    val isTop3: Boolean
        get() = this == TOP_1_MONARCH || this == TOP_2_SOVEREIGN || this == TOP_3_VANGUARD

    companion object {
        fun resolve(points: Long, rank: Int? = null): RankTier = when (rank) {
            1 -> TOP_1_MONARCH
            2 -> TOP_2_SOVEREIGN
            3 -> TOP_3_VANGUARD
            else -> when {
                points >= 450L -> LEGEND
                points >= 300L -> GRANDMASTER
                points >= 220L -> MASTER
                points >= 160L -> TACTICIAN
                points >= 120L -> APPRENTICE
                else -> NOVICE
            }
        }
    }
}

enum class BoardSurface(val key: String) {
    MINIMAL_CREAM("MINIMAL_CREAM"),
    WARM_WOOD("WARM_WOOD"),
    EMERALD_GRID("EMERALD_GRID"),
    DARK_SLATE("DARK_SLATE");

    companion object {
        fun fromKey(raw: String?): BoardSurface =
            entries.firstOrNull { it.key == raw } ?: MINIMAL_CREAM
    }
}

enum class ThemeMode(val key: String) {
    SYSTEM("SYSTEM"),
    LIGHT("LIGHT"),
    DARK("DARK"),
    AMOLED("AMOLED");

    companion object {
        fun fromKey(raw: String?): ThemeMode =
            entries.firstOrNull { it.key == raw } ?: SYSTEM
    }
}

enum class AppLanguage(val key: String, val languageTag: String) {
    SYSTEM("SYSTEM", ""),
    VIETNAMESE("VI", "vi"),
    ENGLISH("EN", "en"),
    CHINESE_SIMPLIFIED("ZH", "zh-CN"),
    INDONESIAN("ID", "in"),
    KOREAN("KO", "ko"),
    JAPANESE("JA", "ja");

    companion object {
        fun fromKey(raw: String?): AppLanguage =
            entries.firstOrNull { it.key == raw } ?: SYSTEM

        fun fromLocaleTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            val lower = tag.lowercase()
            return when {
                lower.startsWith("vi") -> VIETNAMESE
                lower.startsWith("en") -> ENGLISH
                lower.startsWith("zh") -> CHINESE_SIMPLIFIED
                lower.startsWith("id") || lower.startsWith("in") -> INDONESIAN
                lower.startsWith("ko") -> KOREAN
                lower.startsWith("ja") -> JAPANESE
                else -> SYSTEM
            }
        }
    }
}

sealed interface UiText {
    data class Resource(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes resId: Int, vararg formatArgs: Any) : this(resId, formatArgs.toList())
    }

    data class Dynamic(val value: String) : UiText

    fun asString(context: Context): String = when (this) {
        is Resource -> if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args.toTypedArray())
        is Dynamic -> value
    }
}

enum class GameStatus {
    WAITING,
    PLAYING,
    X_WON,
    O_WON,
    DRAW
}

data class BoardPoint(val row: Int, val col: Int)

data class MoveRecord(
    val row: Int,
    val col: Int,
    val piece: Piece
)

data class WinResult(
    val winner: Piece,
    val line: List<BoardPoint>
)
