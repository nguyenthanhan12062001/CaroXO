package com.game.caroxo.an.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
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
import com.game.caroxo.an.databinding.FragmentGameBinding
import com.game.caroxo.an.databinding.SheetMatchResultBinding
import com.game.caroxo.an.databinding.SheetQuickSettingsBinding
import com.game.caroxo.an.databinding.SheetRoomChatBinding
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.BoardSurface
import com.game.caroxo.an.model.GameMode
import com.game.caroxo.an.model.GameStatus
import com.game.caroxo.an.model.Piece
import com.game.caroxo.an.viewmodel.GameEffect
import com.game.caroxo.an.viewmodel.GameUiState
import com.game.caroxo.an.viewmodel.GameViewModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class GameFragment : Fragment() {

    private var _binding: FragmentGameBinding? = null
    private val binding get() = _binding!!

    private val gameViewModel: GameViewModel by activityViewModels {
        (requireActivity().application as CaroApplication).gameViewModelFactory
    }

    private var turnBlinkAnimator: ObjectAnimator? = null
    private var lastBlinkingTurn: Piece? = null
    private var lastShownFinishedState: GameStatus? = null
    private var activeResultSheet: BottomSheetDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CaroApplication
        val sound = app.soundManager
        val prefs = app.preferencesManager

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleExitRequested()
                }
            }
        )

        binding.btnBackGame.setOnClickListener {
            sound.playUiClick()
            handleExitRequested()
        }

        binding.layoutRoomCode.setOnClickListener {
            val code = gameViewModel.uiState.value.onlineRoomCode ?: return@setOnClickListener
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Room Code", code))
            Toast.makeText(
                requireContext(),
                getString(R.string.room_code_copied, code),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.caroBoardView.onCellTapped = { row, col ->
            gameViewModel.onCellTapped(row, col)
        }

        binding.caroBoardView.onZoomChanged = { scale ->
            binding.btnZoomReset.visibility = if (scale > 1.05f) View.VISIBLE else View.GONE
        }

        binding.btnZoomReset.setOnClickListener {
            sound.playUiClick()
            binding.caroBoardView.resetZoom()
        }

        binding.btnUndo.setOnClickListener {
            gameViewModel.undoMove()
        }

        binding.btnHint.setOnClickListener {
            gameViewModel.requestHint()
        }

        binding.btnReset.setOnClickListener {
            val state = gameViewModel.uiState.value
            if (state.mode == GameMode.ONLINE) return@setOnClickListener
            if (state.status == GameStatus.PLAYING && state.moveHistory.isNotEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm_reset_title)
                    .setMessage(R.string.confirm_reset_message)
                    .setPositiveButton(R.string.action_confirm) { _, _ ->
                        gameViewModel.resetOfflineGame()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else {
                gameViewModel.resetOfflineGame()
            }
        }

        binding.btnRoomChat.setOnClickListener {
            sound.playUiClick()
            showRoomChatSheet(sound)
        }

        binding.btnGameMenu.setOnClickListener { anchor ->
            sound.playUiClick()
            showGameMenu(anchor, prefs, sound)
        }

        if (prefs.isOnboardingCompleted() && !prefs.isAllDiscoverySkipped() && !prefs.isFeatureDiscovered(PreferencesManager.FEATURE_GAME_GESTURES)) {
            FeatureDiscoveryHelper.showTip(
                container = binding.layoutDiscoveryContainer,
                featureKey = PreferencesManager.FEATURE_GAME_GESTURES,
                iconRes = R.drawable.ic_grid,
                stepText = getString(R.string.discovery_step_fmt, 1, 1),
                titleRes = R.string.discovery_game_controls_title,
                descRes = R.string.discovery_game_controls_desc,
                prefs = prefs
            )
        }

        observeGameState()
    }

    private fun showRoomChatSheet(sound: com.game.caroxo.an.audio.SoundAndHapticsManager) {
        val state = gameViewModel.uiState.value
        val roomCode = state.onlineRoomCode ?: return
        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)
        val sheetBinding = SheetRoomChatBinding.inflate(LayoutInflater.from(ctx))

        sheetBinding.tvRoomChatSubtitle.text = getString(R.string.chat_room_subtitle, roomCode)
        val chatAdapter = ChatAdapter(FirebaseAuth.getInstance().currentUser?.uid)
        sheetBinding.rvRoomChat.layoutManager = LinearLayoutManager(ctx).apply {
            stackFromEnd = true
        }
        sheetBinding.rvRoomChat.adapter = chatAdapter

        gameViewModel.setRoomChatOpen(true)

        fun submitRoomMessage() {
            sound.playUiClick()
            val text = sheetBinding.etRoomChatInput.text?.toString().orEmpty()
            gameViewModel.sendRoomChatMessage(text) {
                sheetBinding.etRoomChatInput.setText("")
            }
        }

        sheetBinding.btnSendRoomChat.setOnClickListener { submitRoomMessage() }
        sheetBinding.etRoomChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitRoomMessage()
                true
            } else false
        }
        sheetBinding.btnCloseRoomChat.setOnClickListener {
            sound.playUiClick()
            sheet.dismiss()
        }

        val chatObserverJob: Job = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                gameViewModel.roomMessages.collect { messages ->
                    sheetBinding.tvRoomChatEmpty.visibility =
                        if (messages.isEmpty()) View.VISIBLE else View.GONE
                    chatAdapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            sheetBinding.rvRoomChat.scrollToPosition(messages.size - 1)
                        }
                    }
                }
            }
            launch {
                gameViewModel.isSendingRoomChat.collect { sending ->
                    sheetBinding.btnSendRoomChat.isEnabled = !sending
                    sheetBinding.btnSendRoomChat.alpha = if (sending) 0.5f else 1f
                }
            }
        }

        sheet.setOnDismissListener {
            chatObserverJob.cancel()
            gameViewModel.setRoomChatOpen(false)
        }

        sheet.setContentView(sheetBinding.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun showGameMenu(
        anchor: View,
        prefs: com.game.caroxo.an.data.PreferencesManager,
        sound: com.game.caroxo.an.audio.SoundAndHapticsManager
    ) {
        val state = gameViewModel.uiState.value
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.menu_quick_settings)
        popup.menu.add(0, 2, 1, R.string.menu_about)
        if (state.mode == GameMode.ONLINE && state.status == GameStatus.PLAYING) {
            popup.menu.add(0, 3, 2, R.string.menu_resign)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    showQuickSettingsSheet(prefs, sound)
                    true
                }
                2 -> {
                    AboutDialogHelper.show(requireContext())
                    true
                }
                3 -> {
                    confirmResignAndExit()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showQuickSettingsSheet(
        prefs: com.game.caroxo.an.data.PreferencesManager,
        sound: com.game.caroxo.an.audio.SoundAndHapticsManager
    ) {
        val ctx = requireContext()
        val sheet = BottomSheetDialog(ctx)
        val sheetBinding = SheetQuickSettingsBinding.inflate(LayoutInflater.from(ctx))
        val current = prefs.settings.value

        fun refreshSwatches(selected: BoardSurface) {
            val activeColor = MaterialColors.getColor(sheetBinding.root, com.google.android.material.R.attr.colorPrimary)
            val inactiveColor = MaterialColors.getColor(sheetBinding.root, com.google.android.material.R.attr.colorOutline)
            val density = ctx.resources.displayMetrics.density
            val activeStroke = (2.5f * density).roundToInt().coerceAtLeast(3)
            val inactiveStroke = (1f * density).roundToInt().coerceAtLeast(1)

            val mapping: List<Pair<MaterialCardView, BoardSurface>> = listOf(
                sheetBinding.cardQuickCream to BoardSurface.MINIMAL_CREAM,
                sheetBinding.cardQuickWood to BoardSurface.WARM_WOOD,
                sheetBinding.cardQuickEmerald to BoardSurface.EMERALD_GRID,
                sheetBinding.cardQuickSlate to BoardSurface.DARK_SLATE
            )
            for ((card, surface) in mapping) {
                val isSelected = surface == selected
                card.strokeColor = if (isSelected) activeColor else inactiveColor
                card.strokeWidth = if (isSelected) activeStroke else inactiveStroke
            }
        }

        refreshSwatches(current.boardSurface)
        sheetBinding.cardQuickCream.setOnClickListener {
            sound.playUiClick()
            prefs.setBoardSurface(BoardSurface.MINIMAL_CREAM)
            refreshSwatches(BoardSurface.MINIMAL_CREAM)
        }
        sheetBinding.cardQuickWood.setOnClickListener {
            sound.playUiClick()
            prefs.setBoardSurface(BoardSurface.WARM_WOOD)
            refreshSwatches(BoardSurface.WARM_WOOD)
        }
        sheetBinding.cardQuickEmerald.setOnClickListener {
            sound.playUiClick()
            prefs.setBoardSurface(BoardSurface.EMERALD_GRID)
            refreshSwatches(BoardSurface.EMERALD_GRID)
        }
        sheetBinding.cardQuickSlate.setOnClickListener {
            sound.playUiClick()
            prefs.setBoardSurface(BoardSurface.DARK_SLATE)
            refreshSwatches(BoardSurface.DARK_SLATE)
        }

        sheetBinding.switchQuickSfx.isChecked = current.sfxEnabled
        sheetBinding.switchQuickHaptics.isChecked = current.hapticsEnabled
        sheetBinding.switchQuickSfx.setOnCheckedChangeListener { _, isChecked ->
            prefs.setSfxEnabled(isChecked)
            if (isChecked) sound.playUiClick()
        }
        sheetBinding.switchQuickHaptics.setOnCheckedChangeListener { _, isChecked ->
            sound.playUiClick()
            prefs.setHapticsEnabled(isChecked)
            if (isChecked) sound.vibrateTap()
        }

        sheetBinding.btnCloseQuickSettings.setOnClickListener {
            sheet.dismiss()
        }

        sheet.setContentView(sheetBinding.root)
        sheet.show()
    }

    private fun handleExitRequested() {
        val state = gameViewModel.uiState.value
        if (state.mode == GameMode.ONLINE) {
            if (state.status == GameStatus.PLAYING) {
                confirmResignAndExit()
            } else {
                gameViewModel.resignOrLeaveOnlineGame {
                    (activity as? MainActivity)?.exitGameScreen()
                }
            }
        } else if (state.status == GameStatus.PLAYING && state.moveHistory.isNotEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm_exit_title)
                .setMessage(R.string.confirm_exit_message)
                .setPositiveButton(R.string.action_confirm) { _, _ ->
                    (activity as? MainActivity)?.exitGameScreen()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            (activity as? MainActivity)?.exitGameScreen()
        }
    }

    private fun confirmResignAndExit() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_resign_title)
            .setMessage(R.string.confirm_resign_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                gameViewModel.resignOrLeaveOnlineGame {
                    (activity as? MainActivity)?.exitGameScreen()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun observeGameState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    gameViewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    gameViewModel.unreadChatCount.collect { count ->
                        val showBadge = gameViewModel.uiState.value.mode == GameMode.ONLINE && count > 0
                        binding.tvRoomChatBadge.visibility = if (showBadge) View.VISIBLE else View.GONE
                        if (showBadge) {
                            binding.tvRoomChatBadge.text = if (count > 99) "99+" else count.toString()
                        }
                    }
                }
                launch {
                    gameViewModel.effects.collect { effect ->
                        when (effect) {
                            is GameEffect.ToastMessage -> {
                                Toast.makeText(
                                    requireContext(),
                                    effect.text.asString(requireContext()),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            is GameEffect.RematchDeclinedExit -> {
                                activeResultSheet?.dismiss()
                                activeResultSheet = null
                                (activity as? MainActivity)?.exitGameScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: GameUiState) {
        val ctx = requireContext()
        val isOnline = state.mode == GameMode.ONLINE
        binding.layoutRoomCode.visibility = if (isOnline) View.VISIBLE else View.GONE
        binding.tvRoomCode.text = state.onlineRoomCode?.let {
            getString(R.string.room_code_label, it)
        } ?: ""

        binding.tvMatchBadge.text = if (state.blockTwoEnds) {
            getString(R.string.match_badge_block_two_ends, state.boardSize)
        } else {
            "${state.boardSize}×${state.boardSize}"
        }

        binding.ivAvatarX.setImageResource(AvatarCatalog.resolveDrawable(state.playerXAvatarId))
        binding.ivAvatarO.setImageResource(AvatarCatalog.resolveDrawable(state.playerOAvatarId))
        binding.tvNameX.text = state.playerXName.asString(ctx)
        binding.tvNameO.text = state.playerOName.asString(ctx)
        binding.tvScoreX.text = state.playerXScore.toString()
        binding.tvScoreO.text = state.playerOScore.toString()

        if (isOnline) {
            binding.tvScoreX.visibility = View.GONE
            binding.tvScoreO.visibility = View.GONE
            binding.tvVsOrTimer.text = when (state.status) {
                GameStatus.WAITING -> "..."
                GameStatus.PLAYING -> getString(R.string.timer_seconds, state.remainingSeconds)
                else -> getString(R.string.status_end)
            }
        } else {
            binding.tvScoreX.visibility = View.VISIBLE
            binding.tvScoreO.visibility = View.VISIBLE
            binding.tvVsOrTimer.text = "VS"
        }

        // Turn highlight & blinking indicator
        val xActive = state.status == GameStatus.PLAYING && state.currentTurn == Piece.X
        val oActive = state.status == GameStatus.PLAYING && state.currentTurn == Piece.O
        binding.cardPlayerX.setBackgroundResource(
            if (xActive) R.drawable.bg_turn_active else R.drawable.bg_turn_inactive
        )
        binding.cardPlayerO.setBackgroundResource(
            if (oActive) R.drawable.bg_turn_active else R.drawable.bg_turn_inactive
        )
        binding.tvTurnX.visibility = if (xActive) View.VISIBLE else View.INVISIBLE
        binding.tvTurnO.visibility = if (oActive) View.VISIBLE else View.INVISIBLE

        updateTurnBlinkAnimation(if (xActive) Piece.X else if (oActive) Piece.O else null)

        binding.tvAiThinking.visibility = if (state.isAiThinking) View.VISIBLE else View.GONE

        // Controls availability
        binding.btnUndo.visibility = if (isOnline) View.GONE else View.VISIBLE
        binding.btnHint.visibility = if (isOnline) View.GONE else View.VISIBLE
        binding.btnReset.visibility = if (isOnline) View.GONE else View.VISIBLE
        binding.layoutRoomChatBtn.visibility = if (isOnline) View.VISIBLE else View.GONE
        binding.btnUndo.isEnabled = state.canUndo && !state.isAiThinking
        binding.btnHint.isEnabled = state.status == GameStatus.PLAYING && !state.isAiThinking

        // Update Custom Canvas Board View
        binding.caroBoardView.updateBoardState(
            size = state.boardSize,
            newBoard = state.board,
            lastMovePoint = state.lastMove,
            newWinLine = state.winLine,
            currentTurn = state.currentTurn,
            enabled = state.status == GameStatus.PLAYING && state.isMyTurn && !state.isAiThinking,
            xStyle = state.playerXStyle,
            oStyle = state.playerOStyle,
            surface = state.boardSurface,
            hint = state.hintPoint
        )

        // Show finish bottom sheet once per match conclusion
        if (state.status == GameStatus.X_WON || state.status == GameStatus.O_WON || state.status == GameStatus.DRAW) {
            if (lastShownFinishedState != state.status) {
                lastShownFinishedState = state.status
                showMatchResultDialog(state)
            }
        } else {
            lastShownFinishedState = null
            activeResultSheet?.dismiss()
            activeResultSheet = null
        }
    }

    private fun updateTurnBlinkAnimation(activeTurn: Piece?) {
        if (lastBlinkingTurn == activeTurn) return
        lastBlinkingTurn = activeTurn
        turnBlinkAnimator?.cancel()
        binding.tvTurnX.alpha = 1f
        binding.tvTurnO.alpha = 1f
        val target = when (activeTurn) {
            Piece.X -> binding.tvTurnX
            Piece.O -> binding.tvTurnO
            Piece.EMPTY, null -> null
        } ?: return

        turnBlinkAnimator = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0.25f).apply {
            duration = 550L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun showMatchResultDialog(state: GameUiState) {
        activeResultSheet?.dismiss()
        activeResultSheet = null

        val ctx = requireContext()
        val xName = state.playerXName.asString(ctx)
        val oName = state.playerOName.asString(ctx)
        val title = when {
            state.timeoutWinnerName != null -> getString(R.string.result_timeout_win, state.timeoutWinnerName)
            state.status == GameStatus.X_WON -> getString(R.string.result_x_wins, xName)
            state.status == GameStatus.O_WON -> getString(R.string.result_o_wins, oName)
            else -> getString(R.string.result_draw)
        }
        val msg = state.pointsDeltaText?.asString(ctx) ?: if (state.mode == GameMode.ONLINE) {
            getString(R.string.result_online_finished)
        } else {
            getString(R.string.result_offline_play_again_prompt)
        }

        val sheet = BottomSheetDialog(ctx)
        val sheetBinding = SheetMatchResultBinding.inflate(LayoutInflater.from(ctx))
        sheetBinding.tvResultTitle.text = title
        sheetBinding.tvResultMessage.text = msg

        sheetBinding.btnResultSecondary.visibility = View.VISIBLE
        sheetBinding.btnResultSecondary.setText(R.string.action_back_home)

        if (state.mode != GameMode.ONLINE) {
            sheetBinding.btnResultSecondary.setOnClickListener {
                sheet.dismiss()
                (activity as? MainActivity)?.exitGameScreen()
            }
            sheetBinding.btnResultPrimary.visibility = View.VISIBLE
            sheetBinding.btnResultPrimary.setText(R.string.action_continue)
            sheetBinding.btnResultPrimary.setOnClickListener {
                sheet.dismiss()
                gameViewModel.resetOfflineGame()
            }
        } else {
            sheetBinding.btnResultSecondary.setOnClickListener {
                sheet.dismiss()
                gameViewModel.cancelOnlineJobs()
                (activity as? MainActivity)?.exitGameScreen()
            }
            if (state.canRematch) {
                sheetBinding.btnResultPrimary.visibility = View.VISIBLE
                sheetBinding.btnResultPrimary.setText(R.string.action_continue)
                sheetBinding.btnResultPrimary.setOnClickListener {
                    sheet.dismiss()
                    gameViewModel.requestOnlineRematch()
                }
            } else {
                sheetBinding.btnResultPrimary.visibility = View.GONE
            }
        }
        sheet.setOnDismissListener {
            if (activeResultSheet === sheet) {
                activeResultSheet = null
            }
        }
        activeResultSheet = sheet
        sheet.setContentView(sheetBinding.root)
        sheet.show()
    }

    override fun onDestroyView() {
        activeResultSheet?.dismiss()
        activeResultSheet = null
        turnBlinkAnimator?.cancel()
        turnBlinkAnimator = null
        lastBlinkingTurn = null
        super.onDestroyView()
        _binding = null
    }
}
