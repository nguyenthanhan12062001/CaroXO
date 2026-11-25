package com.game.caroxo.an.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
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
import com.game.caroxo.an.databinding.FragmentChatBinding
import com.game.caroxo.an.model.AdminConfig
import com.game.caroxo.an.ui.FeatureDiscoveryHelper
import com.game.caroxo.an.viewmodel.LobbyEvent
import com.game.caroxo.an.viewmodel.MainViewModel
import com.game.caroxo.an.viewmodel.UiState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as CaroApplication).mainViewModelFactory
    }
    private lateinit var adapter: ChatAdapter
    private var lastMessageCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CaroApplication
        val sound = app.soundManager
        val prefs = app.preferencesManager

        adapter = ChatAdapter(mainViewModel.currentUser.value?.uid) { msg ->
            sound.playUiClick()
            PlayerProfileSheetHelper.show(
                fragment = this,
                mainViewModel = mainViewModel,
                preferencesManager = prefs,
                targetUid = msg.senderUid,
                fallbackNickname = msg.senderNickname,
                fallbackAvatarId = msg.senderAvatarId
            )
        }
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvGlobalChat.layoutManager = layoutManager
        binding.rvGlobalChat.adapter = adapter

        if (prefs.isOnboardingCompleted() && !prefs.isAllDiscoverySkipped() && !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_GLOBAL_CHAT)) {
            FeatureDiscoveryHelper.showTip(
                container = binding.layoutDiscoveryContainer,
                featureKey = PreferencesManager.FEATURE_GLOBAL_CHAT,
                iconRes = R.drawable.ic_chat,
                stepText = getString(R.string.discovery_step_fmt, 1, 1),
                titleRes = R.string.discovery_chat_title,
                descRes = R.string.discovery_chat_desc,
                prefs = prefs
            )
        }

        binding.btnChatGoogleSignIn.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.triggerGoogleSignIn()
        }

        binding.btnChatEmailSignIn.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.openEmailAuthSheet()
        }

        fun submitChat() {
            sound.playUiClick()
            val text = binding.etGlobalChatInput.text?.toString().orEmpty()
            mainViewModel.sendGlobalChatMessage(text) {
                _binding?.etGlobalChatInput?.setText("")
            }
        }

        binding.btnSendGlobalChat.setOnClickListener { submitChat() }

        binding.etGlobalChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitChat()
                true
            } else false
        }

        binding.btnAdminClearChat.setOnClickListener {
            sound.playUiClick()
            if (!AdminConfig.isAdmin(mainViewModel.currentUser.value?.uid)) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_CaroXO_AlertDialog)
                .setTitle(R.string.admin_clear_chat_title)
                .setMessage(R.string.admin_clear_chat_message)
                .setPositiveButton(R.string.admin_clear_chat_action) { _, _ ->
                    mainViewModel.clearGlobalChatHistory()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainViewModel.currentUser.collect { user ->
                        val signedIn = user != null
                        val isAdmin = AdminConfig.isAdmin(user?.uid)
                        adapter.setCurrentUserId(user?.uid)
                        binding.btnAdminClearChat.visibility = if (isAdmin) View.VISIBLE else View.GONE
                        binding.cardChatAuthGate.visibility = if (signedIn) View.GONE else View.VISIBLE
                        binding.cardChatComposer.visibility = if (signedIn) View.VISIBLE else View.GONE
                        if (!signedIn) {
                            binding.rvGlobalChat.visibility = View.GONE
                            binding.spacerChatBottom.visibility = View.GONE
                            binding.cardChatLoading.visibility = View.GONE
                            binding.cardChatEmpty.visibility = View.GONE
                            binding.cardChatError.visibility = View.GONE
                        }
                    }
                }

                launch {
                    mainViewModel.globalChatState.collect { state ->
                        if (mainViewModel.currentUser.value == null) return@collect
                        when (state) {
                            is UiState.Idle -> {
                                binding.cardChatLoading.visibility = View.GONE
                                binding.cardChatEmpty.visibility = View.GONE
                                binding.cardChatError.visibility = View.GONE
                                binding.rvGlobalChat.visibility = View.GONE
                                binding.spacerChatBottom.visibility = View.VISIBLE
                            }
                            is UiState.Loading -> {
                                binding.cardChatLoading.visibility = View.VISIBLE
                                binding.cardChatEmpty.visibility = View.GONE
                                binding.cardChatError.visibility = View.GONE
                                binding.rvGlobalChat.visibility = View.GONE
                                binding.spacerChatBottom.visibility = View.VISIBLE
                            }
                            is UiState.Success -> {
                                binding.cardChatLoading.visibility = View.GONE
                                binding.cardChatError.visibility = View.GONE
                                val list = state.data
                                val isEmpty = list.isEmpty()
                                binding.cardChatEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                                binding.rvGlobalChat.visibility = if (isEmpty) View.GONE else View.VISIBLE
                                binding.spacerChatBottom.visibility = if (isEmpty) View.VISIBLE else View.GONE
                                adapter.submitList(list) {
                                    if (list.isNotEmpty() && list.size >= lastMessageCount) {
                                        _binding?.rvGlobalChat?.scrollToPosition(list.size - 1)
                                    }
                                    lastMessageCount = list.size
                                }
                            }
                            is UiState.Error -> {
                                binding.cardChatLoading.visibility = View.GONE
                                binding.cardChatEmpty.visibility = View.GONE
                                binding.rvGlobalChat.visibility = View.GONE
                                binding.spacerChatBottom.visibility = View.VISIBLE
                                binding.cardChatError.visibility = View.VISIBLE
                                binding.tvChatErrorMessage.text = state.message.asString(requireContext())
                            }
                        }
                    }
                }

                launch {
                    mainViewModel.isSendingGlobalChat.collect { sending ->
                        binding.btnSendGlobalChat.isEnabled = !sending
                        binding.btnSendGlobalChat.alpha = if (sending) 0.5f else 1f
                    }
                }

                launch {
                    mainViewModel.isBusy.collect { busy ->
                        binding.btnAdminClearChat.isEnabled = !busy
                        binding.btnAdminClearChat.alpha = if (busy) 0.5f else 1f
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
