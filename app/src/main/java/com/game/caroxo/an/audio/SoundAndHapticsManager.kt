package com.game.caroxo.an.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class SoundAndHapticsManager(
    context: Context,
    private val preferencesManager: PreferencesManager
) {
    private val appContext = context.applicationContext
    private val audioExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var musicRunning = false
    private var mediaPlayer: MediaPlayer? = null

    private val loadedSounds = ConcurrentHashMap.newKeySet<Int>()
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(5)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build().apply {
            setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    loadedSounds.add(sampleId)
                }
            }
        }

    private val placePieceSoundId: Int = soundPool.load(appContext, R.raw.datquan, 1)
    private val winSoundId: Int = soundPool.load(appContext, R.raw.win, 1)
    private val loseSoundId: Int = soundPool.load(appContext, R.raw.lose, 1)

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun playPlacePiece() {
        val settings = preferencesManager.settings.value
        if (settings.sfxEnabled) {
            playSoundEffect(placePieceSoundId)
        }
        vibrateTap(impact = settings.impactEffectsEnabled)
    }

    fun playUiClick() {
        if (preferencesManager.settings.value.sfxEnabled) {
            playToneAsync(freqHz = 740.0, durationMs = 35, decay = 35.0, volume = 0.25)
        }
    }

    fun playError() {
        if (preferencesManager.settings.value.sfxEnabled) {
            playToneAsync(freqHz = 210.0, durationMs = 110, decay = 14.0, volume = 0.35)
        }
        vibrateError()
    }

    fun playWin() {
        if (preferencesManager.settings.value.sfxEnabled) {
            playSoundEffect(winSoundId)
        }
        vibrateWin()
    }

    fun playLose() {
        if (preferencesManager.settings.value.sfxEnabled) {
            playSoundEffect(loseSoundId)
        }
        vibrateError()
    }

    private fun playSoundEffect(soundId: Int) {
        if (soundId != 0 && loadedSounds.contains(soundId)) {
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    fun vibrateTap(impact: Boolean = false) {
        if (!preferencesManager.settings.value.hapticsEnabled) return
        try {
            val effect = if (impact) {
                VibrationEffect.createOneShot(26L, 200)
            } else {
                VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator?.vibrate(effect)
        } catch (_: Exception) {
        }
    }

    fun vibrateError() {
        if (!preferencesManager.settings.value.hapticsEnabled) return
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 35, 40, 35), -1))
        } catch (_: Exception) {
        }
    }

    fun vibrateWin() {
        if (!preferencesManager.settings.value.hapticsEnabled) return
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 70, 50, 90), -1))
        } catch (_: Exception) {
        }
    }

    fun syncAmbientMusic(isResumed: Boolean) {
        val shouldPlay = isResumed && preferencesManager.settings.value.musicEnabled
        if (shouldPlay && !musicRunning) {
            startAmbientMusic()
        } else if (!shouldPlay && musicRunning) {
            stopAmbientMusic()
        }
    }

    @Synchronized
    private fun startAmbientMusic() {
        if (musicRunning) return
        try {
            val player = MediaPlayer.create(appContext, R.raw.nhacnen) ?: return
            player.isLooping = true
            player.setVolume(0.45f, 0.45f)
            player.setOnErrorListener { mp, _, _ ->
                musicRunning = false
                try {
                    mp.release()
                } catch (_: Exception) {
                }
                if (mediaPlayer === mp) {
                    mediaPlayer = null
                }
                true
            }
            player.start()
            mediaPlayer = player
            musicRunning = true
        } catch (_: Exception) {
            musicRunning = false
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {
            }
            mediaPlayer = null
        }
    }

    @Synchronized
    fun stopAmbientMusic() {
        musicRunning = false
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }
    }

    private fun playToneAsync(freqHz: Double, durationMs: Int, decay: Double, volume: Double) {
        audioExecutor.execute {
            playToneSync(freqHz, durationMs, decay, volume)
        }
    }

    private fun playToneSync(freqHz: Double, durationMs: Int, decay: Double, volume: Double) {
        val sampleRate = 22050
        val count = (sampleRate * durationMs) / 1000
        if (count <= 0) return
        val pcm = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / sampleRate
            val env = exp(-decay * t)
            val wave = sin(2.0 * PI * freqHz * t) * env
            pcm[i] = (wave * volume * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 15L)
        } catch (_: Exception) {
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }
}
