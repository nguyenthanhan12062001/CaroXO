package com.game.caroxo.an

import android.app.Dialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.credentials.CredentialManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.game.caroxo.an.data.AuthManager
import com.game.caroxo.an.databinding.ActivityMainBinding
import com.game.caroxo.an.model.GameInvite
import com.game.caroxo.an.model.GameMode
import com.game.caroxo.an.model.GameStatus
import com.game.caroxo.an.model.ThemeMode
import com.game.caroxo.an.ui.ChatFragment
import com.game.caroxo.an.ui.EmailAuthSheetHelper
import com.game.caroxo.an.ui.FriendsFragment
import com.game.caroxo.an.ui.GameFragment
import com.game.caroxo.an.ui.GameInviteDialogHelper
import com.game.caroxo.an.ui.HomeFragment
import com.game.caroxo.an.ui.LeaderboardFragment
import com.game.caroxo.an.ui.OnboardingDialogHelper
import com.game.caroxo.an.ui.ProfileFragment
import com.game.caroxo.an.ui.SettingsFragment
import com.game.caroxo.an.viewmodel.GameViewModel
import com.game.caroxo.an.viewmodel.LobbyEvent
import com.game.caroxo.an.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var credentialManager: CredentialManager

    val mainViewModel: MainViewModel by viewModels {
        (application as CaroApplication).mainViewModelFactory
    }

    val gameViewModel: GameViewModel by viewModels {
        (application as CaroApplication).gameViewModelFactory
    }

    private var inGameScreen = false
    private var inFriendsScreen = false
    private val promptedInviteIds = mutableSetOf<String>()
    private var activeInviteDialog: Dialog? = null
    private var activeInviteDialogId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val activeThemeMode = (application as CaroApplication).preferencesManager.settings.value.themeMode
        if (activeThemeMode == ThemeMode.AMOLED) {
            setTheme(R.style.Theme_CaroXO_Amoled)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isNightActive = activeThemeMode == ThemeMode.AMOLED ||
            activeThemeMode == ThemeMode.DARK ||
            (activeThemeMode == ThemeMode.SYSTEM &&
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightActive
            isAppearanceLightNavigationBars = !isNightActive
        }

        credentialManager = CredentialManager.create(this)
        inGameScreen = savedInstanceState?.getBoolean(KEY_IN_GAME, false) ?: false
        inFriendsScreen = savedInstanceState?.getBoolean(KEY_IN_FRIENDS, false) ?: false
        val hideBottomNav = inGameScreen || inFriendsScreen
        binding.bottomNav.visibility = if (hideBottomNav) View.GONE else View.VISIBLE

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootMain) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomNav.updatePadding(bottom = sysBars.bottom)
            if (inGameScreen || inFriendsScreen) {
                binding.fragmentContainer.updatePadding(top = sysBars.top, bottom = sysBars.bottom)
            } else {
                binding.fragmentContainer.updatePadding(top = sysBars.top, bottom = 0)
            }
            insets
        }

        val prefs = (application as CaroApplication).preferencesManager
        (application as CaroApplication).adManager.initConsentAndAds(this)
        if (savedInstanceState == null) {
            showTabFragment(HomeFragment())
            AuthManager.attemptAutoSignIn(
                context = this,
                credentialManager = credentialManager,
                scope = lifecycleScope,
                onAuthSuccess = { },
                onUnauthenticated = { }
            )
            if (!prefs.isOnboardingCompleted()) {
                binding.root.post {
                    OnboardingDialogHelper.show(this, prefs) {
                        Toast.makeText(this, R.string.discovery_toast_started, Toast.LENGTH_SHORT).show()
                        val currentFrag = supportFragmentManager.findFragmentById(R.id.fragment_container)
                        if (currentFrag is HomeFragment) {
                            currentFrag.checkAndShowFeatureDiscovery()
                        }
                    }
                }
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val app = application as CaroApplication
            app.soundManager.playUiClick()
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_chat -> ChatFragment()
                R.id.nav_leaderboard -> LeaderboardFragment()
                R.id.nav_profile -> ProfileFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> HomeFragment()
            }
            showTabFragment(fragment)
            true
        }

        observeSocialAndLobbyEvents()
    }

    private fun observeSocialAndLobbyEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainViewModel.events.collect { event ->
                        when (event) {
                            is LobbyEvent.OpenOnlineRoom -> {
                                openOnlineGame(event.roomCode)
                            }
                            is LobbyEvent.ShowMessage -> {
                                Toast.makeText(
                                    this@MainActivity,
                                    event.message.asString(this@MainActivity),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                launch {
                    mainViewModel.incomingGameInvites.collect { invites ->
                        val activeId = activeInviteDialogId
                        if (activeId != null && invites.none { it.id == activeId }) {
                            activeInviteDialog?.dismiss()
                            activeInviteDialog = null
                            activeInviteDialogId = null
                        }
                        maybeShowPendingInvite(invites)
                    }
                }
                launch {
                    var lastStatus: GameStatus? = null
                    gameViewModel.uiState.collect { gState ->
                        if (gState.status != lastStatus) {
                            lastStatus = gState.status
                            if (gState.status != GameStatus.PLAYING) {
                                maybeShowPendingInvite()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun maybeShowPendingInvite(invites: List<GameInvite> = mainViewModel.incomingGameInvites.value) {
        if (isFinishing || isDestroyed) return
        if (activeInviteDialog?.isShowing == true) return
        val currentUid = mainViewModel.currentUser.value?.uid
        val gState = gameViewModel.uiState.value
        val nextInvite = invites.firstOrNull { invite ->
            if (invite.id.isBlank() || invite.id in promptedInviteIds) return@firstOrNull false
            if (!inGameScreen) return@firstOrNull true
            when (gState.status) {
                GameStatus.PLAYING -> false
                GameStatus.WAITING ->
                    gameViewModel.isRematchFrom(invite.senderUid) &&
                        invite.roomCode != gState.onlineRoomCode &&
                        invite.senderUid != currentUid
                GameStatus.X_WON, GameStatus.O_WON, GameStatus.DRAW -> true
            }
        } ?: return
        showIncomingInviteDialog(nextInvite)
    }

    private fun showIncomingInviteDialog(invite: GameInvite) {
        promptedInviteIds.add(invite.id)
        activeInviteDialogId = invite.id
        val isRematch = gameViewModel.isRematchFrom(invite.senderUid)
        val sound = (application as CaroApplication).soundManager
        val dialog = GameInviteDialogHelper.show(
            context = this,
            invite = invite,
            isRematch = isRematch,
            onAccept = {
                sound.playUiClick()
                gameViewModel.cancelPendingWaitingRoomIfNeeded()
                mainViewModel.acceptGameInvite(invite)
            },
            onDecline = {
                sound.playUiClick()
                gameViewModel.clearRematchContext(invite.senderUid)
                mainViewModel.declineGameInvite(invite)
            },
            onDismiss = {
                if (activeInviteDialogId == invite.id) {
                    activeInviteDialog = null
                    activeInviteDialogId = null
                }
            }
        )
        activeInviteDialog = dialog
    }

    override fun onResume() {
        super.onResume()
        (application as CaroApplication).soundManager.syncAmbientMusic(isResumed = true)
        mainViewModel.setAppForeground(true)
    }

    override fun onPause() {
        super.onPause()
        (application as CaroApplication).soundManager.syncAmbientMusic(isResumed = false)
        mainViewModel.setAppForeground(false)
    }

    fun triggerGoogleSignIn() {
        AuthManager.signInWithGoogle(
            activity = this,
            credentialManager = credentialManager,
            scope = lifecycleScope,
            onAuthSuccess = {
                Toast.makeText(
                    this,
                    getString(R.string.auth_signed_in_as, it.displayName ?: getString(R.string.default_player_name)),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onAuthCancelled = {
                Toast.makeText(this, getString(R.string.action_cancel), Toast.LENGTH_SHORT).show()
            },
            onAuthError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        )
    }

    fun openEmailAuthSheet(initialRegisterMode: Boolean = false) {
        val defaultNick = mainViewModel.myProfile.value?.nickname
            ?: (application as CaroApplication).preferencesManager.settings.value.localNickname
        EmailAuthSheetHelper.show(
            activity = this,
            defaultNickname = defaultNick,
            initialRegisterMode = initialRegisterMode
        )
    }

    fun triggerSignOut() {
        lifecycleScope.launch {
            mainViewModel.markOfflineBeforeSignOut()
            mainViewModel.stopAuthenticatedListeners()
            AuthManager.signOut(
                credentialManager = credentialManager,
                scope = lifecycleScope
            )
        }
    }

    fun retriggerOnboardingTour() {
        val prefs = (application as CaroApplication).preferencesManager
        OnboardingDialogHelper.show(this, prefs) {
            val currentFrag = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (currentFrag is HomeFragment) {
                currentFrag.checkAndShowFeatureDiscovery()
            }
        }
    }

    fun promptSignInForOnline() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.auth_required_title)
            .setMessage(R.string.auth_required_desc)
            .setPositiveButton(R.string.sign_in_with_google) { _, _ ->
                triggerGoogleSignIn()
            }
            .setNeutralButton(R.string.sign_in_with_email) { _, _ ->
                openEmailAuthSheet()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun openOfflineGame(
        mode: GameMode,
        boardSize: Int,
        blockTwoEnds: Boolean,
        aiDifficultyLabel: String
    ) {
        gameViewModel.startOfflineGame(mode, boardSize, blockTwoEnds, aiDifficultyLabel)
        enterGameScreen()
    }

    fun openOnlineGame(roomCode: String) {
        gameViewModel.startOnlineGame(roomCode)
        enterGameScreen()
    }

    fun openFriendsScreen(initialTab: Int = 0) {
        inGameScreen = false
        inFriendsScreen = true
        binding.bottomNav.visibility = View.GONE
        ViewCompat.requestApplyInsets(binding.rootMain)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.frag_enter, R.anim.frag_exit)
            .replace(R.id.fragment_container, FriendsFragment.newInstance(initialTab))
            .commit()
    }

    fun exitFriendsScreen() {
        inFriendsScreen = false
        exitToCurrentTab()
    }

    private fun enterGameScreen() {
        inGameScreen = true
        inFriendsScreen = false
        activeInviteDialog?.dismiss()
        activeInviteDialog = null
        activeInviteDialogId = null
        binding.bottomNav.visibility = View.GONE
        ViewCompat.requestApplyInsets(binding.rootMain)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.frag_enter, R.anim.frag_exit)
            .replace(R.id.fragment_container, GameFragment())
            .commit()
    }

    fun exitGameScreen(showExitInterstitial: Boolean = true) {
        val app = application as CaroApplication
        val isAdmin = com.game.caroxo.an.model.AdminConfig.isAdmin(mainViewModel.currentUser.value?.uid)
        if (showExitInterstitial && !isAdmin) {
            app.adManager.maybeShowInterstitialAfterMatchExit(this, false) {
                performExitGame()
            }
        } else {
            performExitGame()
        }
    }

    private fun performExitGame() {
        inGameScreen = false
        inFriendsScreen = false
        exitToCurrentTab()
        binding.root.post { maybeShowPendingInvite() }
    }

    private fun exitToCurrentTab() {
        binding.bottomNav.visibility = View.VISIBLE
        ViewCompat.requestApplyInsets(binding.rootMain)
        val tabFragment: Fragment = when (binding.bottomNav.selectedItemId) {
            R.id.nav_chat -> ChatFragment()
            R.id.nav_leaderboard -> LeaderboardFragment()
            R.id.nav_profile -> ProfileFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        showTabFragment(tabFragment)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IN_GAME, inGameScreen)
        outState.putBoolean(KEY_IN_FRIENDS, inFriendsScreen)
    }

    private fun showTabFragment(fragment: Fragment) {
        inGameScreen = false
        inFriendsScreen = false
        binding.bottomNav.visibility = View.VISIBLE
        ViewCompat.requestApplyInsets(binding.rootMain)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.frag_enter, R.anim.frag_exit)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    companion object {
        private const val KEY_IN_GAME = "in_game_screen"
        private const val KEY_IN_FRIENDS = "in_friends_screen"
    }
}
