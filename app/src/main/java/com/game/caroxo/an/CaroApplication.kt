package com.game.caroxo.an

import android.app.Application
import com.game.caroxo.an.ads.AdManager
import com.game.caroxo.an.audio.SoundAndHapticsManager
import com.game.caroxo.an.data.FirebaseGameRepository
import com.game.caroxo.an.data.FirebaseSocialRepository
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.viewmodel.GameViewModel
import com.game.caroxo.an.viewmodel.MainViewModel
import com.google.firebase.firestore.FirebaseFirestore

class CaroApplication : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var soundManager: SoundAndHapticsManager
        private set

    val adManager: AdManager by lazy {
        AdManager(this, preferencesManager)
    }

    private val firestoreDb: FirebaseFirestore by lazy {
        val dbId = getString(R.string.firestore_database_id)
        if (dbId == "(default)") {
            FirebaseFirestore.getInstance()
        } else {
            FirebaseFirestore.getInstance(dbId)
        }
    }

    val repository: FirebaseGameRepository by lazy {
        FirebaseGameRepository(firestoreDb)
    }

    val socialRepository: FirebaseSocialRepository by lazy {
        FirebaseSocialRepository(firestoreDb)
    }

    val mainViewModelFactory: MainViewModel.Factory by lazy {
        MainViewModel.Factory(repository, socialRepository, preferencesManager)
    }

    val gameViewModelFactory: GameViewModel.Factory by lazy {
        GameViewModel.Factory(repository, socialRepository, preferencesManager, soundManager)
    }

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        preferencesManager.applySavedTheme()
        preferencesManager.applySavedLanguage()
        soundManager = SoundAndHapticsManager(this, preferencesManager)
    }
}
