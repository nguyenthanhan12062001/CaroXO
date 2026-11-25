package com.game.caroxo.an.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.databinding.DialogOnboardingBinding
import com.game.caroxo.an.databinding.ItemOnboardingSlideBinding

data class OnboardingSlideItem(
    @DrawableRes val iconRes: Int,
    @StringRes val tagRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @StringRes val highlight1Res: Int,
    @StringRes val highlight2Res: Int,
    @StringRes val highlight3Res: Int
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
            binding.ivOnboardingIcon.setImageResource(slide.iconRes)
            binding.tvOnboardingTag.setText(slide.tagRes)
            binding.tvOnboardingTitle.setText(slide.titleRes)
            binding.tvOnboardingDesc.setText(slide.descRes)
            binding.tvHighlight1.setText(slide.highlight1Res)
            binding.tvHighlight2.setText(slide.highlight2Res)
            binding.tvHighlight3.setText(slide.highlight3Res)
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
                highlight1Res = R.string.onboarding_s1_h1,
                highlight2Res = R.string.onboarding_s1_h2,
                highlight3Res = R.string.onboarding_s1_h3
            ),
            OnboardingSlideItem(
                iconRes = R.drawable.ic_cpu,
                tagRes = R.string.onboarding_slide2_subtitle,
                titleRes = R.string.onboarding_slide2_title,
                descRes = R.string.onboarding_slide2_desc,
                highlight1Res = R.string.onboarding_s2_h1,
                highlight2Res = R.string.onboarding_s2_h2,
                highlight3Res = R.string.onboarding_s2_h3
            ),
            OnboardingSlideItem(
                iconRes = R.drawable.ic_people,
                tagRes = R.string.onboarding_slide_social_subtitle,
                titleRes = R.string.onboarding_slide_social_title,
                descRes = R.string.onboarding_slide_social_desc,
                highlight1Res = R.string.onboarding_s_social_h1,
                highlight2Res = R.string.onboarding_s_social_h2,
                highlight3Res = R.string.onboarding_s_social_h3
            ),
            OnboardingSlideItem(
                iconRes = R.drawable.ic_crown,
                tagRes = R.string.onboarding_slide3_subtitle,
                titleRes = R.string.onboarding_slide3_title,
                descRes = R.string.onboarding_slide3_desc,
                highlight1Res = R.string.onboarding_s3_h1,
                highlight2Res = R.string.onboarding_s3_h2,
                highlight3Res = R.string.onboarding_s3_h3
            ),
            OnboardingSlideItem(
                iconRes = R.drawable.ic_globe,
                tagRes = R.string.onboarding_slide4_subtitle,
                titleRes = R.string.onboarding_slide4_title,
                descRes = R.string.onboarding_slide4_desc,
                highlight1Res = R.string.onboarding_s4_h1,
                highlight2Res = R.string.onboarding_s4_h2,
                highlight3Res = R.string.onboarding_s4_h3
            )
        )

        val adapter = OnboardingAdapter(slides)
        binding.vpOnboarding.adapter = adapter

        val indicators = listOf(
            binding.indicator0,
            binding.indicator1,
            binding.indicator2,
            binding.indicator3,
            binding.indicator4
        )

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
