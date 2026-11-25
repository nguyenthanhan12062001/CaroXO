package com.game.caroxo.an.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.databinding.DialogOnboardingBinding
import com.game.caroxo.an.databinding.ItemOnboardingSlideBinding
import com.google.android.material.color.MaterialColors

data class OnboardingSlideItem(
    val iconRes: Int,
    val tagRes: Int,
    val titleRes: Int,
    val descRes: Int,
    val highlight1: String,
    val highlight2: String,
    val highlight3: String
)

class OnboardingAdapter(private val slides: List<OnboardingSlideItem>) :
    RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
        val binding = ItemOnboardingSlideBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SlideViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
        holder.bind(slides[position])
    }

    override fun getItemCount(): Int = slides.size

    class SlideViewHolder(private val binding: ItemOnboardingSlideBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(slide: OnboardingSlideItem) {
            val ctx = binding.root.context
            binding.ivOnboardingIcon.setImageResource(slide.iconRes)
            binding.tvOnboardingTag.setText(slide.tagRes)
            binding.tvOnboardingTitle.setText(slide.titleRes)
            binding.tvOnboardingDesc.setText(slide.descRes)
            binding.tvHighlight1.text = slide.highlight1
            binding.tvHighlight2.text = slide.highlight2
            binding.tvHighlight3.text = slide.highlight3
        }
    }
}

object OnboardingDialogHelper {

    fun show(
        context: Context,
        prefs: PreferencesManager,
        onFinished: () -> Unit = {}
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val binding = DialogOnboardingBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(1000),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val slides = listOf(
            OnboardingSlideItem(
                iconRes = R.drawable.ic_grid,
                tagRes = R.string.onboarding_slide1_subtitle,
                titleRes = R.string.onboarding_slide1_title,
                descRes = R.string.onboarding_slide1_desc,
                highlight1 = "10×10 / 15×15 / 20×20",
                highlight2 = "Pinch Zoom 1x–3x",
                highlight3 = "Block 2 Ends"
            ),
            OnboardingSlideItem(
                iconRes = R.drawable.ic_cpu,
                tagRes = R.string.onboarding_slide2_subtitle,
                titleRes = R.string.onboarding_slide2_title,
                descRes = R.string.onboarding_slide2_desc,
                highlight1 = "Pass & Play (1 device)",
                highlight2 = "Minimax Alpha-Beta",
                highlight3 = "Real-time 6-digit Room"
            ),
            OnboardingSlideItem(
                iconRes = R.drawable.ic_crown,
                tagRes = R.string.onboarding_slide3_subtitle,
                titleRes = R.string.onboarding_slide3_title,
                descRes = R.string.onboarding_slide3_desc,
                highlight1 = "Win +10 / Loss -5",
                highlight2 = "Top 1–3 Hallmarks",
                highlight3 = "3 VIP Glowing Styles"
            ),
            OnboardingSlideItem(
                iconRes = R.drawable.ic_globe,
                tagRes = R.string.onboarding_slide4_subtitle,
                titleRes = R.string.onboarding_slide4_title,
                descRes = R.string.onboarding_slide4_desc,
                highlight1 = "Global & Room Chat",
                highlight2 = "10 Unique Avatars",
                highlight3 = "Last 10 Match History"
            )
        )

        val adapter = OnboardingAdapter(slides)
        binding.vpOnboarding.adapter = adapter

        val indicators = listOf(
            binding.indicator0,
            binding.indicator1,
            binding.indicator2,
            binding.indicator3
        )

        val activeColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val inactiveColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

        fun updateIndicators(position: Int) {
            val density = context.resources.displayMetrics.density
            indicators.forEachIndexed { idx, dot ->
                if (idx == position) {
                    dot.layoutParams.width = (22 * density).toInt()
                    dot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.caroxo_primary)
                } else {
                    dot.layoutParams.width = (6 * density).toInt()
                    dot.backgroundTintList = ContextCompat.getColorStateList(context, R.color.caroxo_outline)
                }
                dot.requestLayout()
            }

            if (position == slides.lastIndex) {
                binding.btnOnboardingAction.setText(R.string.onboarding_start_discovery)
                binding.btnOnboardingAction.setIconResource(R.drawable.ic_check)
            } else {
                binding.btnOnboardingAction.setText(R.string.onboarding_next)
                binding.btnOnboardingAction.setIconResource(R.drawable.ic_chevron_right)
            }
        }

        binding.vpOnboarding.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateIndicators(position)
            }
        })

        binding.btnOnboardingAction.setOnClickListener {
            val cur = binding.vpOnboarding.currentItem
            if (cur < slides.lastIndex) {
                binding.vpOnboarding.setCurrentItem(cur + 1, true)
            } else {
                prefs.setOnboardingCompleted(true)
                dialog.dismiss()
                onFinished()
            }
        }

        binding.btnOnboardingSkip.setOnClickListener {
            prefs.setOnboardingCompleted(true)
            dialog.dismiss()
            onFinished()
        }

        dialog.show()
    }
}
