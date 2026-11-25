package com.game.caroxo.an.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.game.caroxo.an.CaroApplication
import com.game.caroxo.an.MainActivity
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.databinding.FragmentProfileBinding
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.PlayerProfile
import com.game.caroxo.an.model.RankTier
import com.game.caroxo.an.ui.FeatureDiscoveryHelper
import com.game.caroxo.an.viewmodel.LobbyEvent
import com.game.caroxo.an.viewmodel.MainViewModel
import com.game.caroxo.an.viewmodel.UiState
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as CaroApplication).mainViewModelFactory
    }
    private lateinit var avatarAdapter: AvatarAdapter
    private lateinit var pieceStyleAdapter: PieceStyleAdapter
    private lateinit var matchHistoryAdapter: MatchHistoryAdapter

    private var selectedAvatarId: String = "ninja"
    private var selectedPieceStyle: PieceStyle = PieceStyle.CLASSIC
    private var initializedInputs = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CaroApplication
        val prefs = app.preferencesManager
        val sound = app.soundManager

        val initial = prefs.settings.value
        selectedAvatarId = initial.localAvatarId
        val signedInInitially = mainViewModel.currentUser.value != null
        val rankInitially = mainViewModel.myCurrentRank.value
        selectedPieceStyle = if (initial.pieceStyle.isUnlockedFor(signedInInitially, rankInitially)) {
            initial.pieceStyle
        } else {
            PieceStyle.CLASSIC
        }
        binding.etNickname.setText(initial.localNickname)
        binding.ivProfileAvatar.setImageResource(AvatarCatalog.resolveDrawable(selectedAvatarId))

        avatarAdapter = AvatarAdapter(AvatarCatalog.all, selectedAvatarId) { chosen ->
            sound.playUiClick()
            selectedAvatarId = chosen.id
            avatarAdapter.updateSelection(chosen.id)
            binding.ivProfileAvatar.setImageResource(chosen.drawableRes)
        }
        binding.rvAvatars.layoutManager = GridLayoutManager(requireContext(), 5)
        binding.rvAvatars.adapter = avatarAdapter

        pieceStyleAdapter = PieceStyleAdapter(
            selectedStyle = selectedPieceStyle,
            isSignedIn = signedInInitially,
            currentRank = rankInitially,
            onSelect = { chosen ->
                sound.playUiClick()
                selectedPieceStyle = chosen
                pieceStyleAdapter.updateState(
                    chosen,
                    mainViewModel.currentUser.value != null,
                    mainViewModel.myCurrentRank.value
                )
            },
            onLockedClick = { lockedStyle ->
                sound.playError()
                val isSignedIn = mainViewModel.currentUser.value != null
                val msg = if (!isSignedIn) {
                    getString(R.string.vip_style_locked_sign_in)
                } else {
                    getString(R.string.vip_style_locked_rank_fmt, lockedStyle.requiredMaxRank ?: 3)
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        )
        binding.rvPieceStyles.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvPieceStyles.adapter = pieceStyleAdapter

        matchHistoryAdapter = MatchHistoryAdapter()
        binding.rvMatchHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMatchHistory.adapter = matchHistoryAdapter

        if (prefs.isOnboardingCompleted() && !prefs.isAllDiscoverySkipped() && !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_PROFILE_CUSTOMIZATION)) {
            FeatureDiscoveryHelper.showTip(
                container = binding.layoutDiscoveryContainer,
                featureKey = PreferencesManager.FEATURE_PROFILE_CUSTOMIZATION,
                iconRes = R.drawable.ic_nav_profile,
                stepText = getString(R.string.discovery_step_fmt, 1, 1),
                titleRes = R.string.discovery_profile_title,
                descRes = R.string.discovery_profile_desc,
                prefs = prefs
            )
        }

        binding.btnProfileGoogleSignIn.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.triggerGoogleSignIn()
        }

        binding.btnProfileEmailSignIn.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.openEmailAuthSheet()
        }

        binding.btnProfileSignOut.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.triggerSignOut()
        }

        binding.btnSaveProfile.setOnClickListener {
            sound.playUiClick()
            val nick = binding.etNickname.text?.toString().orEmpty()
            mainViewModel.saveProfile(nick, selectedAvatarId, selectedPieceStyle)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainViewModel.currentUser.collect { user ->
                        val signedIn = user != null
                        binding.btnProfileGoogleSignIn.visibility = if (signedIn) View.GONE else View.VISIBLE
                        binding.btnProfileEmailSignIn.visibility = if (signedIn) View.GONE else View.VISIBLE
                        binding.btnProfileSignOut.visibility = if (signedIn) View.VISIBLE else View.GONE
                        binding.layoutProfileStats.visibility = if (signedIn) View.VISIBLE else View.GONE
                        binding.layoutProfileLegacy.visibility = if (signedIn) View.VISIBLE else View.GONE
                        binding.tvProfileTitleBadge.visibility = if (signedIn) View.VISIBLE else View.GONE
                        if (user != null) {
                            binding.tvProfileAccountStatus.text = getString(
                                R.string.auth_signed_in_as,
                                user.displayName ?: getString(R.string.default_player_name)
                            )
                            binding.tvProfileEmail.text = user.email.orEmpty()
                        } else {
                            initializedInputs = false
                            binding.tvProfileAccountStatus.text = getString(R.string.auth_banner_title)
                            binding.tvProfileEmail.text = getString(R.string.auth_required_desc)
                        }
                        refreshPieceStyleLockState()
                        refreshLegacyAndTitle(mainViewModel.myProfile.value, mainViewModel.myCurrentRank.value)
                    }
                }
                launch {
                    mainViewModel.myProfile.collect { profile ->
                        if (profile != null) {
                            binding.tvStatWins.text = profile.wins.toString()
                            binding.tvStatLosses.text = profile.losses.toString()
                            binding.tvStatPoints.text = profile.points.toString()
                            if (!initializedInputs) {
                                initializedInputs = true
                                binding.etNickname.setText(profile.nickname)
                                selectedAvatarId = profile.avatarId
                                val loadedStyle = PieceStyle.fromKey(profile.pieceStyle)
                                selectedPieceStyle = if (loadedStyle.isUnlockedFor(
                                        mainViewModel.currentUser.value != null,
                                        mainViewModel.myCurrentRank.value
                                    )
                                ) {
                                    loadedStyle
                                } else {
                                    PieceStyle.CLASSIC
                                }
                                avatarAdapter.updateSelection(selectedAvatarId)
                                binding.ivProfileAvatar.setImageResource(
                                    AvatarCatalog.resolveDrawable(selectedAvatarId)
                                )
                            }
                        }
                        refreshPieceStyleLockState()
                        refreshLegacyAndTitle(profile, mainViewModel.myCurrentRank.value)
                    }
                }
                launch {
                    mainViewModel.myCurrentRank.collect { currentRank ->
                        refreshPieceStyleLockState()
                        refreshLegacyAndTitle(mainViewModel.myProfile.value, currentRank)
                    }
                }
                launch {
                    mainViewModel.isBusy.collect { busy ->
                        binding.btnSaveProfile.isEnabled = !busy
                    }
                }
                launch {
                    mainViewModel.events.collect { event ->
                        if (event is LobbyEvent.ShowMessage) {
                            Toast.makeText(
                                requireContext(),
                                event.message.asString(requireContext()),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                launch {
                    mainViewModel.matchHistoryState.collect { state ->
                        when (state) {
                            is UiState.Success -> {
                                val list = state.data
                                matchHistoryAdapter.submitList(list)
                                if (list.isEmpty()) {
                                    binding.rvMatchHistory.visibility = View.GONE
                                    binding.tvMatchHistoryEmpty.visibility = View.VISIBLE
                                } else {
                                    binding.rvMatchHistory.visibility = View.VISIBLE
                                    binding.tvMatchHistoryEmpty.visibility = View.GONE
                                }
                            }
                            is UiState.Error -> {
                                binding.rvMatchHistory.visibility = View.GONE
                                binding.tvMatchHistoryEmpty.visibility = View.VISIBLE
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun refreshPieceStyleLockState() {
        val signedIn = mainViewModel.currentUser.value != null
        val rank = mainViewModel.myCurrentRank.value
        if (!selectedPieceStyle.isUnlockedFor(signedIn, rank)) {
            selectedPieceStyle = PieceStyle.CLASSIC
        }
        pieceStyleAdapter.updateState(selectedPieceStyle, signedIn, rank)
    }

    private fun refreshLegacyAndTitle(profile: PlayerProfile?, currentRank: Int?) {
        val ctx = context ?: return
        val user = mainViewModel.currentUser.value ?: return
        val points = profile?.points ?: 100L
        val bestPoints = maxOf(profile?.bestPoints ?: points, points)
        val storedBestRank = profile?.bestRank?.takeIf { it > 0L }?.toInt()
        val effectiveBestRank = when {
            storedBestRank != null && currentRank != null -> minOf(storedBestRank, currentRank)
            storedBestRank != null -> storedBestRank
            else -> currentRank
        }

        // Current Rank Title Badge
        val tier = RankTier.resolve(points, currentRank)
        val titleText = getString(RankUiHelper.resolveTitleRes(tier))
        binding.tvProfileTitleBadge.text = getString(R.string.profile_current_title_fmt, titleText)
        binding.tvProfileTitleBadge.setTextColor(
            RankUiHelper.resolveTierColor(ctx, tier, binding.tvProfileTitleBadge)
        )

        // Peak Rank Metrics
        binding.tvPeakRank.text = if (effectiveBestRank != null && effectiveBestRank > 0) {
            getString(R.string.profile_rank_value_fmt, effectiveBestRank)
        } else {
            getString(R.string.profile_rank_unranked)
        }
        binding.tvCurrentRank.text = if (currentRank != null && currentRank > 0) {
            getString(R.string.profile_rank_value_fmt, currentRank)
        } else {
            getString(R.string.profile_rank_unranked)
        }
        binding.tvPeakPoints.text = bestPoints.toString()

        // Special Top 1/2/3 Hallmark Crest
        val topHallmarkRank = when {
            currentRank != null && currentRank in 1..3 -> currentRank
            effectiveBestRank != null && effectiveBestRank in 1..3 -> effectiveBestRank
            else -> null
        }

        if (topHallmarkRank != null && user.uid.isNotBlank()) {
            binding.cardSpecialHallmark.visibility = View.VISIBLE
            binding.tvHallmarkLockedHint.visibility = View.GONE
            binding.cardSpecialHallmark.setBackgroundResource(RankUiHelper.resolveHallmarkBgRes(topHallmarkRank))
            val accentColor = RankUiHelper.resolveHallmarkAccentColor(ctx, topHallmarkRank)
            binding.ivHallmarkCrest.setColorFilter(accentColor)
            binding.tvHallmarkTitle.setText(RankUiHelper.resolveHallmarkTitleRes(topHallmarkRank))
            binding.tvHallmarkTitle.setTextColor(accentColor)
            val isActiveTop3 = currentRank != null && currentRank in 1..3
            binding.tvHallmarkSubtitle.setText(
                if (isActiveTop3) R.string.hallmark_status_active else R.string.hallmark_status_peak
            )
        } else {
            binding.cardSpecialHallmark.visibility = View.GONE
            binding.tvHallmarkLockedHint.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
