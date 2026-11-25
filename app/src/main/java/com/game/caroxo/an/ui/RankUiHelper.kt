package com.game.caroxo.an.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.game.caroxo.an.R
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.RankTier
import com.google.android.material.color.MaterialColors

object RankUiHelper {

    @StringRes
    fun resolveTitleRes(tier: RankTier): Int = when (tier) {
        RankTier.TOP_1_MONARCH -> R.string.rank_title_top1
        RankTier.TOP_2_SOVEREIGN -> R.string.rank_title_top2
        RankTier.TOP_3_VANGUARD -> R.string.rank_title_top3
        RankTier.LEGEND -> R.string.rank_title_legend
        RankTier.GRANDMASTER -> R.string.rank_title_grandmaster
        RankTier.MASTER -> R.string.rank_title_master
        RankTier.TACTICIAN -> R.string.rank_title_tactician
        RankTier.APPRENTICE -> R.string.rank_title_apprentice
        RankTier.NOVICE -> R.string.rank_title_novice
    }

    fun resolveTierColor(context: Context, tier: RankTier, anchorView: android.view.View): Int {
        return when (tier) {
            RankTier.TOP_1_MONARCH -> ContextCompat.getColor(context, R.color.caroxo_rank_gold)
            RankTier.TOP_2_SOVEREIGN -> ContextCompat.getColor(context, R.color.caroxo_vip_plasma)
            RankTier.TOP_3_VANGUARD -> ContextCompat.getColor(context, R.color.caroxo_rank_bronze)
            RankTier.LEGEND -> ContextCompat.getColor(context, R.color.caroxo_tier_legend)
            RankTier.GRANDMASTER -> ContextCompat.getColor(context, R.color.caroxo_tier_grandmaster)
            RankTier.MASTER -> ContextCompat.getColor(context, R.color.caroxo_tier_master)
            RankTier.TACTICIAN -> ContextCompat.getColor(context, R.color.caroxo_tier_tactician)
            RankTier.APPRENTICE, RankTier.NOVICE -> MaterialColors.getColor(
                anchorView,
                com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        }
    }

    @StringRes
    fun resolveHallmarkTitleRes(rank: Int): Int = when (rank) {
        1 -> R.string.hallmark_top1_title
        2 -> R.string.hallmark_top2_title
        else -> R.string.hallmark_top3_title
    }

    @DrawableRes
    fun resolveHallmarkBgRes(rank: Int): Int = when (rank) {
        1 -> R.drawable.bg_hallmark_gold
        2 -> R.drawable.bg_hallmark_silver
        else -> R.drawable.bg_hallmark_bronze
    }

    fun resolveHallmarkAccentColor(context: Context, rank: Int): Int = when (rank) {
        1 -> ContextCompat.getColor(context, R.color.caroxo_rank_gold)
        2 -> ContextCompat.getColor(context, R.color.caroxo_rank_silver)
        else -> ContextCompat.getColor(context, R.color.caroxo_rank_bronze)
    }

    @StringRes
    fun resolvePieceStyleLabelRes(style: PieceStyle): Int = when (style) {
        PieceStyle.CLASSIC -> R.string.style_classic
        PieceStyle.NEON_CYBER -> R.string.style_neon_cyber
        PieceStyle.WOOD_CLASSIC -> R.string.style_wood_classic
        PieceStyle.SYMBOL -> R.string.style_symbol
        PieceStyle.AD_STORM_VOLT -> R.string.style_ad_storm_volt
        PieceStyle.AD_FROST_CRYSTAL -> R.string.style_ad_frost_crystal
        PieceStyle.AD_VOID_SINGULARITY -> R.string.style_ad_void_singularity
        PieceStyle.AD_INK_GOLD -> R.string.style_ad_ink_gold
        PieceStyle.VIP_CRIMSON_EMBER -> R.string.style_vip_crimson_ember
        PieceStyle.VIP_PLASMA_VORTEX -> R.string.style_vip_plasma_vortex
        PieceStyle.VIP_CELESTIAL_MONARCH -> R.string.style_vip_celestial_monarch
    }

    @StringRes
    fun resolveVipBadgeRes(style: PieceStyle): Int? = when (style) {
        PieceStyle.AD_STORM_VOLT,
        PieceStyle.AD_FROST_CRYSTAL,
        PieceStyle.AD_VOID_SINGULARITY,
        PieceStyle.AD_INK_GOLD -> R.string.ad_badge_timed
        PieceStyle.VIP_CRIMSON_EMBER -> R.string.vip_badge_top3
        PieceStyle.VIP_PLASMA_VORTEX -> R.string.vip_badge_top2
        PieceStyle.VIP_CELESTIAL_MONARCH -> R.string.vip_badge_top1
        else -> null
    }
}
