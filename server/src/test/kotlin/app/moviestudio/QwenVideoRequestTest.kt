package app.moviestudio

import app.moviestudio.service.QwenAIService
import app.moviestudio.storage.OssService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Validates the WAN 2.7 video-synthesis request body built by
 * [QwenAIService.buildVideoRequestBody] for each generation kind. This guards against the
 * Model Studio `input.media` regressions: I2V must send the first-frame image as a list of media
 * objects (`[{ "type": "first_frame", "url": <url> }]`) under `input.media` (not `input.img_url`,
 * not a scalar, not a list of bare URL strings, and not an `{ "image": <url> }` entry), and the
 * other kinds must keep their own input shape.
 */
class QwenVideoRequestTest {

    private fun JsonObject.input(): JsonObject = getValue("input").jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** The `url` values of each `first_frame` media object in the `input.media` list. */
    private fun JsonObject.firstFrameUrls(): List<String> =
        getValue("media").jsonArray.map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "first_frame" }
            .map { it.getValue("url").jsonPrimitive.content }

    private fun ossUrl(objectKey: String): String {
        val endpointHost = OssService.endpoint.removePrefix("http://").removePrefix("https://").removeSuffix("/")
        return "https://${OssService.bucketName}.$endpointHost/$objectKey?Expires=1&Signature=old"
    }

    @Test
    fun i2vSendsFirstFrameImageAsInputMediaList() {
        val setup = GenerationSetup(kind = "video", prompt = "A cat", imageUrl = "https://oss/first-frame.png")
        assertEquals("i2v", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "A cat, cinematic", "i2v", "wan2.7-i2v")
        val input = body.input()

        assertEquals(listOf("https://oss/first-frame.png"), input.firstFrameUrls(), "I2V must send the image as a first_frame media object under input.media")
        assertNull(input["img_url"], "I2V must not send the legacy img_url field")
    }

    @Test
    fun t2vSendsPromptAndSizeButNoImage() {
        val setup = GenerationSetup(kind = "video", prompt = "A dog", resolution = "1920*1080")
        assertEquals("t2v", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "A dog, cinematic", "t2v", "wan2.7-t2v")
        val input = body.input()

        assertEquals("A dog, cinematic", input.str("prompt"))
        assertNull(input["media"])
        assertNull(input["img_url"])
        assertEquals("1920*1080", body.getValue("parameters").jsonObject.str("size"))
    }

    @Test
    fun r2vSendsReferenceImagesUnderRefImagesUrl() {
        val refs = listOf("https://oss/a.png", "https://oss/b.png")
        val setup = GenerationSetup(kind = "video", prompt = "A hero", referenceImages = refs)
        assertEquals("r2v", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "A hero, cinematic", "r2v", "wan2.7-r2v")
        val urls = body.input().getValue("ref_images_url").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(refs, urls)
        assertNull(body.input()["media"])
    }

    @Test
    fun videoEditSendsBaseVideoUrl() {
        val setup = GenerationSetup(kind = "video", prompt = "Repaint", videoUrl = "https://oss/base.mp4")
        assertEquals("videoedit", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "Repaint, cinematic", "videoedit", "wan2.7-videoedit")

        assertEquals("https://oss/base.mp4", body.input().str("video_url"))
    }

    @Test
    fun i2vRefreshesOwnOssImageUrlBeforeSendingToQwen() {
        val objectKey = "uploads/first-frame.png"
        val staleUrl = ossUrl(objectKey)
        val setup = GenerationSetup(kind = "video", prompt = "A cat", imageUrl = staleUrl)

        val body = QwenAIService.buildVideoRequestBody(setup, "A cat, cinematic", "i2v", "wan2.7-i2v")
        val mediaUrl = body.input().firstFrameUrls().single()

        assertEquals(objectKey, OssService.objectKeyFromUrl(mediaUrl))
        assertNotEquals(staleUrl, mediaUrl)
    }
}
