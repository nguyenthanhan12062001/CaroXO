package com.game.caroxo.an.ads

object AdConfig {
    // Google Mobile Ads official Test Ad Unit IDs.
    // Replace these with your production Ad Unit IDs before publishing on Google Play.
    const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"
    const val INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    // Reward & Frequency-capping tuning for a non-intrusive player experience
    const val STYLE_UNLOCK_DURATION_MS = 2L * 60L * 60L * 1000L // 2 hours
    const val INTERSTITIAL_COOLDOWN_MS = 3L * 60L * 1000L // Min 3 minutes between interstitials
    const val INTERSTITIAL_MIN_MATCH_INTERVAL = 3 // Min 3 finished matches between interstitials
    const val NEW_PLAYER_GRACE_MATCHES = 3 // No interstitials during first 3 matches

    // Offline / AI match free utilities before offering optional Rewarded Ad refill
    const val FREE_HINTS_PER_MATCH = 2
    const val FREE_UNDOS_PER_MATCH = 2
    const val REWARD_EXTRA_CHARGES = 3
}
