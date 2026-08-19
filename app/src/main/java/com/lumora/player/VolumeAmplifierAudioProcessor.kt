package com.lumora.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Kodi-style volume amplification for decoded PCM audio.
 *
 * 0 dB = unchanged.
 * Positive values amplify samples with hard clipping at 16-bit limits.
 *
 * The processor remains active even at 0 dB so gain can be changed while
 * playback is running without rebuilding ExoPlayer.
 */
@UnstableApi
class VolumeAmplifierAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var amplificationDb: Float = 0f

    fun setAmplificationDb(db: Float) {
        amplificationDb = db.coerceIn(0f, 30f)
    }

    fun getAmplificationDb(): Float = amplificationDb

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        // Always active so the gain can change during playback.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())

        val gain = 10.0.pow(amplificationDb / 20.0).toFloat()

        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short.toInt()

            val amplified = if (amplificationDb <= 0f) {
                sample
            } else {
                (sample * gain)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            }

            outputBuffer.putShort(amplified.toShort())
        }

        outputBuffer.flip()
    }
}
