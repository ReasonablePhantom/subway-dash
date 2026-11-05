package com.brucelyu.subwaydash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Procedural Synthesized Sound Effects for Android.
 * Zero external audio files required, ensuring guaranteed playback and tiny app size.
 */
class SoundManager {

    private val executor = Executors.newSingleThreadExecutor()
    private val sampleRate = 44100

    fun playCoin() {
        executor.execute {
            val durationMs = 220
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freq = if (t < 0.08) 987.77 else 1318.51
                val decay = 1.0 - (i.toDouble() / numSamples)
                val sample = sin(2.0 * PI * freq * t) * decay * 0.4
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    fun playJump() {
        executor.execute {
            val durationMs = 180
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)

            var phase = 0.0
            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val freq = 220.0 + progress * 360.0
                phase += 2.0 * PI * freq / sampleRate
                val decay = 1.0 - progress
                val sample = sin(phase) * decay * 0.35
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    fun playSlide() {
        executor.execute {
            val durationMs = 200
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)

            var phase = 0.0
            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val freq = 340.0 - progress * 230.0
                phase += 2.0 * PI * freq / sampleRate
                val decay = 1.0 - progress
                val sample = sin(phase) * decay * 0.3
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    fun playCrash() {
        executor.execute {
            val durationMs = 320
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)

            var phase = 0.0
            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val freq = (120.0 - progress * 90.0).coerceAtLeast(30.0)
                phase += 2.0 * PI * freq / sampleRate
                val decay = 1.0 - progress
                val noise = (Math.random() * 2.0 - 1.0) * 0.2
                val sample = (sin(phase) * 0.8 + noise) * decay * 0.5
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    fun playSpeedUp() {
        executor.execute {
            val durationMs = 300
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)

            var phase = 0.0
            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val freq = 350.0 + progress * 530.0
                phase += 2.0 * PI * freq / sampleRate
                val decay = 1.0 - progress * 0.4
                val sample = sin(phase) * decay * 0.35
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    private fun playPcm(buffer: ShortArray) {
        try {
            val audioTrack = AudioTrack.Builder()
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
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.play()
            Thread.sleep((buffer.size.toDouble() / sampleRate * 1000).toLong() + 30)
            audioTrack.release()
        } catch (_: Exception) {}
    }
}
