package com.game.caroxo.an.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.game.caroxo.an.CaroApplication
import com.game.caroxo.an.MainActivity
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.FragmentSettingsBinding
import com.game.caroxo.an.databinding.ItemLanguageOptionBinding
import com.game.caroxo.an.databinding.SheetLanguagePickerBinding
import com.game.caroxo.an.model.AppLanguage
import com.game.caroxo.an.model.BoardSurface
import com.game.caroxo.an.model.ThemeMode
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CaroApplication
        val prefs = app.preferencesManager
        val sound = app.soundManager

        val current = prefs.settings.value
        binding.switchSfx.isChecked = current.sfxEnabled
        binding.switchMusic.isChecked = current.musicEnabled
        binding.switchHaptics.isChecked = current.hapticsEnabled

        binding.switchSfx.setOnCheckedChangeListener { _, isChecked ->
            prefs.setSfxEnabled(isChecked)
            if (isChecked) sound.playUiClick()
        }

        binding.switchMusic.setOnCheckedChangeListener { _, isChecked ->
            sound.playUiClick()
            prefs.setMusicEnabled(isChecked)
            sound.syncAmbientMusic(isResumed = true)
        }

        binding.switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            sound.playUiClick()
            prefs.setHapticsEnabled(isChecked)
            if (isChecked) sound.vibrateTap()
        }

        updateSurfaceCards(current.boardSurface)
        binding.cardSurfaceCream.setOnClickListener {
            sound.playUiClick()
            prefs.setBoardSurface(BoardSurface.MINIMAL_CREAM)
            updateSurfaceCards(BoardSurface.MINIMAL_CREAM)
        }
        binding.cardSurfaceWood.setOnClickListener {
            sound.playUiClick()
            prefs.setBoardSurface(BoardSurface.WARM_WOOD)
            updateSurfaceCards(BoardSurface.WARM_WOOD)
        }
        binding.cardSurfaceEmerald.setOnClickListener {
            sound.playUiClick()
            prefs.setBoardSurface(BoardSurface.EMERALD_GRID)
            updateSurfaceCards(BoardSurface.EMERALD_GRID)
        }
        binding.cardSurfaceSlate.setOnClickListener {
            sound.playUiClick()
            prefs.setBoardSurface(BoardSurface.DARK_SLATE)
            updateSurfaceCards(BoardSurface.DARK_SLATE)
        }

        updateThemeCards(current.themeMode)
        val themeClickMapping = listOf(
            binding.cardThemeSystem to ThemeMode.SYSTEM,
            binding.cardThemeLight to ThemeMode.LIGHT,
            binding.cardThemeDark to ThemeMode.DARK,
            binding.cardThemeAmoled to ThemeMode.AMOLED
        )
        for ((card, mode) in themeClickMapping) {
            card.setOnClickListener {
                sound.playUiClick()
                val previousMode = prefs.settings.value.themeMode
                if (previousMode != mode) {
                    val nightFlagWasSame = (previousMode == ThemeMode.DARK || previousMode == ThemeMode.AMOLED) &&
                        (mode == ThemeMode.DARK || mode == ThemeMode.AMOLED)
                    val amoledChanged = (previousMode == ThemeMode.AMOLED) != (mode == ThemeMode.AMOLED)
                    prefs.setThemeMode(mode)
                    updateThemeCards(mode)
                    // AppCompatDelegate won't recreate when both modes share MODE_NIGHT_YES,
                    // or when toggling AMOLED from SYSTEM while system is already in night mode.
                    if (nightFlagWasSame || amoledChanged) {
                        activity?.recreate()
                    }
                }
            }
        }

        val activeLang = resolveEffectiveLanguage(prefs.settings.value.languageMode)
        binding.tvCurrentLanguage.text = languageDisplayLabel(activeLang)
        binding.cardLanguage.setOnClickListener {
            sound.playUiClick()
            showLanguagePickerSheet(prefs, sound)
        }

        binding.btnReplayOnboarding.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.retriggerOnboardingTour()
        }

        binding.btnResetDiscovery.setOnClickListener {
            sound.playUiClick()
            prefs.resetOnlyFeatureTips()
            Toast.makeText(requireContext(), R.string.discovery_toast_reset, Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenAbout.setOnClickListener {
            sound.playUiClick()
            AboutDialogHelper.show(requireContext())
        }
    }

    private fun resolveEffectiveLanguage(saved: AppLanguage): AppLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) {
            if (saved == AppLanguage.SYSTEM) AppLanguage.SYSTEM else saved
        } else {
            AppLanguage.fromLocaleTag(locales[0]?.toLanguageTag())
        }
    }

    private fun languageDisplayLabel(language: AppLanguage): String = when (language) {
        AppLanguage.SYSTEM -> getString(R.string.lang_system)
        AppLanguage.VIETNAMESE -> getString(R.string.lang_vietnamese)
        AppLanguage.ENGLISH -> getString(R.string.lang_english)
        AppLanguage.CHINESE_SIMPLIFIED -> getString(R.string.lang_chinese)
        AppLanguage.INDONESIAN -> getString(R.string.lang_indonesian)
        AppLanguage.KOREAN -> getString(R.string.lang_korean)
        AppLanguage.JAPANESE -> getString(R.string.lang_japanese)
    }

    private fun showLanguagePickerSheet(
        prefs: com.game.caroxo.an.data.PreferencesManager,
        sound: com.game.caroxo.an.audio.SoundAndHapticsManager
    ) {
        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)
        val sheetBinding = SheetLanguagePickerBinding.inflate(LayoutInflater.from(ctx))
        val languages = AppLanguage.entries
        val currentLang = resolveEffectiveLanguage(prefs.settings.value.languageMode)
        val density = ctx.resources.displayMetrics.density
        val activeColor = MaterialColors.getColor(sheetBinding.root, com.google.android.material.R.attr.colorPrimary)
        val activeBg = MaterialColors.getColor(sheetBinding.root, com.google.android.material.R.attr.colorPrimaryContainer)
        val activeText = MaterialColors.getColor(sheetBinding.root, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val inactiveColor = MaterialColors.getColor(sheetBinding.root, com.google.android.material.R.attr.colorOutline)
        val inactiveBg = MaterialColors.getColor(sheetBinding.root, com.google.android.material.R.attr.colorSurface)
        val inactiveText = MaterialColors.getColor(sheetBinding.root, com.google.android.material.R.attr.colorOnSurface)

        for (lang in languages) {
            val rowBinding = ItemLanguageOptionBinding.inflate(
                LayoutInflater.from(ctx),
                sheetBinding.containerLanguageOptions,
                false
            )
            val isSelected = lang == currentLang
            rowBinding.tvLanguageOption.text = languageDisplayLabel(lang)
            rowBinding.ivLanguageCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                rowBinding.cardLanguageOption.setCardBackgroundColor(activeBg)
                rowBinding.cardLanguageOption.strokeColor = activeColor
                rowBinding.cardLanguageOption.strokeWidth = (1.5f * density).roundToInt().coerceAtLeast(2)
                rowBinding.tvLanguageOption.setTextColor(activeText)
            } else {
                rowBinding.cardLanguageOption.setCardBackgroundColor(inactiveBg)
                rowBinding.cardLanguageOption.strokeColor = inactiveColor
                rowBinding.cardLanguageOption.strokeWidth = (1f * density).roundToInt().coerceAtLeast(1)
                rowBinding.tvLanguageOption.setTextColor(inactiveText)
            }
            rowBinding.cardLanguageOption.setOnClickListener {
                sound.playUiClick()
                prefs.setLanguageMode(lang)
                binding.tvCurrentLanguage.text = languageDisplayLabel(lang)
                sheet.dismiss()
            }
            sheetBinding.containerLanguageOptions.addView(rowBinding.root)
        }

        sheet.setContentView(sheetBinding.root)
        sheet.show()
    }

    private fun updateThemeCards(selected: ThemeMode) {
        val density = resources.displayMetrics.density
        val activeStroke = (2f * density).roundToInt().coerceAtLeast(2)
        val inactiveStroke = (1f * density).roundToInt().coerceAtLeast(1)
        val activeColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val activeBg = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
        val activeText = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val inactiveColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)
        val inactiveBg = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        val inactiveText = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)

        val mapping = listOf(
            Triple(binding.cardThemeSystem, binding.tvThemeSystem, ThemeMode.SYSTEM),
            Triple(binding.cardThemeLight, binding.tvThemeLight, ThemeMode.LIGHT),
            Triple(binding.cardThemeDark, binding.tvThemeDark, ThemeMode.DARK),
            Triple(binding.cardThemeAmoled, binding.tvThemeAmoled, ThemeMode.AMOLED)
        )
        for ((card, label, mode) in mapping) {
            val isSelected = mode == selected
            card.strokeColor = if (isSelected) activeColor else inactiveColor
            card.strokeWidth = if (isSelected) activeStroke else inactiveStroke
            card.setCardBackgroundColor(if (isSelected) activeBg else inactiveBg)
            label.setTextColor(if (isSelected) activeText else inactiveText)
        }
    }

    private fun updateSurfaceCards(selected: BoardSurface) {
        val density = resources.displayMetrics.density
        val activeStroke = (2.5f * density).roundToInt().coerceAtLeast(3)
        val inactiveStroke = (1f * density).roundToInt().coerceAtLeast(1)
        val activeColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val inactiveColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

        val mapping = listOf(
            binding.cardSurfaceCream to BoardSurface.MINIMAL_CREAM,
            binding.cardSurfaceWood to BoardSurface.WARM_WOOD,
            binding.cardSurfaceEmerald to BoardSurface.EMERALD_GRID,
            binding.cardSurfaceSlate to BoardSurface.DARK_SLATE
        )
        for ((card: MaterialCardView, surface) in mapping) {
            val isSelected = surface == selected
            card.strokeColor = if (isSelected) activeColor else inactiveColor
            card.strokeWidth = if (isSelected) activeStroke else inactiveStroke
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
