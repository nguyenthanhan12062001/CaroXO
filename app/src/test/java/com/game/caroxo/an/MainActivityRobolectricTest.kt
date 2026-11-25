package com.game.caroxo.an

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.game.caroxo.an.model.AppLanguage
import com.game.caroxo.an.model.ChatMessage
import com.game.caroxo.an.model.LeaderboardEntry
import com.game.caroxo.an.model.PieceStyle
import com.game.caroxo.an.model.RankTier
import com.game.caroxo.an.model.ThemeMode
import com.game.caroxo.an.ui.ChatAdapter
import com.game.caroxo.an.ui.ChatFragment
import com.game.caroxo.an.ui.GameFragment
import com.game.caroxo.an.ui.HomeFragment
import com.game.caroxo.an.ui.LeaderboardAdapter
import com.game.caroxo.an.ui.LeaderboardFragment
import com.game.caroxo.an.ui.PieceStyleAdapter
import com.game.caroxo.an.ui.ProfileFragment
import com.game.caroxo.an.ui.SettingsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityRobolectricTest {

    @Before
    fun setUpFirebase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            val initialized = FirebaseApp.initializeApp(context)
            if (initialized == null) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId("1:1234567890:android:abcdef123456")
                        .setApiKey("test-api-key")
                        .setProjectId("test-project-id")
                        .build()
                )
            }
        }
    }

    @Test
    fun `launch MainActivity and switch all tabs and start offline game without ViewModel crash`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Shadows.shadowOf(activity.mainLooper).idle()
                assertTrue(
                    activity.supportFragmentManager.findFragmentById(R.id.fragment_container) is HomeFragment
                )
                assertNotNull(activity.mainViewModel)
                assertNotNull(activity.gameViewModel)

                val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottom_nav)
                bottomNav.selectedItemId = R.id.nav_chat
                Shadows.shadowOf(activity.mainLooper).idle()
                assertTrue(
                    activity.supportFragmentManager.findFragmentById(R.id.fragment_container) is ChatFragment
                )
                assertNotNull(activity.findViewById<View>(R.id.btn_chat_email_sign_in))

                bottomNav.selectedItemId = R.id.nav_leaderboard
                Shadows.shadowOf(activity.mainLooper).idle()
                assertTrue(
                    activity.supportFragmentManager.findFragmentById(R.id.fragment_container) is LeaderboardFragment
                )

                bottomNav.selectedItemId = R.id.nav_profile
                Shadows.shadowOf(activity.mainLooper).idle()
                assertTrue(
                    activity.supportFragmentManager.findFragmentById(R.id.fragment_container) is ProfileFragment
                )

                bottomNav.selectedItemId = R.id.nav_settings
                Shadows.shadowOf(activity.mainLooper).idle()
                assertTrue(
                    activity.supportFragmentManager.findFragmentById(R.id.fragment_container) is SettingsFragment
                )

                bottomNav.selectedItemId = R.id.nav_play
                Shadows.shadowOf(activity.mainLooper).idle()
                assertTrue(
                    activity.supportFragmentManager.findFragmentById(R.id.fragment_container) is HomeFragment
                )

                activity.findViewById<View>(R.id.btn_ai_medium).performClick()
                Shadows.shadowOf(activity.mainLooper).idle()
                assertTrue(
                    activity.supportFragmentManager.findFragmentById(R.id.fragment_container) is GameFragment
                )
                assertEquals(View.GONE, bottomNav.visibility)
            }
        }
    }

    @Test
    fun `theme modes switch cleanly across Light, Dark, and true-black AMOLED`() {
        val app = ApplicationProvider.getApplicationContext<CaroApplication>()
        app.preferencesManager.setThemeMode(ThemeMode.AMOLED)
        assertEquals(ThemeMode.AMOLED, app.preferencesManager.settings.value.themeMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Shadows.shadowOf(activity.mainLooper).idle()
                val rootBg = MaterialColors.getColor(
                    activity.findViewById(R.id.root_main),
                    android.R.attr.colorBackground
                )
                assertEquals(Color.BLACK, rootBg)
            }
        }

        app.preferencesManager.setThemeMode(ThemeMode.LIGHT)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
        app.preferencesManager.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode())
    }

    @Test
    fun `first launch defaults to system language and all locales resolve cleanly`() {
        val app = ApplicationProvider.getApplicationContext<CaroApplication>()
        assertEquals(AppLanguage.SYSTEM, app.preferencesManager.settings.value.languageMode)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)

        val expectedByTag = mapOf(
            "vi" to "Ngôn ngữ",
            "en" to "Language",
            "zh-CN" to "语言",
            "in" to "Bahasa",
            "ko" to "언어",
            "ja" to "言語"
        )
        for ((tag, expectedTitle) in expectedByTag) {
            val locale = Locale.forLanguageTag(tag)
            val conf = android.content.res.Configuration(app.resources.configuration).apply {
                setLocale(locale)
            }
            val localizedCtx = app.createConfigurationContext(conf)
            assertEquals("Locale $tag mismatch", expectedTitle, localizedCtx.getString(R.string.settings_language))
            assertTrue(localizedCtx.getString(R.string.theme_amoled).isNotBlank())
        }
    }

    @Test
    fun `email auth sheet opens from Home, switches to register mode, and validates inputs`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Shadows.shadowOf(activity.mainLooper).idle()
                activity.findViewById<View>(R.id.btn_email_sign_in_home).performClick()
                Shadows.shadowOf(activity.mainLooper).idle()

                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                assertNotNull(dialog)
                assertTrue(dialog.isShowing)

                val tilConfirm = dialog.findViewById<View>(R.id.til_auth_confirm_password)
                val tilNick = dialog.findViewById<View>(R.id.til_auth_nickname)
                assertEquals(View.GONE, tilConfirm.visibility)
                assertEquals(View.GONE, tilNick.visibility)

                dialog.findViewById<View>(R.id.btn_tab_sign_up).performClick()
                Shadows.shadowOf(activity.mainLooper).idle()
                assertEquals(View.VISIBLE, tilConfirm.visibility)
                assertEquals(View.VISIBLE, tilNick.visibility)

                dialog.findViewById<TextInputEditText>(R.id.et_auth_nickname).setText("Tester")
                dialog.findViewById<TextInputEditText>(R.id.et_auth_email).setText("invalid-email")
                dialog.findViewById<TextInputEditText>(R.id.et_auth_password).setText("123456")
                dialog.findViewById<TextInputEditText>(R.id.et_auth_confirm_password).setText("123456")
                dialog.findViewById<View>(R.id.btn_email_submit).performClick()
                Shadows.shadowOf(activity.mainLooper).idle()

                val tvError = dialog.findViewById<TextView>(R.id.tv_auth_error)
                assertEquals(View.VISIBLE, tvError.visibility)
                assertFalse(tvError.text.isNullOrBlank())
                dialog.dismiss()
            }
        }
    }

    @Test
    fun `chat adapter renders incoming and outgoing bubbles accurately`() {
        val context = ApplicationProvider.getApplicationContext<CaroApplication>()
        context.setTheme(R.style.Theme_CaroXO)
        val adapter = ChatAdapter("uid_me")
        val messages = listOf(
            ChatMessage(
                id = "m1",
                senderUid = "uid_me",
                senderNickname = "Me",
                senderAvatarId = "ninja",
                text = "Hello CaroXO!"
            ),
            ChatMessage(
                id = "m2",
                senderUid = "uid_other",
                senderNickname = "Opponent",
                senderAvatarId = "robot",
                text = "Good game!"
            )
        )
        adapter.submitList(messages)
        val parent = FrameLayout(context)

        val mineHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(mineHolder, 0)
        assertEquals(View.VISIBLE, mineHolder.itemView.findViewById<View>(R.id.layout_outgoing_msg).visibility)
        assertEquals(View.GONE, mineHolder.itemView.findViewById<View>(R.id.layout_incoming_msg).visibility)
        assertEquals("Hello CaroXO!", mineHolder.itemView.findViewById<TextView>(R.id.tv_chat_text_mine).text.toString())

        val otherHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(otherHolder, 1)
        assertEquals(View.GONE, otherHolder.itemView.findViewById<View>(R.id.layout_outgoing_msg).visibility)
        assertEquals(View.VISIBLE, otherHolder.itemView.findViewById<View>(R.id.layout_incoming_msg).visibility)
        assertEquals("Opponent", otherHolder.itemView.findViewById<TextView>(R.id.tv_chat_sender_other).text.toString())
        assertEquals("Good game!", otherHolder.itemView.findViewById<TextView>(R.id.tv_chat_text_other).text.toString())
    }

    @Test
    fun `vip piece styles lock when signed out or outside top 3 and unlock by rank`() {
        // Signed out -> all 3 VIP styles locked even if rank passed
        assertFalse(PieceStyle.VIP_CRIMSON_EMBER.isUnlockedFor(isSignedIn = false, currentRank = 1))
        assertFalse(PieceStyle.VIP_PLASMA_VORTEX.isUnlockedFor(isSignedIn = false, currentRank = 1))
        assertFalse(PieceStyle.VIP_CELESTIAL_MONARCH.isUnlockedFor(isSignedIn = false, currentRank = 1))

        // Signed in, Rank #4 -> all 3 VIP styles locked
        assertFalse(PieceStyle.VIP_CRIMSON_EMBER.isUnlockedFor(isSignedIn = true, currentRank = 4))
        assertFalse(PieceStyle.VIP_PLASMA_VORTEX.isUnlockedFor(isSignedIn = true, currentRank = 4))
        assertFalse(PieceStyle.VIP_CELESTIAL_MONARCH.isUnlockedFor(isSignedIn = true, currentRank = 4))

        // Rank #3 unlocks Crimson Ember only
        assertTrue(PieceStyle.VIP_CRIMSON_EMBER.isUnlockedFor(isSignedIn = true, currentRank = 3))
        assertFalse(PieceStyle.VIP_PLASMA_VORTEX.isUnlockedFor(isSignedIn = true, currentRank = 3))
        assertFalse(PieceStyle.VIP_CELESTIAL_MONARCH.isUnlockedFor(isSignedIn = true, currentRank = 3))

        // Rank #2 unlocks Crimson Ember & Plasma Vortex
        assertTrue(PieceStyle.VIP_CRIMSON_EMBER.isUnlockedFor(isSignedIn = true, currentRank = 2))
        assertTrue(PieceStyle.VIP_PLASMA_VORTEX.isUnlockedFor(isSignedIn = true, currentRank = 2))
        assertFalse(PieceStyle.VIP_CELESTIAL_MONARCH.isUnlockedFor(isSignedIn = true, currentRank = 2))

        // Rank #1 unlocks all 3 VIP styles
        assertTrue(PieceStyle.VIP_CRIMSON_EMBER.isUnlockedFor(isSignedIn = true, currentRank = 1))
        assertTrue(PieceStyle.VIP_PLASMA_VORTEX.isUnlockedFor(isSignedIn = true, currentRank = 1))
        assertTrue(PieceStyle.VIP_CELESTIAL_MONARCH.isUnlockedFor(isSignedIn = true, currentRank = 1))
    }

    @Test
    fun `piece style adapter gates clicks on locked vip styles and unlocks for top 1`() {
        val context = ApplicationProvider.getApplicationContext<CaroApplication>()
        context.setTheme(R.style.Theme_CaroXO)
        var selected: PieceStyle? = null
        var lockedClicked: PieceStyle? = null

        val adapter = PieceStyleAdapter(
            selectedStyle = PieceStyle.CLASSIC,
            isSignedIn = false,
            currentRank = null,
            onSelect = { selected = it },
            onLockedClick = { lockedClicked = it }
        )
        val parent = FrameLayout(context)
        val vipIndex = PieceStyle.entries.indexOf(PieceStyle.VIP_CELESTIAL_MONARCH)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, vipIndex)

        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.layout_style_lock).visibility)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.tv_vip_badge).visibility)
        holder.itemView.findViewById<View>(R.id.card_style_item).performClick()
        assertEquals(PieceStyle.VIP_CELESTIAL_MONARCH, lockedClicked)
        assertEquals(null, selected)

        adapter.updateState(PieceStyle.CLASSIC, signedIn = true, rank = 1)
        adapter.onBindViewHolder(holder, vipIndex)
        assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.layout_style_lock).visibility)
        holder.itemView.findViewById<View>(R.id.card_style_item).performClick()
        assertEquals(PieceStyle.VIP_CELESTIAL_MONARCH, selected)
    }

    @Test
    fun `leaderboard adapter and rank tier assign top 1-3 crowns and titles`() {
        assertEquals(RankTier.TOP_1_MONARCH, RankTier.resolve(110L, 1))
        assertEquals(RankTier.TOP_2_SOVEREIGN, RankTier.resolve(110L, 2))
        assertEquals(RankTier.TOP_3_VANGUARD, RankTier.resolve(110L, 3))
        assertEquals(RankTier.LEGEND, RankTier.resolve(460L, 4))
        assertEquals(RankTier.GRANDMASTER, RankTier.resolve(310L, 5))
        assertEquals(RankTier.NOVICE, RankTier.resolve(95L, 10))

        val context = ApplicationProvider.getApplicationContext<CaroApplication>()
        context.setTheme(R.style.Theme_CaroXO)
        val adapter = LeaderboardAdapter("u1")
        val entries = listOf(
            LeaderboardEntry(userId = "u1", nickname = "Ace", points = 500, wins = 40, losses = 5),
            LeaderboardEntry(userId = "u2", nickname = "Beta", points = 320, wins = 25, losses = 8),
            LeaderboardEntry(userId = "u3", nickname = "Gamma", points = 240, wins = 16, losses = 6),
            LeaderboardEntry(userId = "u4", nickname = "Delta", points = 160, wins = 8, losses = 4)
        )
        adapter.submitList(entries)
        val parent = FrameLayout(context)

        val top1Holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(top1Holder, 0)
        assertEquals(View.VISIBLE, top1Holder.itemView.findViewById<View>(R.id.iv_lb_crown).visibility)
        assertFalse(top1Holder.itemView.findViewById<TextView>(R.id.tv_lb_title).text.isNullOrBlank())

        val rank4Holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(rank4Holder, 3)
        assertEquals(View.GONE, rank4Holder.itemView.findViewById<View>(R.id.iv_lb_crown).visibility)
        assertEquals(context.getString(R.string.rank_title_tactician), rank4Holder.itemView.findViewById<TextView>(R.id.tv_lb_title).text.toString())
    }
}
