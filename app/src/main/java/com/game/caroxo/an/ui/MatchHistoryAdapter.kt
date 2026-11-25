package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.ItemMatchHistoryBinding
import com.game.caroxo.an.model.MatchRecord
import java.text.SimpleDateFormat
import java.util.Locale

class MatchHistoryAdapter : ListAdapter<MatchRecord, MatchHistoryAdapter.MatchViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchViewHolder {
        val binding = ItemMatchHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MatchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MatchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MatchViewHolder(private val binding: ItemMatchHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: MatchRecord) {
            val ctx = binding.root.context
            binding.tvMatchOpponent.text = ctx.getString(R.string.match_history_vs_fmt, record.opponentNickname)

            val modeName = when (record.mode) {
                "AI_EASY" -> ctx.getString(R.string.ai_easy)
                "AI_MEDIUM" -> ctx.getString(R.string.ai_medium)
                "AI_HARD" -> ctx.getString(R.string.ai_hard)
                "PASS_PLAY" -> ctx.getString(R.string.mode_pass_play_title)
                "ONLINE" -> ctx.getString(R.string.section_online_modes)
                else -> record.mode
            }

            val mins = record.durationSeconds / 60
            val secs = record.durationSeconds % 60
            val durationStr = if (mins > 0) {
                ctx.getString(R.string.match_duration_min_sec, mins, secs)
            } else {
                ctx.getString(R.string.match_duration_sec, secs)
            }

            binding.tvMatchModeDuration.text = ctx.getString(R.string.match_history_mode_duration_fmt, modeName, durationStr)

            val dateStr = record.createdAt?.toDate()?.let {
                SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(it)
            } ?: ""
            binding.tvMatchTime.text = dateStr

            when (record.result) {
                "WIN" -> {
                    binding.tvMatchResult.text = ctx.getString(R.string.match_history_win)
                    binding.tvMatchResult.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_green_dark))
                }
                "LOSS" -> {
                    binding.tvMatchResult.text = ctx.getString(R.string.match_history_loss)
                    binding.tvMatchResult.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_red_dark))
                }
                else -> {
                    binding.tvMatchResult.text = ctx.getString(R.string.match_history_draw)
                    binding.tvMatchResult.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                }
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<MatchRecord>() {
            override fun areItemsTheSame(oldItem: MatchRecord, newItem: MatchRecord): Boolean {
                return oldItem.id == newItem.id && oldItem.id.isNotBlank()
            }

            override fun areContentsTheSame(oldItem: MatchRecord, newItem: MatchRecord): Boolean {
                return oldItem == newItem
            }
        }
    }
}
