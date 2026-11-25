package com.game.caroxo.an.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.databinding.SheetPlayerProfileBinding
import com.game.caroxo.an.model.AdminConfig
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.Friendship
import com.game.caroxo.an.model.LeaderboardEntry
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.RankTier
import com.game.caroxo.an.viewmodel.MainViewModel
import com.game.caroxo.an.viewmodel.UiState
import com.game.caroxo.an.viewmodel.ViewedPlayerProfile
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

object PlayerProfileSheetHelper {

    fun show(
        fragment: Fragment,
        mainViewModel: MainViewModel,
        preferencesManager: PreferencesManager,
        targetUid: String,
        fallbackNickname: String? = null,
        fallbackAvatarId: String? = null
    ) {
        val ctx = fragment.context ?: return
        if (targetUid.isBlank() || targetUid == mainViewModel.currentUser.value?.uid) return

        val dialog = BottomSheetDialog(ctx, R.style.ThemeOverlay_CaroXO_BottomSheetDialog)
        val binding = SheetPlayerProfileBinding.inflate(LayoutInflater.from(ctx))
        dialog.setContentView(binding.root)

        mainViewModel.loadPlayerProfile(targetUid, fallbackNickname, fallbackAvatarId)

        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainViewModel.viewedPlayerProfileState.collect { state ->
                        renderProfileState(
                            ctx = ctx,
                            binding = binding,
                            state = state,
                            friendship = mainViewModel.friendshipWith(targetUid),
                            myUid = mainViewModel.currentUser.value?.uid.orEmpty(),
                            isBusy = mainViewModel.isBusy.value,
                            dialog = dialog,
                            mainViewModel = mainViewModel,
                            preferencesManager = preferencesManager
                        )
                    }
                }
                launch {
                    mainViewModel.friendshipsState.collect {
                        renderProfileState(
                            ctx = ctx,
                            binding = binding,
                            state = mainViewModel.viewedPlayerProfileState.value,
                            friendship = mainViewModel.friendshipWith(targetUid),
                            myUid = mainViewModel.currentUser.value?.uid.orEmpty(),
                            isBusy = mainViewModel.isBusy.value,
                            dialog = dialog,
                            mainViewModel = mainViewModel,
                            preferencesManager = preferencesManager
                        )
                    }
                }
                launch {
                    mainViewModel.isBusy.collect { busy ->
                        binding.btnSheetPrimaryAction.isEnabled = !busy
                        binding.btnSheetSecondaryAction.isEnabled = !busy
                    }
                }
            }
        }

        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }

    private fun renderProfileState(
        ctx: Context,
        binding: SheetPlayerProfileBinding,
        state: UiState<ViewedPlayerProfile>,
        friendship: Friendship?,
        myUid: String,
        isBusy: Boolean,
        dialog: BottomSheetDialog,
        mainViewModel: MainViewModel,
        preferencesManager: PreferencesManager
    ) {
        when (state) {
            is UiState.Idle, is UiState.Loading -> {
                binding.progressPlayerProfile.visibility = View.VISIBLE
                binding.tvPlayerProfileError.visibility = View.GONE
                binding.layoutPlayerProfileContent.visibility = View.GONE
            }
            is UiState.Error -> {
                binding.progressPlayerProfile.visibility = View.GONE
                binding.layoutPlayerProfileContent.visibility = View.GONE
                binding.tvPlayerProfileError.visibility = View.VISIBLE
                binding.tvPlayerProfileError.text = state.message.asString(ctx)
            }
            is UiState.Success -> {
                binding.progressPlayerProfile.visibility = View.GONE
                binding.tvPlayerProfileError.visibility = View.GONE
                binding.layoutPlayerProfileContent.visibility = View.VISIBLE
                bindProfileData(
                    ctx = ctx,
                    binding = binding,
                    data = state.data,
                    friendship = friendship,
                    myUid = myUid,
                    isBusy = isBusy,
                    dialog = dialog,
                    mainViewModel = mainViewModel,
                    preferencesManager = preferencesManager
                )
            }
        }
    }

    private fun bindProfileData(
        ctx: Context,
        binding: SheetPlayerProfileBinding,
        data: ViewedPlayerProfile,
        friendship: Friendship?,
        myUid: String,
        isBusy: Boolean,
        dialog: BottomSheetDialog,
        mainViewModel: MainViewModel,
        preferencesManager: PreferencesManager
    ) {
        val entry: LeaderboardEntry = data.entry
        val rank = data.rank
        val isAdmin = AdminConfig.isAdmin(entry.userId)

        binding.ivSheetAvatar.setImageResource(AvatarCatalog.resolveDrawable(entry.avatarId))
        binding.tvSheetNickname.text = entry.nickname
        binding.ivSheetVerified.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.layoutSheetAdminBadge.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.tvSheetPlayerId.text = ctx.getString(R.string.player_profile_id_fmt, entry.userId)

        binding.btnSheetCopyId.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PlayerID", entry.userId))
            Toast.makeText(ctx, ctx.getString(R.string.friends_id_copied, entry.userId), Toast.LENGTH_SHORT).show()
        }

        val tier = RankTier.resolve(entry.points, rank)
        binding.tvSheetTierBadge.setText(RankUiHelper.resolveTitleRes(tier))
        binding.tvSheetTierBadge.setTextColor(
            RankUiHelper.resolveTierColor(ctx, tier, binding.tvSheetTierBadge)
        )

        if (rank != null && rank in 1..3) {
            binding.ivSheetCrown.visibility = View.VISIBLE
            val rankColor = when (rank) {
                1 -> ContextCompat.getColor(ctx, R.color.caroxo_rank_gold)
                2 -> ContextCompat.getColor(ctx, R.color.caroxo_rank_silver)
                else -> ContextCompat.getColor(ctx, R.color.caroxo_rank_bronze)
            }
            binding.ivSheetCrown.setColorFilter(rankColor)
        } else {
            binding.ivSheetCrown.visibility = View.GONE
        }

        if (rank != null && rank > 0) {
            binding.tvSheetRankPill.visibility = View.VISIBLE
            binding.tvSheetRankPill.text = ctx.getString(R.string.player_profile_rank_fmt, rank)
        } else {
            binding.tvSheetRankPill.visibility = View.GONE
        }

        binding.tvSheetWins.text = entry.wins.toString()
        binding.tvSheetLosses.text = entry.losses.toString()
        binding.tvSheetPoints.text = entry.points.toString()

        val totalGames = entry.wins + entry.losses + entry.draws
        val winRate = if (totalGames > 0L) ((entry.wins * 100L) / totalGames).toInt() else 0
        binding.tvSheetWinrate.text = "$winRate%"

        val style = PieceStyle.fromKey(entry.pieceStyle)
        binding.previewSheetPieceStyle.pieceStyle = style
        binding.tvSheetPieceStyleName.setText(RankUiHelper.resolvePieceStyleLabelRes(style))

        binding.btnSheetPrimaryAction.isEnabled = !isBusy
        binding.btnSheetSecondaryAction.isEnabled = !isBusy

        when {
            friendship?.status == "ACCEPTED" -> {
                binding.btnSheetPrimaryAction.setText(R.string.btn_play_with_friends)
                binding.btnSheetPrimaryAction.setIconResource(R.drawable.ic_globe)
                binding.btnSheetPrimaryAction.setOnClickListener {
                    val settings = preferencesManager.settings.value
                    dialog.dismiss()
                    mainViewModel.inviteFriendToPlay(
                        friendUid = entry.userId,
                        friendNickname = entry.nickname,
                        boardSize = settings.defaultBoardSize,
                        blockTwoEnds = settings.defaultBlockTwoEnds
                    )
                }

                binding.btnSheetSecondaryAction.visibility = View.VISIBLE
                binding.btnSheetSecondaryAction.setText(R.string.action_remove_friend)
                binding.btnSheetSecondaryAction.setIconResource(R.drawable.ic_close)
                binding.btnSheetSecondaryAction.setOnClickListener {
                    MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_CaroXO_AlertDialog)
                        .setTitle(R.string.confirm_remove_friend_title)
                        .setMessage(ctx.getString(R.string.confirm_remove_friend_message, entry.nickname))
                        .setPositiveButton(R.string.action_confirm) { _, _ ->
                            mainViewModel.removeOrDeclineFriendship(friendship.id, R.string.friend_removed_toast)
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            }
            friendship?.status == "PENDING" && friendship.recipientUid == myUid -> {
                binding.btnSheetPrimaryAction.setText(R.string.action_accept)
                binding.btnSheetPrimaryAction.setIconResource(R.drawable.ic_check)
                binding.btnSheetPrimaryAction.setOnClickListener {
                    mainViewModel.acceptFriendRequest(friendship.id)
                }

                binding.btnSheetSecondaryAction.visibility = View.VISIBLE
                binding.btnSheetSecondaryAction.setText(R.string.action_decline)
                binding.btnSheetSecondaryAction.setIconResource(R.drawable.ic_close)
                binding.btnSheetSecondaryAction.setOnClickListener {
                    mainViewModel.removeOrDeclineFriendship(friendship.id, R.string.friend_request_declined_toast)
                }
            }
            friendship?.status == "PENDING" && friendship.requesterUid == myUid -> {
                binding.btnSheetPrimaryAction.setText(R.string.action_cancel_request)
                binding.btnSheetPrimaryAction.setIconResource(R.drawable.ic_close)
                binding.btnSheetPrimaryAction.setOnClickListener {
                    mainViewModel.removeOrDeclineFriendship(friendship.id, R.string.friend_request_cancelled_toast)
                }
                binding.btnSheetSecondaryAction.visibility = View.GONE
            }
            else -> {
                binding.btnSheetPrimaryAction.setText(R.string.action_add_friend)
                binding.btnSheetPrimaryAction.setIconResource(R.drawable.ic_person_add)
                binding.btnSheetPrimaryAction.setOnClickListener {
                    mainViewModel.sendFriendRequest(entry)
                }
                binding.btnSheetSecondaryAction.visibility = View.GONE
            }
        }
    }
}
