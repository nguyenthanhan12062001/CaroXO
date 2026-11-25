package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.ItemPlayerSearchBinding
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.Friendship
import com.game.caroxo.an.model.LeaderboardEntry
import com.game.caroxo.an.model.RankTier

class PlayerSearchAdapter(
    private val myUid: String,
    private val onPlayerClick: (LeaderboardEntry) -> Unit,
    private val onActionClick: (LeaderboardEntry, Friendship?) -> Unit
) : ListAdapter<LeaderboardEntry, PlayerSearchAdapter.ViewHolder>(DIFF) {

    private var friendshipByUid: Map<String, Friendship> = emptyMap()

    fun updateFriendships(friendships: List<Friendship>) {
        friendshipByUid = friendships.mapNotNull { item ->
            val other = item.userIds.firstOrNull { it != myUid }
            other?.let { it to item }
        }.toMap()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlayerSearchBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry, myUid, friendshipByUid[entry.userId], onPlayerClick, onActionClick)
    }

    class ViewHolder(
        private val binding: ItemPlayerSearchBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            entry: LeaderboardEntry,
            myUid: String,
            friendship: Friendship?,
            onPlayerClick: (LeaderboardEntry) -> Unit,
            onActionClick: (LeaderboardEntry, Friendship?) -> Unit
        ) {
            val ctx = binding.root.context
            binding.ivSearchAvatar.setImageResource(AvatarCatalog.resolveDrawable(entry.avatarId))
            binding.tvSearchNickname.text = entry.nickname

            val tier = RankTier.resolve(entry.points, null)
            val tierName = ctx.getString(RankUiHelper.resolveTitleRes(tier))
            val ptsText = ctx.getString(R.string.leaderboard_points_format, entry.points.toInt())
            val recText = ctx.getString(R.string.leaderboard_record_format, entry.wins.toInt(), entry.losses.toInt())
            binding.tvSearchMeta.text = "$tierName · $ptsText · $recText"

            when {
                friendship?.status == "ACCEPTED" -> {
                    binding.btnSearchAction.isEnabled = true
                    binding.btnSearchAction.setText(R.string.action_invite_play)
                    binding.btnSearchAction.setIconResource(R.drawable.ic_globe)
                }
                friendship?.status == "PENDING" && friendship.recipientUid == myUid -> {
                    binding.btnSearchAction.isEnabled = true
                    binding.btnSearchAction.setText(R.string.action_accept)
                    binding.btnSearchAction.setIconResource(R.drawable.ic_check)
                }
                friendship?.status == "PENDING" && friendship.requesterUid == myUid -> {
                    binding.btnSearchAction.isEnabled = false
                    binding.btnSearchAction.setText(R.string.status_request_sent)
                    binding.btnSearchAction.setIconResource(R.drawable.ic_check)
                }
                else -> {
                    binding.btnSearchAction.isEnabled = true
                    binding.btnSearchAction.setText(R.string.action_add_friend)
                    binding.btnSearchAction.setIconResource(R.drawable.ic_person_add)
                }
            }

            binding.cardSearchPlayer.setOnClickListener { onPlayerClick(entry) }
            binding.btnSearchAction.setOnClickListener { onActionClick(entry, friendship) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LeaderboardEntry>() {
            override fun areItemsTheSame(oldItem: LeaderboardEntry, newItem: LeaderboardEntry): Boolean =
                oldItem.userId == newItem.userId

            override fun areContentsTheSame(oldItem: LeaderboardEntry, newItem: LeaderboardEntry): Boolean =
                oldItem == newItem
        }
    }
}
