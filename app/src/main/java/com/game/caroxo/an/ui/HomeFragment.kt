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
import com.game.caroxo.an.CaroApplication
import com.game.caroxo.an.MainActivity
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.databinding.FragmentHomeBinding
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.GameMode
import com.game.caroxo.an.viewmodel.LobbyEvent
import com.game.caroxo.an.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as CaroApplication).mainViewModelFactory
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CaroApplication
        val prefs = app.preferencesManager
        val sound = app.soundManager

        // Initialize rule selections from PreferencesManager
        when (prefs.settings.value.defaultBoardSize) {
            10 -> binding.chipSize10.isChecked = true
            20 -> binding.chipSize20.isChecked = true
            else -> binding.chipSize15.isChecked = true
        }
        binding.switchBlockTwoEnds.isChecked = prefs.settings.value.defaultBlockTwoEnds

        binding.chipGroupBoardSize.setOnCheckedStateChangeListener { _, checkedIds ->
            sound.playUiClick()
            val size = when (checkedIds.firstOrNull()) {
                R.id.chip_size_10 -> 10
                R.id.chip_size_20 -> 20
                else -> 15
            }
            prefs.setDefaultBoardSize(size)
        }

        binding.switchBlockTwoEnds.setOnCheckedChangeListener { _, isChecked ->
            sound.playUiClick()
            prefs.setDefaultBlockTwoEnds(isChecked)
        }

        binding.btnFriendsHome.setOnClickListener {
            sound.playUiClick()
            if (mainViewModel.currentUser.value == null) {
                (activity as? MainActivity)?.promptSignInForOnline()
            } else {
                (activity as? MainActivity)?.openFriendsScreen()
            }
        }

        binding.btnAboutHome.setOnClickListener {
            sound.playUiClick()
            AboutDialogHelper.show(requireContext())
        }

        binding.btnGoogleSignInHome.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.triggerGoogleSignIn()
        }

        binding.btnEmailSignInHome.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.openEmailAuthSheet()
        }

        binding.btnAiEasy.setOnClickListener {
            sound.playUiClick()
            launchOffline(GameMode.AI_EASY, getString(R.string.ai_easy))
        }
        binding.btnAiMedium.setOnClickListener {
            sound.playUiClick()
            launchOffline(GameMode.AI_MEDIUM, getString(R.string.ai_medium))
        }
        binding.btnAiHard.setOnClickListener {
            sound.playUiClick()
            launchOffline(GameMode.AI_HARD, getString(R.string.ai_hard))
        }

        binding.btnPassPlay.setOnClickListener {
            sound.playUiClick()
            launchOffline(GameMode.PASS_AND_PLAY, "")
        }
        binding.cardPassPlay.setOnClickListener {
            sound.playUiClick()
            launchOffline(GameMode.PASS_AND_PLAY, "")
        }

        binding.btnCreateRoom.setOnClickListener {
            sound.playUiClick()
            if (mainViewModel.currentUser.value == null) {
                (activity as? MainActivity)?.promptSignInForOnline()
                return@setOnClickListener
            }
            val size = selectedBoardSize()
            val block = binding.switchBlockTwoEnds.isChecked
            mainViewModel.createOnlineRoom(size, block)
        }

        binding.btnPlayWithFriends.setOnClickListener {
            sound.playUiClick()
            if (mainViewModel.currentUser.value == null) {
                (activity as? MainActivity)?.promptSignInForOnline()
                return@setOnClickListener
            }
            (activity as? MainActivity)?.openFriendsScreen()
        }

        binding.btnJoinRoom.setOnClickListener {
            sound.playUiClick()
            if (mainViewModel.currentUser.value == null) {
                (activity as? MainActivity)?.promptSignInForOnline()
                return@setOnClickListener
            }
            val code = binding.etRoomCode.text?.toString().orEmpty().trim()
            mainViewModel.joinOnlineRoom(code)
        }

        observeState()
        checkAndShowFeatureDiscovery()

        val isAdmin = com.game.caroxo.an.model.AdminConfig.isAdmin(mainViewModel.currentUser.value?.uid)
        app.adManager.attachAdaptiveBanner(binding.adBannerContainer, viewLifecycleOwner, isAdmin)
    }

    override fun onResume() {
        super.onResume()
        checkAndShowFeatureDiscovery()
    }

    fun checkAndShowFeatureDiscovery() {
        val prefs = (activity?.application as? CaroApplication)?.preferencesManager ?: return
        if (!prefs.isOnboardingCompleted() || prefs.isAllDiscoverySkipped()) {
            _binding?.layoutDiscoveryContainer?.visibility = View.GONE
            return
        }

        when {
            !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_BOARD_RULES) -> {
                FeatureDiscoveryHelper.showTip(
                    container = binding.layoutDiscoveryContainer,
                    featureKey = PreferencesManager.FEATURE_BOARD_RULES,
                    iconRes = R.drawable.ic_tune,
                    stepText = getString(R.string.discovery_step_fmt, 1, 4),
                    titleRes = R.string.discovery_home_rules_title,
                    descRes = R.string.discovery_home_rules_desc,
                    prefs = prefs
                ) {
                    checkAndShowFeatureDiscovery()
                }
            }
            !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_AI_MODES) -> {
                FeatureDiscoveryHelper.showTip(
                    container = binding.layoutDiscoveryContainer,
                    featureKey = PreferencesManager.FEATURE_AI_MODES,
                    iconRes = R.drawable.ic_cpu,
                    stepText = getString(R.string.discovery_step_fmt, 2, 4),
                    titleRes = R.string.discovery_home_modes_title,
                    descRes = R.string.discovery_home_modes_desc,
                    prefs = prefs
                ) {
                    checkAndShowFeatureDiscovery()
                }
            }
            !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_ONLINE_ROOM) -> {
                FeatureDiscoveryHelper.showTip(
                    container = binding.layoutDiscoveryContainer,
                    featureKey = PreferencesManager.FEATURE_ONLINE_ROOM,
                    iconRes = R.drawable.ic_globe,
                    stepText = getString(R.string.discovery_step_fmt, 3, 4),
                    titleRes = R.string.discovery_home_online_title,
                    descRes = R.string.discovery_home_online_desc,
                    prefs = prefs
                ) {
                    checkAndShowFeatureDiscovery()
                }
            }
            !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_HOME_FRIENDS) -> {
                FeatureDiscoveryHelper.showTip(
                    container = binding.layoutDiscoveryContainer,
                    featureKey = PreferencesManager.FEATURE_HOME_FRIENDS,
                    iconRes = R.drawable.ic_people,
                    stepText = getString(R.string.discovery_step_fmt, 4, 4),
                    titleRes = R.string.discovery_home_friends_title,
                    descRes = R.string.discovery_home_friends_desc,
                    prefs = prefs
                )
            }
            else -> {
                _binding?.layoutDiscoveryContainer?.visibility = View.GONE
            }
        }
    }

    private fun selectedBoardSize(): Int = when (binding.chipGroupBoardSize.checkedChipId) {
        R.id.chip_size_10 -> 10
        R.id.chip_size_20 -> 20
        else -> 15
    }

    private fun launchOffline(mode: GameMode, aiLabel: String) {
        (activity as? MainActivity)?.openOfflineGame(
            mode = mode,
            boardSize = selectedBoardSize(),
            blockTwoEnds = binding.switchBlockTwoEnds.isChecked,
            aiDifficultyLabel = aiLabel
        )
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainViewModel.currentUser.collect { user ->
                        val signedIn = user != null
                        binding.layoutSignedOutBanner.visibility = if (signedIn) View.GONE else View.VISIBLE
                        binding.layoutSignedInBanner.visibility = if (signedIn) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    mainViewModel.myProfile.collect { profile ->
                        if (profile != null) {
                            binding.ivHomeAvatar.setImageResource(AvatarCatalog.resolveDrawable(profile.avatarId))
                            binding.tvHomeNickname.text = profile.nickname
                            binding.tvHomeStats.text = getString(
                                R.string.leaderboard_record_format,
                                profile.wins.toInt(),
                                profile.losses.toInt()
                            )
                            binding.tvHomePointsPill.text =
                                getString(R.string.leaderboard_points_format, profile.points.toInt())
                        }
                    }
                }
                launch {
                    mainViewModel.isBusy.collect { busy ->
                        binding.btnCreateRoom.isEnabled = !busy
                        binding.btnJoinRoom.isEnabled = !busy
                    }
                }
                launch {
                    mainViewModel.pendingSocialCount.collect { count ->
                        if (count > 0) {
                            binding.tvFriendsBadgeHome.visibility = View.VISIBLE
                            binding.tvFriendsBadgeHome.text = if (count > 9) "9+" else count.toString()
                        } else {
                            binding.tvFriendsBadgeHome.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
