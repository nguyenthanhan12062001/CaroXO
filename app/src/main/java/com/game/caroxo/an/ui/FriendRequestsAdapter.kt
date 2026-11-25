package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.ItemFriendRequestBinding
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.Friendship
import com.game.caroxo.an.model.otherUserAvatarId
import com.game.caroxo.an.model.otherUserNickname
import com.game.caroxo.an.model.otherUserPoints
import com.game.caroxo.an.model.otherUserUid

class FriendRequestsAdapter(
    private val myUid: String,
    private val isIncoming: Boolean,
    private val onPlayerClick: (uid: String, nickname: String, avatarId: String) -> Unit,
    private val onAcceptClick: (Friendship) -> Unit,
    private val onDeclineOrCancelClick: (Friendship) -> Unit
) : ListAdapter<Friendship, FriendRequestsAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFriendRequestBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), myUid, isIncoming, onPlayerClick, onAcceptClick, onDeclineOrCancelClick)
    }

    class ViewHolder(
        private val binding: ItemFriendRequestBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: Friendship,
            myUid: String,
            isIncoming: Boolean,
            onPlayerClick: (String, String, String) -> Unit,
            onAcceptClick: (Friendship) -> Unit,
            onDeclineOrCancelClick: (Friendship) -> Unit
        ) {
            val ctx = binding.root.context
            val otherUid = item.otherUserUid(myUid)
            val nickname = item.otherUserNickname(myUid).ifBlank { ctx.getString(R.string.default_player_name) }
            val avatarId = item.otherUserAvatarId(myUid)
            val points = item.otherUserPoints(myUid)

            binding.ivReqAvatar.setImageResource(AvatarCatalog.resolveDrawable(avatarId))
            binding.tvReqNickname.text = nickname
            binding.tvReqPoints.text = ctx.getString(R.string.leaderboard_points_format, points.toInt())

            if (isIncoming) {
                binding.layoutReqIncomingActions.visibility = View.VISIBLE
                binding.btnReqCancel.visibility = View.GONE
                binding.btnReqAccept.setOnClickListener { onAcceptClick(item) }
                binding.btnReqDecline.setOnClickListener { onDeclineOrCancelClick(item) }
            } else {
                binding.layoutReqIncomingActions.visibility = View.GONE
                binding.btnReqCancel.visibility = View.VISIBLE
                binding.btnReqCancel.setOnClickListener { onDeclineOrCancelClick(item) }
            }

            binding.cardFriendRequest.setOnClickListener {
                onPlayerClick(otherUid, nickname, avatarId)
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
