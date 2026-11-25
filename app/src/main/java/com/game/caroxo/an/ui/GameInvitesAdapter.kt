package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.ItemGameInviteBinding
import com.game.caroxo.an.model.AdminConfig
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.GameInvite

class GameInvitesAdapter(
    private val onAcceptClick: (GameInvite) -> Unit,
    private val onDeclineClick: (GameInvite) -> Unit
) : ListAdapter<GameInvite, GameInvitesAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGameInviteBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onAcceptClick, onDeclineClick)
    }

    class ViewHolder(
        private val binding: ItemGameInviteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: GameInvite,
            onAcceptClick: (GameInvite) -> Unit,
            onDeclineClick: (GameInvite) -> Unit
        ) {
            val ctx = binding.root.context
            binding.ivInviteAvatar.setImageResource(AvatarCatalog.resolveDrawable(item.senderAvatarId))
            binding.tvInviteSender.text = ctx.getString(
                R.string.invite_popup_headline_match,
                item.senderNickname
            )
            binding.tvInviteSender.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                if (AdminConfig.isAdmin(item.senderUid)) R.drawable.ic_verified_tiktok else 0,
                0
            )
            val ruleLabel = ctx.getString(
                if (item.blockTwoEnds) R.string.invite_rule_block_on else R.string.invite_rule_block_off
            )
            binding.tvInviteDetails.text = ctx.getString(
                R.string.invite_card_desc_fmt,
                item.roomCode,
                item.boardSize,
                ruleLabel
            )
            binding.btnInviteAccept.setOnClickListener { onAcceptClick(item) }
            binding.btnInviteDecline.setOnClickListener { onDeclineClick(item) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<GameInvite>() {
            override fun areItemsTheSame(oldItem: GameInvite, newItem: GameInvite): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: GameInvite, newItem: GameInvite): Boolean =
                oldItem == newItem
        }
    }
}
