package app.moviestudio.service

import app.moviestudio.MusicSequence
import app.moviestudio.SEQUENCER_SCALE_SEMITONES
import app.moviestudio.sequencerRowFrequency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * A tiny synthesizer that renders mini-music-sequencer patterns ([MusicSequence]) to 16-bit PCM
 * WAV files. Notes are laid on a pentatonic scale (which always sounds musical), given a soft
 * attack/release envelope and mixed additively. Besides the four classic waveforms, a library
 * sound effect can be used as the instrument ("sample"): the sample is pitch-shifted per row by
 * resampling it at the note's playback rate.
 */
object MusicSynthesizer {
    private val logger = LoggerFactory.getLogger(MusicSynthesizer::class.java)
    private const val SAMPLE_RATE = 44100

    // Sample instruments are capped to keep notes percussive and the mix predictable.
    private const val MAX_SAMPLE_SECONDS = 2.0

    /** Number of pitch rows the sequencer grid offers (shared with the client dialog). */
    val PITCH_ROWS: Int get() = SEQUENCER_SCALE_SEMITONES.size

    /** The duration in seconds of one rendered pattern pass. */
    fun patternSeconds(sequence: MusicSequence): Double {
        val stepSeconds = 60.0 / sequence.tempoBpm / 4.0 // 16th notes
        return stepSeconds * sequence.steps
    }

    /** Total duration in seconds of the rendered file (pattern x loops). */
    fun totalSeconds(sequence: MusicSequence): Double =
        patternSeconds(sequence) * sequence.loops.coerceIn(1, 32)

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
     * When the sequence uses a "sample" instrument, pass the decoded mono PCM as [samplePcm]
     * (see [loadSamplePcm]); a null/empty sample falls back to the sine waveform.
     * Returns the total duration in seconds.
     */
    fun renderToWav(sequence: MusicSequence, outputFile: File, samplePcm: DoubleArray? = null): Double {
        val steps = sequence.steps.coerceIn(4, 64)
        val loops = sequence.loops.coerceIn(1, 32)
        val stepSeconds = 60.0 / sequence.tempoBpm.coerceIn(40, 240) / 4.0
        val noteSeconds = stepSeconds * 1.9 // let notes ring past their step for smoothness
        val patternSamples = (stepSeconds * steps * SAMPLE_RATE).toInt()
        val totalSamples = patternSamples * loops + (SAMPLE_RATE / 2) // half-second tail
        val mix = DoubleArray(totalSamples)
        val useSample = sequence.waveform == "sample" && samplePcm != null && samplePcm.isNotEmpty()

        for (loop in 0 until loops) {
            val loopOffset = loop * patternSamples
            for (note in sequence.notes) {
                if (note.step !in 0 until steps) continue
                val freq = sequencerRowFrequency(note.pitch)
                val startSample = loopOffset + (note.step * stepSeconds * SAMPLE_RATE).toInt()
                val noteSamples = (noteSeconds * SAMPLE_RATE).toInt()
                val attack = (0.012 * SAMPLE_RATE).toInt().coerceAtLeast(1)
                // Sample instrument: resample at the note's rate so higher rows play faster/higher.
                val playbackRate = freq / sequencerRowFrequency(0)
                for (i in 0 until noteSamples) {
                    val idx = startSample + i
                    if (idx >= totalSamples) break
                    val t = i.toDouble() / SAMPLE_RATE
                    // Soft attack, exponential-ish decay envelope.
                    val env = min(i.toDouble() / attack, 1.0) * (1.0 - i.toDouble() / noteSamples).coerceIn(0.0, 1.0)
                    val value = if (useSample) {
                        val sampleIdx = (i * playbackRate).toInt()
                        if (sampleIdx >= samplePcm.size) break
                        samplePcm[sampleIdx]
                    } else {
                        oscillator(sequence.waveform, 2 * PI * freq * t)
                    }
                    mix[idx] += value * env * 0.35
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

    /**
     * Downloads a sample-instrument media URL and decodes it to mono 44.1kHz PCM via ffmpeg
     * (trimmed to [MAX_SAMPLE_SECONDS]). Returns null when the download or decode fails, in
     * which case the renderer falls back to the sine waveform.
     */
    suspend fun loadSamplePcm(sampleUrl: String): DoubleArray? = withContext(Dispatchers.IO) {
        var source: File? = null
        var decoded: File? = null
        try {
            val suffix = "." + sampleUrl.substringBefore('?').substringAfterLast('.', "mp3").take(4)
            source = MediaUtil.downloadToTemp(sampleUrl, suffix)
            decoded = File.createTempFile("moviestudio_sample_", ".raw")
            val args = listOf(
                MediaUtil.ffmpegBinary, "-y", "-i", source.absolutePath,
                "-t", MAX_SAMPLE_SECONDS.toString(),
                "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "1", "-ar", SAMPLE_RATE.toString(),
                decoded.absolutePath
            )
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn("Sample decode timed out for $sampleUrl")
                return@withContext null
            }
            if (process.exitValue() != 0 || decoded.length() < 4) {
                logger.warn("Sample decode failed for $sampleUrl: ${log.takeLast(300)}")
                return@withContext null
            }
            val bytes = decoded.readBytes()
            DoubleArray(bytes.size / 2) { i ->
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                ((hi shl 8) or lo) / Short.MAX_VALUE.toDouble()
            }
        } catch (e: Exception) {
            logger.warn("Sample instrument unavailable (${e.message}); falling back to sine.")
            null
        } finally {
            runCatching { source?.delete() }
            runCatching { decoded?.delete() }
        }
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
