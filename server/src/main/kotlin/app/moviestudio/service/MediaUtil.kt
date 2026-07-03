package app.moviestudio.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Local media helpers built on the external ffmpeg/ffprobe binaries: downloading remote media to
 * scratch files, extracting the audio track from a video and probing media durations. Everything
 * degrades gracefully when the binaries are unavailable (mock/dev environments).
 */
object MediaUtil {
    private val logger = LoggerFactory.getLogger(MediaUtil::class.java)

    val ffmpegBinary: String = System.getenv("FFMPEG_PATH") ?: "ffmpeg"
    val ffprobeBinary: String = System.getenv("FFPROBE_PATH") ?: "ffprobe"

    /** Downloads [url] into a temp file with the given [suffix]. Caller owns/deletes the file. */
    suspend fun downloadToTemp(url: String, suffix: String): File = withContext(Dispatchers.IO) {
        val file = Files.createTempFile("moviestudio_media_", suffix).toFile()
        URI(url).toURL().openStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file
    }

    /**
     * Extracts the audio track of [input] into an mp3 next to it and returns the mp3 file, or
     * null when extraction fails (no audio stream / ffmpeg unavailable).
     */
    suspend fun extractAudio(input: File): File? = withContext(Dispatchers.IO) {
        val output = File(input.parentFile, input.nameWithoutExtension + "_audio.mp3")
        val args = listOf(ffmpegBinary, "-y", "-i", input.absolutePath, "-vn", "-acodec", "libmp3lame", "-q:a", "3", output.absolutePath)
        try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn("Audio extraction timed out for ${input.name}")
                return@withContext null
            }
            if (process.exitValue() != 0 || !output.exists() || output.length() == 0L) {
                logger.warn("Audio extraction failed for ${input.name}: ${log.takeLast(400)}")
                return@withContext null
            }
            output
        } catch (e: Exception) {
            logger.warn("Audio extraction unavailable (${e.message}); is ffmpeg installed?")
            null
        }
    }

    /** True when the local media [file] contains at least one audio stream (ffprobe). */
    suspend fun probeHasAudio(file: File): Boolean = withContext(Dispatchers.IO) {
        val args = listOf(
            ffprobeBinary, "-v", "error",
            "-select_streams", "a",
            "-show_entries", "stream=codec_type",
            "-of", "csv=p=0",
            file.absolutePath
        )
        try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            text.contains("audio")
        } catch (e: Exception) {
            false
        }
    }

    /** Probes the duration (seconds) of a local media [file] via ffprobe; null when unknown. */
    suspend fun probeDurationSeconds(file: File): Double? = withContext(Dispatchers.IO) {
        val args = listOf(
            ffprobeBinary, "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        )
        try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext null
            }
            text.lines().firstOrNull()?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }
        } catch (e: Exception) {
            logger.warn("ffprobe unavailable (${e.message})")
            null
        }
    }
}
