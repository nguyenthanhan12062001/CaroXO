package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.game.caroxo.an.databinding.ItemAvatarBinding
import com.game.caroxo.an.model.AvatarOption
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class AvatarAdapter(
    private val items: List<AvatarOption>,
    private var selectedId: String,
    private val onSelect: (AvatarOption) -> Unit
) : RecyclerView.Adapter<AvatarAdapter.ViewHolder>() {

    fun updateSelection(avatarId: String) {
        selectedId = avatarId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAvatarBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item.id == selectedId, onSelect)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemAvatarBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AvatarOption, selected: Boolean, onSelect: (AvatarOption) -> Unit) {
            val density = binding.root.resources.displayMetrics.density
            binding.ivAvatarOption.setImageResource(item.drawableRes)
            binding.tvAvatarLabel.setText(item.labelRes)
            if (selected) {
                binding.cardAvatarItem.strokeColor =
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
                binding.cardAvatarItem.strokeWidth = (2f * density).roundToInt().coerceAtLeast(2)
                binding.cardAvatarItem.setCardBackgroundColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
                )
                binding.tvAvatarLabel.setTextColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer)
                )
            } else {
                binding.cardAvatarItem.strokeColor =
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)
                binding.cardAvatarItem.strokeWidth = (1f * density).roundToInt().coerceAtLeast(1)
                binding.cardAvatarItem.setCardBackgroundColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
                )
                binding.tvAvatarLabel.setTextColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
            binding.cardAvatarItem.setOnClickListener { onSelect(item) }
        }
    }
}
