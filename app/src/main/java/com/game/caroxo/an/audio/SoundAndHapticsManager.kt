package com.game.caroxo.an.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.game.caroxo.an.data.PreferencesManager
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
    private var musicThread: Thread? = null

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
        if (preferencesManager.settings.value.sfxEnabled) {
            playToneAsync(freqHz = 520.0, durationMs = 70, decay = 22.0, volume = 0.45)
        }
        vibrateTap()
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
            audioExecutor.execute {
                playToneSync(523.25, 110, 10.0, 0.42) // C5
                playToneSync(659.25, 110, 10.0, 0.42) // E5
                playToneSync(783.99, 130, 8.0, 0.45)  // G5
                playToneSync(1046.50, 260, 5.0, 0.50) // C6
            }
        }
        vibrateWin()
    }

    fun playLose() {
        if (preferencesManager.settings.value.sfxEnabled) {
            audioExecutor.execute {
                playToneSync(392.0, 130, 10.0, 0.38)
                playToneSync(329.63, 140, 10.0, 0.38)
                playToneSync(261.63, 240, 6.0, 0.40)
            }
        }
        vibrateError()
    }

    fun vibrateTap() {
        if (!preferencesManager.settings.value.hapticsEnabled) return
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
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
        musicRunning = true
        musicThread = Thread {
            val sampleRate = 22050
            val notes = doubleArrayOf(261.63, 293.66, 329.63, 392.00, 440.00, 392.00, 329.63, 293.66)
            var noteIndex = 0
            val bufSamples = sampleRate // 1 second gentle pad per note
            val pcm = ShortArray(bufSamples)
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
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, bufSamples * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()

                while (musicRunning) {
                    val freq = notes[noteIndex % notes.size]
                    noteIndex++
                    for (i in 0 until bufSamples) {
                        val t = i.toDouble() / sampleRate
                        val envelope = sin(PI * i / bufSamples)
                        val wave = sin(2.0 * PI * freq * t) * 0.65 +
                            sin(2.0 * PI * (freq * 1.5) * t) * 0.25
                        val sample = (wave * envelope * 0.08 * Short.MAX_VALUE).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        pcm[i] = sample.toShort()
                    }
                    if (!musicRunning) break
                    track.write(pcm, 0, pcm.size)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stopAmbientMusic() {
        musicRunning = false
        musicThread?.interrupt()
        musicThread = null
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
