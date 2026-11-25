package com.game.caroxo.an.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.game.caroxo.an.databinding.FragmentFriendsBinding
import com.game.caroxo.an.model.Friendship
import com.game.caroxo.an.viewmodel.LobbyEvent
import com.game.caroxo.an.viewmodel.MainViewModel
import com.game.caroxo.an.viewmodel.UiState
import kotlinx.coroutines.launch

class FriendsFragment : Fragment() {

    private var _binding: FragmentFriendsBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels {
        (requireActivity().application as CaroApplication).mainViewModelFactory
    }

    private lateinit var friendsAdapter: FriendsAdapter
    private lateinit var incomingAdapter: FriendRequestsAdapter
    private lateinit var outgoingAdapter: FriendRequestsAdapter
    private lateinit var invitesAdapter: GameInvitesAdapter
    private lateinit var searchAdapter: PlayerSearchAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CaroApplication
        val prefs = app.preferencesManager
        val sound = app.soundManager
        val myUid = mainViewModel.currentUser.value?.uid.orEmpty()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.exitFriendsScreen()
                }
            }
        )

        binding.btnFriendsBack.setOnClickListener {
            sound.playUiClick()
            (activity as? MainActivity)?.exitFriendsScreen()
        }

        binding.tvMyPlayerId.text = myUid
        binding.btnCopyMyPlayerId.setOnClickListener {
            sound.playUiClick()
            if (myUid.isNotBlank()) {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PlayerID", myUid))
                Toast.makeText(
                    requireContext(),
                    getString(R.string.friends_id_copied, myUid),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val openPlayerProfile: (String, String, String) -> Unit = { uid, nick, avatar ->
            sound.playUiClick()
            PlayerProfileSheetHelper.show(
                fragment = this,
                mainViewModel = mainViewModel,
                preferencesManager = prefs,
                targetUid = uid,
                fallbackNickname = nick,
                fallbackAvatarId = avatar
            )
        }

        friendsAdapter = FriendsAdapter(
            myUid = myUid,
            onFriendClick = openPlayerProfile,
            onInviteClick = { friendUid, friendNick ->
                sound.playUiClick()
                val settings = prefs.settings.value
                mainViewModel.inviteFriendToPlay(
                    friendUid = friendUid,
                    friendNickname = friendNick,
                    boardSize = settings.defaultBoardSize,
                    blockTwoEnds = settings.defaultBlockTwoEnds
                )
            }
        )
        binding.rvFriendsList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFriendsList.adapter = friendsAdapter

        invitesAdapter = GameInvitesAdapter(
            onAcceptClick = { invite ->
                sound.playUiClick()
                mainViewModel.acceptGameInvite(invite)
            },
            onDeclineClick = { invite ->
                sound.playUiClick()
                mainViewModel.declineGameInvite(invite)
            }
        )
        binding.rvGameInvites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGameInvites.adapter = invitesAdapter

        incomingAdapter = FriendRequestsAdapter(
            myUid = myUid,
            isIncoming = true,
            onPlayerClick = openPlayerProfile,
            onAcceptClick = { req ->
                sound.playUiClick()
                mainViewModel.acceptFriendRequest(req.id)
            },
            onDeclineOrCancelClick = { req ->
                sound.playUiClick()
                mainViewModel.removeOrDeclineFriendship(req.id, R.string.friend_request_declined_toast)
            }
        )
        binding.rvIncomingRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvIncomingRequests.adapter = incomingAdapter

        outgoingAdapter = FriendRequestsAdapter(
            myUid = myUid,
            isIncoming = false,
            onPlayerClick = openPlayerProfile,
            onAcceptClick = {},
            onDeclineOrCancelClick = { req ->
                sound.playUiClick()
                mainViewModel.removeOrDeclineFriendship(req.id, R.string.friend_request_cancelled_toast)
            }
        )
        binding.rvOutgoingRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOutgoingRequests.adapter = outgoingAdapter

        searchAdapter = PlayerSearchAdapter(
            myUid = myUid,
            onPlayerClick = { entry ->
                openPlayerProfile(entry.userId, entry.nickname, entry.avatarId)
            },
            onActionClick = { entry, friendship ->
                sound.playUiClick()
                when {
                    friendship?.status == "ACCEPTED" -> {
                        val settings = prefs.settings.value
                        mainViewModel.inviteFriendToPlay(
                            friendUid = entry.userId,
                            friendNickname = entry.nickname,
                            boardSize = settings.defaultBoardSize,
                            blockTwoEnds = settings.defaultBlockTwoEnds
                        )
                    }
                    friendship?.status == "PENDING" && friendship.recipientUid == myUid -> {
                        mainViewModel.acceptFriendRequest(friendship.id)
                    }
                    friendship?.status == "PENDING" -> Unit
                    else -> {
                        mainViewModel.sendFriendRequest(entry)
                    }
                }
            }
        )
        binding.rvPlayerSearch.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlayerSearch.adapter = searchAdapter

        binding.toggleFriendsTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sound.playUiClick()
            when (checkedId) {
                R.id.btn_tab_friends_list -> showTab(0)
                R.id.btn_tab_friends_requests -> showTab(1)
                R.id.btn_tab_friends_find -> {
                    showTab(2)
                    if (mainViewModel.playerSearchState.value is UiState.Idle) {
                        mainViewModel.searchPlayers("")
                    }
                }
            }
        }

        val initialTab = arguments?.getInt(ARG_INITIAL_TAB, 0) ?: 0
        when (initialTab) {
            1 -> binding.toggleFriendsTabs.check(R.id.btn_tab_friends_requests)
            2 -> binding.toggleFriendsTabs.check(R.id.btn_tab_friends_find)
            else -> binding.toggleFriendsTabs.check(R.id.btn_tab_friends_list)
        }

        fun runSearch() {
            sound.playUiClick()
            val query = binding.etPlayerSearch.text?.toString().orEmpty()
            mainViewModel.searchPlayers(query)
        }

        binding.btnSearchPlayers.setOnClickListener { runSearch() }
        binding.etPlayerSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }

        if (prefs.isOnboardingCompleted() && !prefs.isAllDiscoverySkipped() && !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_FRIENDS_SOCIAL)) {
            FeatureDiscoveryHelper.showTip(
                container = binding.layoutDiscoveryContainer,
                featureKey = PreferencesManager.FEATURE_FRIENDS_SOCIAL,
                iconRes = R.drawable.ic_people,
                stepText = getString(R.string.discovery_step_fmt, 1, 1),
                titleRes = R.string.discovery_friends_title,
                descRes = R.string.discovery_friends_desc,
                prefs = prefs
            )
        }

        observeState(myUid)
    }

    private fun showTab(index: Int) {
        binding.layoutTabFriendsList.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.layoutTabFriendsRequests.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.layoutTabFriendsFind.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun observeState(myUid: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainViewModel.friendshipsState.collect { state ->
                        val allFriendships: List<Friendship> = (state as? UiState.Success)?.data.orEmpty()
                        val accepted = allFriendships.filter { it.status == "ACCEPTED" }
                        val incoming = allFriendships.filter { it.status == "PENDING" && it.recipientUid == myUid }
                        val outgoing = allFriendships.filter { it.status == "PENDING" && it.requesterUid == myUid }

                        friendsAdapter.submitList(accepted)
                        binding.cardFriendsEmpty.visibility = if (accepted.isEmpty()) View.VISIBLE else View.GONE
                        binding.rvFriendsList.visibility = if (accepted.isEmpty()) View.GONE else View.VISIBLE

                        incomingAdapter.submitList(incoming)
                        outgoingAdapter.submitList(outgoing)
                        searchAdapter.updateFriendships(allFriendships)

                        renderRequestsVisibility(
                            invitesCount = mainViewModel.incomingGameInvites.value.size,
                            incomingCount = incoming.size,
                            outgoingCount = outgoing.size
                        )
                    }
                }
                launch {
                    mainViewModel.friendsOnlineMap.collect { statusMap ->
                        friendsAdapter.updateOnlineStatuses(statusMap)
                    }
                }
                launch {
                    mainViewModel.incomingGameInvites.collect { invites ->
                        invitesAdapter.submitList(invites)
                        val all = (mainViewModel.friendshipsState.value as? UiState.Success)?.data.orEmpty()
                        val incomingCount = all.count { it.status == "PENDING" && it.recipientUid == myUid }
                        val outgoingCount = all.count { it.status == "PENDING" && it.requesterUid == myUid }
                        renderRequestsVisibility(invites.size, incomingCount, outgoingCount)
                    }
                }
                launch {
                    mainViewModel.pendingSocialCount.collect { count ->
                        val baseLabel = getString(R.string.friends_tab_requests)
                        binding.btnTabFriendsRequests.text = if (count > 0) "$baseLabel ($count)" else baseLabel
                    }
                }
                launch {
                    mainViewModel.playerSearchState.collect { state ->
                        when (state) {
                            is UiState.Idle -> {
                                binding.progressSearchPlayers.visibility = View.GONE
                                binding.cardSearchEmpty.visibility = View.GONE
                                binding.rvPlayerSearch.visibility = View.GONE
                            }
                            is UiState.Loading -> {
                                binding.progressSearchPlayers.visibility = View.VISIBLE
                                binding.cardSearchEmpty.visibility = View.GONE
                                binding.rvPlayerSearch.visibility = View.GONE
                            }
                            is UiState.Success -> {
                                binding.progressSearchPlayers.visibility = View.GONE
                                searchAdapter.submitList(state.data)
                                val isEmpty = state.data.isEmpty()
                                binding.cardSearchEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                                binding.tvSearchEmptyMsg.setText(R.string.friends_search_empty)
                                binding.rvPlayerSearch.visibility = if (isEmpty) View.GONE else View.VISIBLE
                            }
                            is UiState.Error -> {
                                binding.progressSearchPlayers.visibility = View.GONE
                                binding.rvPlayerSearch.visibility = View.GONE
                                binding.cardSearchEmpty.visibility = View.VISIBLE
                                binding.tvSearchEmptyMsg.text = state.message.asString(requireContext())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderRequestsVisibility(invitesCount: Int, incomingCount: Int, outgoingCount: Int) {
        binding.tvSectionGameInvites.visibility = if (invitesCount > 0) View.VISIBLE else View.GONE
        binding.rvGameInvites.visibility = if (invitesCount > 0) View.VISIBLE else View.GONE

        binding.tvSectionIncomingRequests.visibility = if (incomingCount > 0) View.VISIBLE else View.GONE
        binding.rvIncomingRequests.visibility = if (incomingCount > 0) View.VISIBLE else View.GONE

        binding.tvSectionOutgoingRequests.visibility = if (outgoingCount > 0) View.VISIBLE else View.GONE
        binding.rvOutgoingRequests.visibility = if (outgoingCount > 0) View.VISIBLE else View.GONE

        val allEmpty = invitesCount == 0 && incomingCount == 0 && outgoingCount == 0
        binding.cardRequestsEmpty.visibility = if (allEmpty) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_INITIAL_TAB = "initial_tab"

        fun newInstance(initialTab: Int = 0): FriendsFragment {
            return FriendsFragment().apply {
                arguments = Bundle().apply { putInt(ARG_INITIAL_TAB, initialTab) }
            }
        }
    }
}
