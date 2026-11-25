package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.ItemChatMessageBinding
import com.game.caroxo.an.model.AdminConfig
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Locale

class ChatAdapter(
    private var currentUserId: String?,
    private val onSenderClick: ((ChatMessage) -> Unit)? = null
) : ListAdapter<ChatMessage, ChatAdapter.ViewHolder>(DIFF) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setCurrentUserId(uid: String?) {
        if (currentUserId != uid) {
            currentUserId = uid
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), currentUserId, timeFormat, onSenderClick)
    }

    class ViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: ChatMessage,
            currentUid: String?,
            timeFormat: SimpleDateFormat,
            onSenderClick: ((ChatMessage) -> Unit)?
        ) {
            val isMine = !currentUid.isNullOrBlank() && item.senderUid == currentUid
            val formattedTime = item.createdAt?.toDate()?.let { timeFormat.format(it) } ?: "••:••"

            if (isMine) {
                binding.layoutOutgoingMsg.visibility = View.VISIBLE
                binding.layoutIncomingMsg.visibility = View.GONE
                binding.tvChatTextMine.text = item.text
                binding.tvChatTimeMine.text = formattedTime
            } else {
                binding.layoutOutgoingMsg.visibility = View.GONE
                binding.layoutIncomingMsg.visibility = View.VISIBLE
                binding.ivChatAvatarOther.setImageResource(AvatarCatalog.resolveDrawable(item.senderAvatarId))
                binding.tvChatSenderOther.text = item.senderNickname.ifBlank { "Player" }
                binding.tvChatSenderOther.compoundDrawablePadding =
                    (4f * binding.root.resources.displayMetrics.density).toInt()
                binding.tvChatSenderOther.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0,
                    0,
                    if (AdminConfig.isAdmin(item.senderUid)) R.drawable.ic_verified_tiktok else 0,
                    0
                )
                binding.tvChatTimeOther.text = formattedTime
                binding.tvChatTextOther.text = item.text
                if (onSenderClick != null && item.senderUid.isNotBlank()) {
                    binding.ivChatAvatarOther.setOnClickListener { onSenderClick(item) }
                    binding.tvChatSenderOther.setOnClickListener { onSenderClick(item) }
                } else {
                    binding.ivChatAvatarOther.setOnClickListener(null)
                    binding.tvChatSenderOther.setOnClickListener(null)
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return if (oldItem.id.isNotBlank() && newItem.id.isNotBlank()) {
                    oldItem.id == newItem.id
                } else {
                    oldItem.senderUid == newItem.senderUid &&
                        oldItem.text == newItem.text &&
                        oldItem.createdAt == newItem.createdAt
                }
            }

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem.senderUid == newItem.senderUid &&
                    oldItem.senderNickname == newItem.senderNickname &&
                    oldItem.senderAvatarId == newItem.senderAvatarId &&
                    oldItem.text == newItem.text &&
                    oldItem.createdAt == newItem.createdAt
            }
        }
    }
}
