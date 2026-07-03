package app.moviestudio.service

import app.moviestudio.MusicSequence
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * A tiny, dependency-free synthesizer that renders mini-music-sequencer patterns
 * ([MusicSequence]) to 16-bit PCM WAV files. Notes are laid on a pentatonic scale (which always
 * sounds musical), given a soft attack/release envelope and mixed additively.
 */
object MusicSynthesizer {
    private const val SAMPLE_RATE = 44100

    // C major pentatonic across two octaves + the root on top: always-consonant rows.
    private val SCALE_SEMITONES = listOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24)

    /** Number of pitch rows the sequencer grid offers. */
    val PITCH_ROWS: Int get() = SCALE_SEMITONES.size

    /** The duration in seconds of one rendered pattern pass. */
    fun patternSeconds(sequence: MusicSequence): Double {
        val stepSeconds = 60.0 / sequence.tempoBpm / 4.0 // 16th notes
        return stepSeconds * sequence.steps
    }

    /** Total duration in seconds of the rendered file (pattern x loops). */
    fun totalSeconds(sequence: MusicSequence): Double =
        patternSeconds(sequence) * sequence.loops.coerceIn(1, 32)

    /** Frequency (Hz) for a sequencer row: C4 root walking up the pentatonic scale. */
    private fun frequencyForRow(row: Int): Double {
        val semitone = SCALE_SEMITONES[row.coerceIn(0, SCALE_SEMITONES.size - 1)]
        return 261.6256 * Math.pow(2.0, semitone / 12.0)
    }

    private fun oscillator(waveform: String, phase: Double): Double {
        val p = phase % (2 * PI)
        return when (waveform) {
            "square" -> if (sin(p) >= 0) 0.6 else -0.6
            "saw" -> ((p / PI) - 1.0) * 0.7
            "triangle" -> (2.0 / PI) * kotlin.math.asin(sin(p))
            else -> sin(p) // sine
        }
    }

    /**
     * Renders [sequence] into a 16-bit mono PCM WAV and writes it to [outputFile].
     * Returns the total duration in seconds.
     */
    fun renderToWav(sequence: MusicSequence, outputFile: File): Double {
        val steps = sequence.steps.coerceIn(4, 64)
        val loops = sequence.loops.coerceIn(1, 32)
        val stepSeconds = 60.0 / sequence.tempoBpm.coerceIn(40, 240) / 4.0
        val noteSeconds = stepSeconds * 1.9 // let notes ring past their step for smoothness
        val patternSamples = (stepSeconds * steps * SAMPLE_RATE).toInt()
        val totalSamples = patternSamples * loops + (SAMPLE_RATE / 2) // half-second tail
        val mix = DoubleArray(totalSamples)

        for (loop in 0 until loops) {
            val loopOffset = loop * patternSamples
            for (note in sequence.notes) {
                if (note.step !in 0 until steps) continue
                val freq = frequencyForRow(note.pitch)
                val startSample = loopOffset + (note.step * stepSeconds * SAMPLE_RATE).toInt()
                val noteSamples = (noteSeconds * SAMPLE_RATE).toInt()
                val attack = (0.012 * SAMPLE_RATE).toInt().coerceAtLeast(1)
                for (i in 0 until noteSamples) {
                    val idx = startSample + i
                    if (idx >= totalSamples) break
                    val t = i.toDouble() / SAMPLE_RATE
                    // Soft attack, exponential-ish decay envelope.
                    val env = min(i.toDouble() / attack, 1.0) * (1.0 - i.toDouble() / noteSamples).coerceIn(0.0, 1.0)
                    mix[idx] += oscillator(sequence.waveform, 2 * PI * freq * t) * env * 0.35
                }
            }
        }

        // Normalize if the additive mix clips.
        val peak = mix.maxOfOrNull { abs(it) } ?: 0.0
        val gain = if (peak > 0.98) 0.98 / peak else 1.0

        val pcm = ByteArrayOutputStream(totalSamples * 2)
        for (sample in mix) {
            val s = (sample * gain * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm.write(s and 0xFF)
            pcm.write((s shr 8) and 0xFF)
        }
        writeWav(outputFile, pcm.toByteArray())
        return totalSamples.toDouble() / SAMPLE_RATE
    }

    /** Wraps raw 16-bit mono PCM data in a standard WAV (RIFF) header. */
    private fun writeWav(file: File, pcmData: ByteArray) {
        val byteRate = SAMPLE_RATE * 2
        val dataSize = pcmData.size
        val out = ByteArrayOutputStream(44 + dataSize)
        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        writeString("RIFF"); writeIntLE(36 + dataSize); writeString("WAVE")
        writeString("fmt "); writeIntLE(16); writeShortLE(1); writeShortLE(1)
        writeIntLE(SAMPLE_RATE); writeIntLE(byteRate); writeShortLE(2); writeShortLE(16)
        writeString("data"); writeIntLE(dataSize)
        out.write(pcmData)
        file.writeBytes(out.toByteArray())
    }
}
