package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.ItemPieceStyleBinding
import com.game.caroxo.an.model.PieceStyle
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class PieceStyleAdapter(
    private var selectedStyle: PieceStyle,
    private var isSignedIn: Boolean = false,
    private var currentRank: Int? = null,
    private var isAdmin: Boolean = false,
    private var adStyleUnlocks: Map<String, Long> = emptyMap(),
    private val onSelect: (PieceStyle) -> Unit,
    private val onLockedClick: (PieceStyle) -> Unit = {}
) : RecyclerView.Adapter<PieceStyleAdapter.ViewHolder>() {

    private val styles = PieceStyle.entries

    fun updateSelection(style: PieceStyle) {
        selectedStyle = style
        notifyDataSetChanged()
    }

    fun updateState(style: PieceStyle, signedIn: Boolean, rank: Int?, admin: Boolean = false, unlocks: Map<String, Long> = emptyMap()) {
        selectedStyle = style
        isSignedIn = signedIn
        currentRank = rank
        isAdmin = admin
        adStyleUnlocks = unlocks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPieceStyleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val style = styles[position]
        val now = System.currentTimeMillis()
        val unlocked = style.isUnlockedFor(isSignedIn, currentRank, isAdmin, adStyleUnlocks, now)
        holder.bind(
            style = style,
            selected = style == selectedStyle && unlocked,
            unlocked = unlocked,
            isSignedIn = isSignedIn,
            adStyleUnlocks = adStyleUnlocks,
            onSelect = onSelect,
            onLockedClick = onLockedClick
        )
    }

    override fun getItemCount(): Int = styles.size

    class ViewHolder(
        private val binding: ItemPieceStyleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            style: PieceStyle,
            selected: Boolean,
            unlocked: Boolean,
            isSignedIn: Boolean,
            adStyleUnlocks: Map<String, Long>,
            onSelect: (PieceStyle) -> Unit,
            onLockedClick: (PieceStyle) -> Unit
        ) {
            val ctx = binding.root.context
            val density = binding.root.resources.displayMetrics.density
            binding.previewPieceStyle.pieceStyle = style
            binding.tvStyleLabel.setText(RankUiHelper.resolvePieceStyleLabelRes(style))

            val vipBadgeRes = RankUiHelper.resolveVipBadgeRes(style)
            if (vipBadgeRes != null) {
                binding.tvVipBadge.visibility = View.VISIBLE
                binding.tvVipBadge.setText(vipBadgeRes)
                val badgeColorRes = when (style) {
                    PieceStyle.VIP_CELESTIAL_MONARCH -> R.color.caroxo_vip_gold
                    PieceStyle.VIP_PLASMA_VORTEX -> R.color.caroxo_vip_plasma
                    PieceStyle.VIP_CRIMSON_EMBER -> R.color.caroxo_vip_crimson
                    else -> R.color.caroxo_rank_gold
                }
                binding.tvVipBadge.setTextColor(ContextCompat.getColor(ctx, badgeColorRes))
            } else {
                binding.tvVipBadge.visibility = View.GONE
            }

            if (style.isVip || style.isAdRewarded) {
                binding.tvStyleStatus.visibility = View.VISIBLE
                if (unlocked) {
                    binding.layoutStyleLock.visibility = View.GONE
                    binding.previewPieceStyle.alpha = 1.0f
                    if (style.isAdRewarded) {
                        val remainingMs = (adStyleUnlocks[style.key] ?: 0L) - System.currentTimeMillis()
                        val hours = remainingMs / 3600000L
                        val mins = (remainingMs % 3600000L) / 60000L
                        binding.tvStyleStatus.text = if (hours > 0) {
                            "Unlocked · ${hours}h ${mins}m left"
                        } else {
                            "Unlocked · ${maxOf(0L, mins)}m left"
                        }
                    } else {
                        binding.tvStyleStatus.setText(R.string.vip_style_status_unlocked)
                    }
                    binding.tvStyleStatus.setTextColor(ContextCompat.getColor(ctx, R.color.caroxo_win_green))
                } else {
                    binding.layoutStyleLock.visibility = View.VISIBLE
                    binding.previewPieceStyle.alpha = 0.72f
                    if (style.isAdRewarded) {
                        binding.tvStyleStatus.text = "Watch Ad to Unlock (2H)"
                    } else if (!isSignedIn) {
                        binding.tvStyleStatus.setText(R.string.vip_style_status_sign_in)
                    } else {
                        binding.tvStyleStatus.text = ctx.getString(
                            R.string.vip_style_status_top_fmt,
                            style.requiredMaxRank ?: 3
                        )
                    }
                    binding.tvStyleStatus.setTextColor(
                        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                }
            } else {
                binding.layoutStyleLock.visibility = View.GONE
                binding.previewPieceStyle.alpha = 1.0f
                binding.tvStyleStatus.visibility = View.GONE
            }

            if (selected) {
                binding.cardStyleItem.strokeColor =
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
                binding.cardStyleItem.strokeWidth = (2f * density).roundToInt().coerceAtLeast(2)
                binding.cardStyleItem.setCardBackgroundColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
                )
                binding.tvStyleLabel.setTextColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer)
                )
            } else {
                val strokeColor = if (style.isVip && unlocked) {
                    ContextCompat.getColor(ctx, R.color.caroxo_rank_gold)
                } else {
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)
                }
                binding.cardStyleItem.strokeColor = strokeColor
                binding.cardStyleItem.strokeWidth = (1f * density).roundToInt().coerceAtLeast(1)
                binding.cardStyleItem.setCardBackgroundColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
                )
                binding.tvStyleLabel.setTextColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
                )
            }

            binding.cardStyleItem.setOnClickListener {
                if (unlocked) {
                    onSelect(style)
                } else {
                    onLockedClick(style)
                }
            }
        }
    }
}
