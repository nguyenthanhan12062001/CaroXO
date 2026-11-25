package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.game.caroxo.an.CaroApplication
import com.game.caroxo.an.R
import com.game.caroxo.an.data.AuthManager
import com.game.caroxo.an.databinding.SheetEmailAuthBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object EmailAuthSheetHelper {

    fun show(
        activity: FragmentActivity,
        defaultNickname: String = "",
        initialRegisterMode: Boolean = false
    ) {
        val sheet = BottomSheetDialog(activity)
        val binding = SheetEmailAuthBinding.inflate(LayoutInflater.from(activity))
        val app = activity.application as CaroApplication
        val sound = app.soundManager

        var isRegisterMode = initialRegisterMode
        if (defaultNickname.isNotBlank()) {
            binding.etAuthNickname.setText(defaultNickname.take(24))
        }

        fun clearError() {
            binding.tvAuthError.visibility = View.GONE
            binding.tvAuthError.text = ""
        }

        fun showError(message: String) {
            binding.tvAuthError.text = message
            binding.tvAuthError.visibility = View.VISIBLE
        }

        fun setLoading(loading: Boolean) {
            binding.progressEmailAuth.visibility = if (loading) View.VISIBLE else View.GONE
            binding.btnEmailSubmit.visibility = if (loading) View.INVISIBLE else View.VISIBLE
            binding.btnEmailSubmit.isEnabled = !loading
            binding.btnEmailAuthCancel.isEnabled = !loading
            binding.btnForgotPassword.isEnabled = !loading
            binding.btnTabSignIn.isEnabled = !loading
            binding.btnTabSignUp.isEnabled = !loading
            binding.etAuthEmail.isEnabled = !loading
            binding.etAuthPassword.isEnabled = !loading
            binding.etAuthConfirmPassword.isEnabled = !loading
            binding.etAuthNickname.isEnabled = !loading
        }

        fun applyMode(registerMode: Boolean) {
            isRegisterMode = registerMode
            clearError()
            binding.tilAuthNickname.visibility = if (registerMode) View.VISIBLE else View.GONE
            binding.tilAuthConfirmPassword.visibility = if (registerMode) View.VISIBLE else View.GONE
            binding.btnForgotPassword.visibility = if (registerMode) View.GONE else View.VISIBLE
            binding.tvEmailAuthTitle.setText(
                if (registerMode) R.string.email_auth_title_sign_up else R.string.email_auth_title_sign_in
            )
            binding.btnEmailSubmit.setText(
                if (registerMode) R.string.btn_email_submit_sign_up else R.string.btn_email_submit_sign_in
            )
        }

        binding.toggleAuthMode.check(
            if (initialRegisterMode) R.id.btn_tab_sign_up else R.id.btn_tab_sign_in
        )
        applyMode(initialRegisterMode)

        binding.toggleAuthMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sound.playUiClick()
            applyMode(checkedId == R.id.btn_tab_sign_up)
        }

        fun submitForm() {
            sound.playUiClick()
            clearError()
            val email = binding.etAuthEmail.text?.toString().orEmpty()
            val password = binding.etAuthPassword.text?.toString().orEmpty()

            if (isRegisterMode) {
                val confirm = binding.etAuthConfirmPassword.text?.toString().orEmpty()
                val nickname = binding.etAuthNickname.text?.toString().orEmpty()
                setLoading(true)
                AuthManager.registerWithEmail(
                    context = activity,
                    email = email,
                    password = password,
                    confirmPassword = confirm,
                    nickname = nickname,
                    scope = activity.lifecycleScope,
                    onAuthSuccess = { user ->
                        if (activity.isFinishing || activity.isDestroyed) return@registerWithEmail
                        val currentSettings = app.preferencesManager.settings.value
                        val resolvedNick = nickname.trim().take(24).ifBlank {
                            user.displayName ?: currentSettings.localNickname
                        }
                        app.preferencesManager.setLocalProfile(
                            nickname = resolvedNick,
                            avatarId = currentSettings.localAvatarId,
                            pieceStyle = currentSettings.pieceStyle
                        )
                        setLoading(false)
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.auth_account_created, resolvedNick),
                            Toast.LENGTH_SHORT
                        ).show()
                        sheet.dismiss()
                    },
                    onAuthError = { msg ->
                        if (activity.isFinishing || activity.isDestroyed) return@registerWithEmail
                        setLoading(false)
                        showError(msg)
                    }
                )
            } else {
                setLoading(true)
                AuthManager.signInWithEmail(
                    context = activity,
                    email = email,
                    password = password,
                    scope = activity.lifecycleScope,
                    onAuthSuccess = { user ->
                        if (activity.isFinishing || activity.isDestroyed) return@signInWithEmail
                        setLoading(false)
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.auth_signed_in_as,
                                user.displayName ?: user.email ?: activity.getString(R.string.default_player_name)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        sheet.dismiss()
                    },
                    onAuthError = { msg ->
                        if (activity.isFinishing || activity.isDestroyed) return@signInWithEmail
                        setLoading(false)
                        showError(msg)
                    }
                )
            }
        }

        binding.btnEmailSubmit.setOnClickListener { submitForm() }

        binding.etAuthPassword.setOnEditorActionListener { _, actionId, _ ->
            if (!isRegisterMode && actionId == EditorInfo.IME_ACTION_DONE) {
                submitForm()
                true
            } else false
        }

        binding.etAuthConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (isRegisterMode && actionId == EditorInfo.IME_ACTION_DONE) {
                submitForm()
                true
            } else false
        }

        binding.btnForgotPassword.setOnClickListener {
            sound.playUiClick()
            clearError()
            val email = binding.etAuthEmail.text?.toString().orEmpty()
            setLoading(true)
            AuthManager.sendPasswordReset(
                context = activity,
                email = email,
                scope = activity.lifecycleScope,
                onSuccess = {
                    if (activity.isFinishing || activity.isDestroyed) return@sendPasswordReset
                    setLoading(false)
                    Toast.makeText(activity, activity.getString(R.string.auth_reset_sent), Toast.LENGTH_LONG).show()
                },
                onError = { msg ->
                    if (activity.isFinishing || activity.isDestroyed) return@sendPasswordReset
                    setLoading(false)
                    showError(msg)
                }
            )
        }

        binding.btnEmailAuthCancel.setOnClickListener {
            sound.playUiClick()
            sheet.dismiss()
        }

        sheet.setContentView(binding.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }
}
