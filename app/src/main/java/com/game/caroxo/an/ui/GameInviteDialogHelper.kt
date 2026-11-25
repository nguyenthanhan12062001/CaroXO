package com.game.caroxo.an.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.game.caroxo.an.R
import com.game.caroxo.an.databinding.DialogGameInvitePopupBinding
import com.game.caroxo.an.model.AdminConfig
import com.game.caroxo.an.model.AvatarCatalog
import com.game.caroxo.an.model.GameInvite
import com.google.android.material.color.MaterialColors

object GameInviteDialogHelper {

    fun show(
        context: Context,
        invite: GameInvite,
        isRematch: Boolean,
        onAccept: () -> Unit,
        onDecline: () -> Unit,
        onDismiss: () -> Unit = {}
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val binding = DialogGameInvitePopupBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(980),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(true)

        val primaryColor = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorPrimary
        )
        val rematchAccent = ContextCompat.getColor(context, R.color.caroxo_tertiary)

        if (isRematch) {
            binding.cardInvitePopup.strokeColor = rematchAccent
            binding.ivPopupBadgeIcon.setImageResource(R.drawable.ic_reset)
            binding.ivPopupBadgeIcon.setColorFilter(rematchAccent)
            binding.tvPopupBadge.setText(R.string.invite_popup_badge_rematch)
            binding.tvPopupBadge.setTextColor(rematchAccent)
            binding.tvPopupHeadline.text = context.getString(
                R.string.invite_popup_headline_rematch,
                invite.senderNickname
            )
        } else {
            binding.cardInvitePopup.strokeColor = primaryColor
            binding.ivPopupBadgeIcon.setImageResource(R.drawable.ic_nav_play)
            binding.ivPopupBadgeIcon.setColorFilter(primaryColor)
            binding.tvPopupBadge.setText(R.string.invite_popup_badge_match)
            binding.tvPopupBadge.setTextColor(primaryColor)
            binding.tvPopupHeadline.text = context.getString(
                R.string.invite_popup_headline_match,
                invite.senderNickname
            )
        }

        binding.ivPopupAvatar.setImageResource(AvatarCatalog.resolveDrawable(invite.senderAvatarId))
        binding.layoutPopupAdmin.visibility =
            if (AdminConfig.isAdmin(invite.senderUid)) View.VISIBLE else View.GONE

        val ruleLabel = context.getString(
            if (invite.blockTwoEnds) R.string.invite_rule_block_on else R.string.invite_rule_block_off
        )
        binding.tvPopupDetails.text = context.getString(
            R.string.invite_card_desc_fmt,
            invite.roomCode,
            invite.boardSize,
            ruleLabel
        )

        var handledAction = false
        binding.btnPopupAccept.setOnClickListener {
            if (handledAction) return@setOnClickListener
            handledAction = true
            dialog.dismiss()
            onAccept()
        }

        binding.btnPopupDecline.setOnClickListener {
            if (handledAction) return@setOnClickListener
            handledAction = true
            dialog.dismiss()
            onDecline()
        }

        dialog.setOnDismissListener {
            onDismiss()
        }

        binding.cardInvitePopup.alpha = 0f
        binding.cardInvitePopup.scaleX = 0.90f
        binding.cardInvitePopup.scaleY = 0.90f
        dialog.show()
        binding.cardInvitePopup.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        return dialog
    }
}
