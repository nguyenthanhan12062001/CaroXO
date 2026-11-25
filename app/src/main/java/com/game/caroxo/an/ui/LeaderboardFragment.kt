package com.game.caroxo.an.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.game.caroxo.an.CaroApplication
import com.game.caroxo.an.MainActivity
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.databinding.FragmentLeaderboardBinding
import com.game.caroxo.an.ui.FeatureDiscoveryHelper
import com.game.caroxo.an.viewmodel.MainViewModel
import com.game.caroxo.an.viewmodel.UiState
import kotlinx.coroutines.launch

class LeaderboardFragment : Fragment() {

    private var _binding: FragmentLeaderboardBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as CaroApplication).mainViewModelFactory
    }
    private lateinit var adapter: LeaderboardAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLeaderboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CaroApplication
        val sound = app.soundManager
        val prefs = app.preferencesManager

        adapter = LeaderboardAdapter(mainViewModel.currentUser.value?.uid)
        binding.rvLeaderboard.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLeaderboard.adapter = adapter

        if (prefs.isOnboardingCompleted() && !prefs.isAllDiscoverySkipped() && !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_LEADERBOARD_VIP)) {
            FeatureDiscoveryHelper.showTip(
                container = binding.layoutDiscoveryContainer,
                featureKey = PreferencesManager.FEATURE_LEADERBOARD_VIP,
                iconRes = R.drawable.ic_trophy,
                stepText = getString(R.string.discovery_step_fmt, 1, 1),
                titleRes = R.string.discovery_lb_title,
                descRes = R.string.discovery_lb_desc,
                prefs = prefs
            )
        }

        binding.btnLbGoogleSignIn.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.triggerGoogleSignIn()
        }

        binding.btnLbEmailSignIn.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.openEmailAuthSheet()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainViewModel.currentUser.collect { user ->
                        val signedIn = user != null
                        adapter.setCurrentUserId(user?.uid)
                        binding.cardLbAuthGate.visibility = if (signedIn) View.GONE else View.VISIBLE
                        if (!signedIn) {
                            binding.rvLeaderboard.visibility = View.GONE
                            binding.cardLbLoading.visibility = View.GONE
                            binding.cardLbEmpty.visibility = View.GONE
                            binding.cardLbError.visibility = View.GONE
                        }
                    }
                }
                launch {
                    mainViewModel.leaderboardState.collect { state ->
                        if (mainViewModel.currentUser.value == null) return@collect
                        when (state) {
                            is UiState.Idle -> {
                                binding.cardLbLoading.visibility = View.GONE
                                binding.cardLbEmpty.visibility = View.GONE
                                binding.cardLbError.visibility = View.GONE
                                binding.rvLeaderboard.visibility = View.GONE
                            }
                            is UiState.Loading -> {
                                binding.cardLbLoading.visibility = View.VISIBLE
                                binding.cardLbEmpty.visibility = View.GONE
                                binding.cardLbError.visibility = View.GONE
                                binding.rvLeaderboard.visibility = View.GONE
                            }
                            is UiState.Success -> {
                                binding.cardLbLoading.visibility = View.GONE
                                binding.cardLbError.visibility = View.GONE
                                adapter.submitList(state.data)
                                val isEmpty = state.data.isEmpty()
                                binding.cardLbEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                                binding.rvLeaderboard.visibility = if (isEmpty) View.GONE else View.VISIBLE
                            }
                            is UiState.Error -> {
                                binding.cardLbLoading.visibility = View.GONE
                                binding.cardLbEmpty.visibility = View.GONE
                                binding.rvLeaderboard.visibility = View.GONE
                                binding.cardLbError.visibility = View.VISIBLE
                                binding.tvLbErrorMessage.text = state.message.asString(requireContext())
                            }
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
