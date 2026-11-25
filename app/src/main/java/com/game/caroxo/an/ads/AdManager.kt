package com.game.caroxo.an.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AdManager(
    context: Context,
    private val preferencesManager: PreferencesManager
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val isMobileAdsInitialized = AtomicBoolean(false)
    private var consentRequestedThisSession = false

    private var rewardedAd: RewardedAd? = null
    private var isLoadingRewarded = false

    private var interstitialAd: InterstitialAd? = null
    private var isLoadingInterstitial = false

    fun initConsentAndAds(activity: Activity) {
        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        if (consentInfo.canRequestAds()) {
            initializeMobileAds()
        }
        if (consentRequestedThisSession) return
        consentRequestedThisSession = true

        val params = ConsentRequestParameters.Builder().build()
        consentInfo.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "UMP consent form warning: ${formError.message}")
                    }
                    if (consentInfo.canRequestAds()) {
                        initializeMobileAds()
                    }
                }
            },
            { requestError ->
                Log.w(TAG, "UMP consent update failed: ${requestError.message}")
                if (consentInfo.canRequestAds()) {
                    initializeMobileAds()
                }
            }
        )
    }

    fun isPrivacyOptionsRequired(context: Context): Boolean {
        return try {
            val consentInfo = UserMessagingPlatform.getConsentInformation(context)
            consentInfo.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        } catch (_: Exception) {
            false
        }
    }

    fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Log.w(TAG, "Privacy options form error: ${formError.message}")
            }
            onDismissed()
        }
    }

    private fun canRequestAds(): Boolean {
        return try {
            UserMessagingPlatform.getConsentInformation(appContext).canRequestAds()
        } catch (_: Exception) {
            false
        }
    }

    private fun initializeMobileAds() {
        if (isMobileAdsInitialized.getAndSet(true)) return
        scope.launch {
            try {
                MobileAds.initialize(appContext) {
                    preloadRewardedAd()
                    preloadInterstitialAd()
                }
            } catch (e: Exception) {
                isMobileAdsInitialized.set(false)
                Log.w(TAG, "MobileAds initialize failed", e)
            }
        }
    }

    fun preloadRewardedAd() {
        if (!canRequestAds() || isLoadingRewarded || rewardedAd != null) return
        isLoadingRewarded = true
        val request = AdRequest.Builder().build()
        RewardedAd.load(
            appContext,
            AdConfig.REWARDED_UNIT_ID,
            request,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoadingRewarded = false
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingRewarded = false
                    rewardedAd = null
                    Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
                }
            }
        )
    }

    fun preloadInterstitialAd() {
        if (!canRequestAds() || isLoadingInterstitial || interstitialAd != null) return
        isLoadingInterstitial = true
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            appContext,
            AdConfig.INTERSTITIAL_UNIT_ID,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoadingInterstitial = false
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingInterstitial = false
                    interstitialAd = null
                    Log.w(TAG, "Interstitial ad failed to load: ${error.message}")
                }
            }
        )
    }

    fun showRewardedAd(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onNotReady: () -> Unit = {
            Toast.makeText(activity, R.string.ad_loading_try_again, Toast.LENGTH_SHORT).show()
        }
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val ad = rewardedAd
        if (ad == null) {
            initializeMobileAds()
            preloadRewardedAd()
            onNotReady()
            return
        }

        var earned = false
        rewardedAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preloadRewardedAd()
                if (earned) {
                    onRewardEarned()
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.w(TAG, "Rewarded ad failed to show: ${adError.message}")
                preloadRewardedAd()
                onNotReady()
            }
        }
        ad.show(activity) {
            earned = true
            preferencesManager.recordRewardedAdWatched()
        }
    }

    fun maybeShowInterstitialAfterMatchExit(
        activity: Activity,
        isAdmin: Boolean,
        onDone: () -> Unit
    ) {
        if (isAdmin || activity.isFinishing || activity.isDestroyed) {
            onDone()
            return
        }
        val eligible = preferencesManager.canShowInterstitial(
            graceMatches = AdConfig.NEW_PLAYER_GRACE_MATCHES,
            minMatchInterval = AdConfig.INTERSTITIAL_MIN_MATCH_INTERVAL,
            cooldownMs = AdConfig.INTERSTITIAL_COOLDOWN_MS
        )
        if (!eligible) {
            onDone()
            return
        }
        val ad = interstitialAd
        if (ad == null) {
            preloadInterstitialAd()
            onDone()
            return
        }
        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preferencesManager.recordInterstitialShown()
                preloadInterstitialAd()
                onDone()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                preloadInterstitialAd()
                onDone()
            }
        }
        ad.show(activity)
    }

    fun attachAdaptiveBanner(
        container: FrameLayout,
        lifecycleOwner: LifecycleOwner,
        isAdmin: Boolean
    ) {
        if (isAdmin || !canRequestAds()) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }

        var attachedAdView: AdView? = null
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                attachedAdView?.resume()
            }

            override fun onPause(owner: LifecycleOwner) {
                attachedAdView?.pause()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                attachedAdView?.destroy()
                attachedAdView = null
                container.removeAllViews()
                owner.lifecycle.removeObserver(this)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        container.post {
            val context = container.context
            val activity = context as? Activity
            if (activity != null && (activity.isFinishing || activity.isDestroyed)) return@post
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.INITIALIZED)) {
                return@post
            }

            val density = context.resources.displayMetrics.density
            val widthPx = container.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val adWidthDp = (widthPx / density).toInt().coerceAtLeast(320)
            val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, adWidthDp)

            val adView = AdView(context).apply {
                adUnitId = AdConfig.BANNER_UNIT_ID
                setAdSize(adSize)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        container.visibility = View.VISIBLE
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "Banner failed to load: ${error.message}")
                        container.visibility = View.GONE
                    }
                }
            }
            attachedAdView = adView
            container.removeAllViews()
            container.addView(adView)
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    private companion object {
        const val TAG = "AdManager"
    }
}
