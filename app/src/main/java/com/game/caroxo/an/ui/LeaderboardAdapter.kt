package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.ItemLeaderboardBinding
import com.game.caroxo.an.model.AdminConfig
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.LeaderboardEntry
import com.game.caroxo.an.model.RankTier
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class LeaderboardAdapter(
    private var currentUserId: String?,
    private val onPlayerClick: ((LeaderboardEntry) -> Unit)? = null
) : ListAdapter<LeaderboardEntry, LeaderboardAdapter.ViewHolder>(DiffCallback) {

    fun setCurrentUserId(uid: String?) {
        currentUserId = uid
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLeaderboardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1, currentUserId, onPlayerClick)
    }

    class ViewHolder(
        private val binding: ItemLeaderboardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            entry: LeaderboardEntry,
            rank: Int,
            currentUserId: String?,
            onPlayerClick: ((LeaderboardEntry) -> Unit)?
        ) {
            val ctx = binding.root.context
            val density = ctx.resources.displayMetrics.density
            binding.tvLbRank.text = "#$rank"
            val rankColor = when (rank) {
                1 -> ContextCompat.getColor(ctx, R.color.caroxo_rank_gold)
                2 -> ContextCompat.getColor(ctx, R.color.caroxo_rank_silver)
                3 -> ContextCompat.getColor(ctx, R.color.caroxo_rank_bronze)
                else -> MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
            }
            binding.tvLbRank.setTextColor(rankColor)

            if (rank in 1..3) {
                binding.ivLbCrown.visibility = View.VISIBLE
                binding.ivLbCrown.setColorFilter(rankColor)
            } else {
                binding.ivLbCrown.visibility = View.GONE
            }

            val tier = RankTier.resolve(entry.points, rank)
            val tierColor = RankUiHelper.resolveTierColor(ctx, tier, binding.root)
            binding.tvLbTitle.setText(RankUiHelper.resolveTitleRes(tier))
            binding.tvLbTitle.setTextColor(tierColor)

            binding.ivLbAvatar.setImageResource(AvatarCatalog.resolveDrawable(entry.avatarId))
            binding.tvLbNickname.text = entry.nickname
            binding.tvLbNickname.compoundDrawablePadding = (5f * density).roundToInt()
            binding.tvLbNickname.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                if (AdminConfig.isAdmin(entry.userId)) R.drawable.ic_verified_tiktok else 0,
                0
            )
            binding.tvLbPlayerId.text = ctx.getString(R.string.leaderboard_player_id_fmt, entry.userId)
            binding.tvLbRecord.text = ctx.getString(
                R.string.leaderboard_record_format,
                entry.wins.toInt(),
                entry.losses.toInt()
            )
            binding.tvLbPoints.text = ctx.getString(R.string.leaderboard_points_format, entry.points.toInt())

            val isMe = currentUserId != null && entry.userId == currentUserId
            binding.tvLbMeBadge.visibility = if (isMe) View.VISIBLE else View.GONE
            if (isMe) {
                binding.cardLbRow.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.caroxo_my_row_highlight)
                )
                binding.cardLbRow.strokeColor =
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
                binding.cardLbRow.strokeWidth = (1.5f * density).roundToInt().coerceAtLeast(2)
            } else {
                binding.cardLbRow.setCardBackgroundColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
                )
                binding.cardLbRow.strokeColor = if (rank in 1..3) {
                    rankColor
                } else {
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)
                }
                binding.cardLbRow.strokeWidth = (if (rank in 1..3) 1.3f else 1f)
                    .let { (it * density).roundToInt().coerceAtLeast(1) }
            }

            if (!isMe && onPlayerClick != null) {
                binding.cardLbRow.isClickable = true
                binding.cardLbRow.isFocusable = true
                binding.cardLbRow.setOnClickListener { onPlayerClick(entry) }
            } else {
                binding.cardLbRow.setOnClickListener(null)
                binding.cardLbRow.isClickable = false
                binding.cardLbRow.isFocusable = false
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<LeaderboardEntry>() {
        override fun areItemsTheSame(oldItem: LeaderboardEntry, newItem: LeaderboardEntry): Boolean =
            oldItem.userId == newItem.userId

        override fun areContentsTheSame(oldItem: LeaderboardEntry, newItem: LeaderboardEntry): Boolean =
            oldItem == newItem
    }
}
