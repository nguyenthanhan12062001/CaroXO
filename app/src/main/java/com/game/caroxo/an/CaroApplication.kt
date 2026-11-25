package com.game.caroxo.an

import android.app.Application
import com.game.caroxo.an.audio.SoundAndHapticsManager
import com.game.caroxo.an.data.FirebaseGameRepository
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.viewmodel.GameViewModel
import com.game.caroxo.an.viewmodel.MainViewModel
import com.google.firebase.firestore.FirebaseFirestore

class CaroApplication : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var soundManager: SoundAndHapticsManager
        private set

    val repository: FirebaseGameRepository by lazy {
        val dbId = getString(R.string.firestore_database_id)
        val db = if (dbId == "(default)") {
            FirebaseFirestore.getInstance()
        } else {
            FirebaseFirestore.getInstance(dbId)
        }
        FirebaseGameRepository(db)
    }

    val mainViewModelFactory: MainViewModel.Factory by lazy {
        MainViewModel.Factory(repository, preferencesManager)
    }

    val gameViewModelFactory: GameViewModel.Factory by lazy {
        GameViewModel.Factory(repository, preferencesManager, soundManager)
    }

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        preferencesManager.applySavedTheme()
        preferencesManager.applySavedLanguage()
        soundManager = SoundAndHapticsManager(this, preferencesManager)
    }
}
