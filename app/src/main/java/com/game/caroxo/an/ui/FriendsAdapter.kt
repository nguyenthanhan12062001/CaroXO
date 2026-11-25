package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.ItemFriendBinding
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.Friendship
import com.game.caroxo.an.model.RankTier
import com.game.caroxo.an.model.otherUserAvatarId
import com.game.caroxo.an.model.otherUserNickname
import com.game.caroxo.an.model.otherUserPoints
import com.game.caroxo.an.model.otherUserUid

class FriendsAdapter(
    private val myUid: String,
    private val onFriendClick: (friendUid: String, nickname: String, avatarId: String) -> Unit,
    private val onInviteClick: (friendUid: String, nickname: String) -> Unit
) : ListAdapter<Friendship, FriendsAdapter.ViewHolder>(DIFF) {

    private var onlineMap: Map<String, Boolean> = emptyMap()

    fun updateOnlineStatuses(statusMap: Map<String, Boolean>) {
        if (onlineMap == statusMap) return
        onlineMap = statusMap
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFriendBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val friendUid = item.otherUserUid(myUid)
        val isOnline = onlineMap[friendUid] == true
        holder.bind(item, myUid, isOnline, onFriendClick, onInviteClick)
    }

    class ViewHolder(
        private val binding: ItemFriendBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: Friendship,
            myUid: String,
            isOnline: Boolean,
            onFriendClick: (String, String, String) -> Unit,
            onInviteClick: (String, String) -> Unit
        ) {
            val ctx = binding.root.context
            val friendUid = item.otherUserUid(myUid)
            val nickname = item.otherUserNickname(myUid).ifBlank { ctx.getString(R.string.default_player_name) }
            val avatarId = item.otherUserAvatarId(myUid)
            val points = item.otherUserPoints(myUid)

            binding.ivFriendAvatar.setImageResource(AvatarCatalog.resolveDrawable(avatarId))
            binding.viewFriendOnlineDot.visibility = if (isOnline) View.VISIBLE else View.GONE
            binding.tvFriendNickname.text = nickname
            binding.tvFriendPoints.text = ctx.getString(R.string.leaderboard_points_format, points.toInt())

            val tier = RankTier.resolve(points, null)
            binding.tvFriendTier.setText(RankUiHelper.resolveTitleRes(tier))
            binding.tvFriendTier.setTextColor(
                RankUiHelper.resolveTierColor(ctx, tier, binding.tvFriendTier)
            )

            binding.cardFriendItem.setOnClickListener {
                onFriendClick(friendUid, nickname, avatarId)
            }
            binding.btnInviteFriend.setOnClickListener {
                onInviteClick(friendUid, nickname)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Friendship>() {
            override fun areItemsTheSame(oldItem: Friendship, newItem: Friendship): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Friendship, newItem: Friendship): Boolean =
                oldItem == newItem
        }
    }
}
