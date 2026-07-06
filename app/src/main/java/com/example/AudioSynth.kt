package com.example

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.sin

object AudioSynth {
    private const val SAMPLE_RATE = 22050

    fun playTone(freq: Double, durationMs: Int, volume: Float = 0.4f) {
        Thread {
            try {
                val numSamples = (SAMPLE_RATE * (durationMs / 1000.0)).toInt()
                val sample = DoubleArray(numSamples)
                val generatedSnd = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    sample[i] = sin(2.0 * Math.PI * i / (SAMPLE_RATE / freq))
                }

                for (i in 0 until numSamples) {
                    // Smooth envelope to prevent popping/clicking noise
                    val envelope = when {
                        i < numSamples * 0.1 -> i / (numSamples * 0.1) // Fade-in
                        i > numSamples * 0.8 -> (numSamples - i) / (numSamples * 0.2) // Fade-out
                        else -> 1.0
                    }
                    generatedSnd[i] = (sample[i] * 32767.0 * envelope * volume).toInt().toShort()
                }

                val audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    numSamples * 2,
                    AudioTrack.MODE_STATIC
                )
                audioTrack.write(generatedSnd, 0, numSamples)
                audioTrack.play()
                Thread.sleep(durationMs.toLong() + 30)
                audioTrack.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun playTap() {
        playTone(659.25, 50, 0.15f) // E5 note (light clean feedback)
    }

    fun playWin() {
        Thread {
            playTone(523.25, 120, 0.35f) // C5
            Thread.sleep(130)
            playTone(659.25, 120, 0.35f) // E5
            Thread.sleep(130)
            playTone(783.99, 120, 0.35f) // G5
            Thread.sleep(130)
            playTone(1046.50, 240, 0.45f) // C6
        }.start()
    }

    fun playLose() {
        Thread {
            playTone(392.00, 140, 0.35f) // G4
            Thread.sleep(160)
            playTone(349.23, 140, 0.35f) // F4
            Thread.sleep(160)
            playTone(311.13, 280, 0.35f) // Eb4
        }.start()
    }

    fun playHint() {
        Thread {
            playTone(880.00, 90, 0.3f) // A5
            Thread.sleep(110)
            playTone(1174.66, 140, 0.3f) // D6
        }.start()
    }
}
