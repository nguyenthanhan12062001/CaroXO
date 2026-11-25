package com.game.caroxo.an.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.game.caroxo.an.model.AppLanguage
import com.game.caroxo.an.model.BoardSurface
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val sfxEnabled: Boolean = true,
    val musicEnabled: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val impactEffectsEnabled: Boolean = true,
    val boardSurface: BoardSurface = BoardSurface.MINIMAL_CREAM,
    val pieceStyle: PieceStyle = PieceStyle.CLASSIC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val languageMode: AppLanguage = AppLanguage.SYSTEM,
    val defaultBoardSize: Int = 15,
    val defaultBlockTwoEnds: Boolean = true,
    val localNickname: String = "CaroPlayer",
    val localAvatarId: String = "ninja",
    val adStyleUnlocks: Map<String, Long> = emptyMap()
)

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private fun loadFromPrefs(): SettingsState {
        val boardSize = prefs.getInt(KEY_BOARD_SIZE, 15).let {
            if (it == 10 || it == 15 || it == 20) it else 15
        }
        return SettingsState(
            sfxEnabled = prefs.getBoolean(KEY_SFX, true),
            musicEnabled = prefs.getBoolean(KEY_MUSIC, false),
            hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
            impactEffectsEnabled = prefs.getBoolean(KEY_IMPACT_FX, true),
            boardSurface = BoardSurface.fromKey(prefs.getString(KEY_BOARD_SURFACE, BoardSurface.MINIMAL_CREAM.key)),
            pieceStyle = PieceStyle.fromKey(prefs.getString(KEY_PIECE_STYLE, PieceStyle.CLASSIC.key)),
            themeMode = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.key)),
            languageMode = AppLanguage.fromKey(prefs.getString(KEY_LANGUAGE_MODE, AppLanguage.SYSTEM.key)),
            defaultBoardSize = boardSize,
            defaultBlockTwoEnds = prefs.getBoolean(KEY_BLOCK_TWO_ENDS, true),
            localNickname = prefs.getString(KEY_LOCAL_NICKNAME, "CaroPlayer").orEmpty().ifBlank { "CaroPlayer" },
            localAvatarId = prefs.getString(KEY_LOCAL_AVATAR, "ninja").orEmpty().ifBlank { "ninja" },
            adStyleUnlocks = loadAdStyleUnlocksMap()
        )
    }

    private fun loadAdStyleUnlocksMap(): Map<String, Long> {
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, Long>()
        for (style in PieceStyle.entries) {
            if (!style.isAdRewarded) continue
            val expiresAt = prefs.getLong("$KEY_AD_STYLE_PREFIX${style.key}", 0L)
            if (expiresAt > now) {
                result[style.key] = expiresAt
            }
        }
        return result
    }

    fun applySavedTheme() {
        applyThemeMode(_settings.value.themeMode)
    }

    fun applySavedLanguage() {
        applyLanguageMode(_settings.value.languageMode)
    }

    fun setSfxEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SFX, enabled).apply()
        _settings.value = _settings.value.copy(sfxEnabled = enabled)
    }

    fun setMusicEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MUSIC, enabled).apply()
        _settings.value = _settings.value.copy(musicEnabled = enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply()
        _settings.value = _settings.value.copy(hapticsEnabled = enabled)
    }

    fun setImpactEffectsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IMPACT_FX, enabled).apply()
        _settings.value = _settings.value.copy(impactEffectsEnabled = enabled)
    }

    fun setBoardSurface(surface: BoardSurface) {
        prefs.edit().putString(KEY_BOARD_SURFACE, surface.key).apply()
        _settings.value = _settings.value.copy(boardSurface = surface)
    }

    fun setPieceStyle(style: PieceStyle) {
        prefs.edit().putString(KEY_PIECE_STYLE, style.key).apply()
        _settings.value = _settings.value.copy(pieceStyle = style)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.key).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
        applyThemeMode(mode)
    }

    fun setLanguageMode(mode: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE_MODE, mode.key).apply()
        _settings.value = _settings.value.copy(languageMode = mode)
        applyLanguageMode(mode)
    }

    fun setDefaultBoardSize(size: Int) {
        val valid = if (size == 10 || size == 15 || size == 20) size else 15
        prefs.edit().putInt(KEY_BOARD_SIZE, valid).apply()
        _settings.value = _settings.value.copy(defaultBoardSize = valid)
    }

    fun setDefaultBlockTwoEnds(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_TWO_ENDS, enabled).apply()
        _settings.value = _settings.value.copy(defaultBlockTwoEnds = enabled)
    }

    fun setLocalProfile(nickname: String, avatarId: String, pieceStyle: PieceStyle) {
        val cleanNick = nickname.trim().take(24).ifBlank { "CaroPlayer" }
        prefs.edit()
            .putString(KEY_LOCAL_NICKNAME, cleanNick)
            .putString(KEY_LOCAL_AVATAR, avatarId)
            .putString(KEY_PIECE_STYLE, pieceStyle.key)
            .apply()
        _settings.value = _settings.value.copy(
            localNickname = cleanNick,
            localAvatarId = avatarId,
            pieceStyle = pieceStyle
        )
    }

    private fun applyThemeMode(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK, ThemeMode.AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun applyLanguageMode(mode: AppLanguage) {
        val target = if (mode == AppLanguage.SYSTEM || mode.languageTag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(mode.languageTag)
        }
        if (AppCompatDelegate.getApplicationLocales() != target) {
            AppCompatDelegate.setApplicationLocales(target)
        }
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun isFeatureDiscovered(featureKey: String): Boolean {
        val discoveredSet = prefs.getStringSet(KEY_DISCOVERED_FEATURES, emptySet()) ?: emptySet()
        return discoveredSet.contains(featureKey)
    }

    fun markFeatureDiscovered(featureKey: String) {
        val currentSet = prefs.getStringSet(KEY_DISCOVERED_FEATURES, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(featureKey)
        prefs.edit().putStringSet(KEY_DISCOVERED_FEATURES, currentSet).apply()
    }

    fun isAllDiscoverySkipped(): Boolean {
        return prefs.getBoolean(KEY_SKIP_ALL_DISCOVERY, false)
    }

    fun setSkipAllDiscovery(skip: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_ALL_DISCOVERY, skip).apply()
    }

    fun resetAllDiscovery() {
        prefs.edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, false)
            .remove(KEY_DISCOVERED_FEATURES)
            .putBoolean(KEY_SKIP_ALL_DISCOVERY, false)
            .apply()
    }

    fun resetOnlyFeatureTips() {
        prefs.edit()
            .remove(KEY_DISCOVERED_FEATURES)
            .putBoolean(KEY_SKIP_ALL_DISCOVERY, false)
            .apply()
    }

    fun grantTimedStyleUnlock(style: PieceStyle, durationMs: Long) {
        if (!style.isAdRewarded) return
        val now = System.currentTimeMillis()
        val currentExpiry = prefs.getLong("$KEY_AD_STYLE_PREFIX${style.key}", 0L)
        val base = maxOf(now, currentExpiry)
        val nextExpiry = base + durationMs
        prefs.edit().putLong("$KEY_AD_STYLE_PREFIX${style.key}", nextExpiry).apply()
        _settings.value = _settings.value.copy(adStyleUnlocks = loadAdStyleUnlocksMap())
    }

    fun getActiveAdUnlocks(): Map<String, Long> {
        val active = loadAdStyleUnlocksMap()
        if (active != _settings.value.adStyleUnlocks) {
            _settings.value = _settings.value.copy(adStyleUnlocks = active)
        }
        return active
    }

    fun getStyleRemainingUnlockMs(style: PieceStyle, nowMs: Long = System.currentTimeMillis()): Long {
        if (!style.isAdRewarded) return 0L
        val expiresAt = _settings.value.adStyleUnlocks[style.key]
            ?: prefs.getLong("$KEY_AD_STYLE_PREFIX${style.key}", 0L)
        return (expiresAt - nowMs).coerceAtLeast(0L)
    }

    fun recordMatchCompleted() {
        val total = prefs.getInt(KEY_TOTAL_MATCHES_COMPLETED, 0) + 1
        val sinceAd = prefs.getInt(KEY_MATCHES_SINCE_INTERSTITIAL, 0) + 1
        prefs.edit()
            .putInt(KEY_TOTAL_MATCHES_COMPLETED, total)
            .putInt(KEY_MATCHES_SINCE_INTERSTITIAL, sinceAd)
            .apply()
    }

    fun recordInterstitialShown(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(KEY_MATCHES_SINCE_INTERSTITIAL, 0)
            .putLong(KEY_LAST_INTERSTITIAL_AT, nowMs)
            .apply()
    }

    fun recordRewardedAdWatched(nowMs: Long = System.currentTimeMillis()) {
        // Reset interstitial pacing so a user who voluntarily watched a rewarded ad
        // is never hit by a forced interstitial shortly after.
        prefs.edit()
            .putInt(KEY_MATCHES_SINCE_INTERSTITIAL, 0)
            .putLong(KEY_LAST_INTERSTITIAL_AT, nowMs)
            .apply()
    }

    fun canShowInterstitial(
        graceMatches: Int,
        minMatchInterval: Int,
        cooldownMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val total = prefs.getInt(KEY_TOTAL_MATCHES_COMPLETED, 0)
        if (total <= graceMatches) return false
        val since = prefs.getInt(KEY_MATCHES_SINCE_INTERSTITIAL, 0)
        if (since < minMatchInterval) return false
        val lastAt = prefs.getLong(KEY_LAST_INTERSTITIAL_AT, 0L)
        return (nowMs - lastAt) >= cooldownMs
    }

    companion object {
        private const val PREFS_NAME = "caroxo_preferences"
        private const val KEY_SFX = "sfx_enabled"
        private const val KEY_MUSIC = "music_enabled"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_IMPACT_FX = "impact_effects_enabled"
        private const val KEY_BOARD_SURFACE = "board_surface"
        private const val KEY_PIECE_STYLE = "piece_style"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE_MODE = "language_mode"
        private const val KEY_BOARD_SIZE = "default_board_size"
        private const val KEY_BLOCK_TWO_ENDS = "default_block_two_ends"
        private const val KEY_LOCAL_NICKNAME = "local_nickname"
        private const val KEY_LOCAL_AVATAR = "local_avatar_id"
        private const val KEY_AD_STYLE_PREFIX = "ad_style_unlock_"
        private const val KEY_TOTAL_MATCHES_COMPLETED = "total_matches_completed"
        private const val KEY_MATCHES_SINCE_INTERSTITIAL = "matches_since_interstitial"
        private const val KEY_LAST_INTERSTITIAL_AT = "last_interstitial_at_ms"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_DISCOVERED_FEATURES = "discovered_features"
        private const val KEY_SKIP_ALL_DISCOVERY = "skip_all_discovery"

        const val FEATURE_BOARD_RULES = "feat_board_rules"
        const val FEATURE_AI_MODES = "feat_ai_modes"
        const val FEATURE_ONLINE_ROOM = "feat_online_room"
        const val FEATURE_HOME_FRIENDS = "feat_home_friends"
        const val FEATURE_FRIENDS_SOCIAL = "feat_friends_social"
        const val FEATURE_GAME_GESTURES = "feat_game_gestures"
        const val FEATURE_LEADERBOARD_VIP = "feat_leaderboard_vip"
        const val FEATURE_GLOBAL_CHAT = "feat_global_chat"
        const val FEATURE_PROFILE_CUSTOMIZATION = "feat_profile_customization"
    }
}
