package app.moviestudio

import app.moviestudio.service.QwenAIService
import app.moviestudio.service.QwenConfig
import app.moviestudio.storage.OssService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Validates the WAN 3.0 video-synthesis request body built by
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

    /** The `url` values of each `last_frame` media object in the `input.media` list. */
    private fun JsonObject.lastFrameUrls(): List<String> =
        getValue("media").jsonArray.map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "last_frame" }
            .map { it.getValue("url").jsonPrimitive.content }

    /** The `url` values of each `reference_image` media object in the `input.media` list. */
    private fun JsonObject.referenceUrls(): List<String> =
        getValue("media").jsonArray.map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "reference_image" }
            .map { it.getValue("url").jsonPrimitive.content }

    /** The `url` values of each `reference_video` media object in the `input.media` list. */
    private fun JsonObject.referenceVideoUrls(): List<String> =
        getValue("media").jsonArray.map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "reference_video" }
            .map { it.getValue("url").jsonPrimitive.content }

    /** The `url` values of each `video` media object in the `input.media` list. */
    private fun JsonObject.videoUrls(): List<String> =
        getValue("media").jsonArray.map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "video" }
            .map { it.getValue("url").jsonPrimitive.content }

    /** The `type` of each media object in the `input.media` list, in order. */
    private fun JsonObject.mediaTypes(): List<String> =
        getValue("media").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content }

    /** The `url` values of each `driving_audio` media object in the `input.media` list. */
    private fun JsonObject.drivingAudioUrls(): List<String> =
        getValue("media").jsonArray.map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "driving_audio" }
            .map { it.getValue("url").jsonPrimitive.content }

    private fun ossUrl(objectKey: String): String {
        val endpointHost = OssService.endpoint.removePrefix("http://").removePrefix("https://").removeSuffix("/")
        return "https://${OssService.bucketName}.$endpointHost/$objectKey?Expires=1&Signature=old"
    }

    @Test
    fun defaultVideoModelsAreTheUnifiedWan30Id() {
        // DashScope rejects wan3.0-t2v / i2v / r2v / videoedit with "Model not exist."
        assertEquals(DEFAULT_VIDEO_MODEL_ID, QwenConfig.videoModel)
        assertEquals(DEFAULT_VIDEO_MODEL_ID, QwenConfig.videoModelT2V)
        assertEquals(DEFAULT_VIDEO_MODEL_ID, QwenConfig.videoModelI2V)
        assertEquals(DEFAULT_VIDEO_MODEL_ID, QwenConfig.videoModelR2V)
        assertEquals(DEFAULT_VIDEO_MODEL_ID, QwenConfig.videoModelEdit)
    }

    @Test
    fun i2vSendsFirstFrameImageAsInputMediaList() {
        val setup = GenerationSetup(kind = "video", prompt = "A cat", imageUrl = "https://oss/first-frame.png")
        assertEquals("i2v", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "A cat, cinematic", "i2v", DEFAULT_VIDEO_MODEL_ID)
        val input = body.input()

        assertEquals(listOf("https://oss/first-frame.png"), input.firstFrameUrls(), "I2V must send the image as a first_frame media object under input.media")
        assertNull(input["img_url"], "I2V must not send the legacy img_url field")
        assertTrue(input.lastFrameUrls().isEmpty(), "I2V without an end image must not send a last_frame media object")
    }

    @Test
    fun i2vSendsEndImageAsLastFrameMediaObject() {
        val setup = GenerationSetup(
            kind = "video",
            prompt = "A cat",
            imageUrl = "https://oss/first-frame.png",
            endImageUrl = "https://oss/last-frame.png",
        )
        assertEquals("i2v", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "A cat, cinematic", "i2v", DEFAULT_VIDEO_MODEL_ID)
        val input = body.input()

        assertEquals(listOf("https://oss/first-frame.png"), input.firstFrameUrls(), "the start image must remain the first_frame media object")
        assertEquals(listOf("https://oss/last-frame.png"), input.lastFrameUrls(), "the end image must be sent as a last_frame media object under input.media")
    }

    @Test
    fun t2vSendsPromptAndSizeButNoImage() {
        val setup = GenerationSetup(kind = "video", prompt = "A dog", resolution = "1920*1080")
        assertEquals("t2v", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "A dog, cinematic", "t2v", DEFAULT_VIDEO_MODEL_ID)
        val input = body.input()

        assertEquals("A dog, cinematic", input.str("prompt"))
        assertNull(input["media"])
        assertNull(input["img_url"])
        assertEquals(DEFAULT_VIDEO_MODEL_ID, body.str("model"))
        val params = body.getValue("parameters").jsonObject
        assertEquals("1080P", params.str("resolution"))
        assertEquals("16:9", params.str("ratio"))
        assertNull(params["size"])
    }

    @Test
    fun t2vHonorsThirtySecondDurationAndTwoKSize() {
        // WAN 3.0 lifts the max generation length to 30s and adds 2K output sizes.
        val setup = GenerationSetup(
            kind = "video",
            prompt = "A long take",
            resolution = "2560*1440",
            durationSeconds = 30.0,
        )
        val body = QwenAIService.buildVideoRequestBody(setup, "A long take, cinematic", "t2v", DEFAULT_VIDEO_MODEL_ID)
        val params = body.getValue("parameters").jsonObject
        assertEquals("1080P", params.str("resolution"))
        assertEquals("16:9", params.str("ratio"))
        assertEquals("30", params.str("duration"))
    }

    @Test
    fun t2vSmartDurationSendsMinusOne() {
        val setup = GenerationSetup(kind = "video", prompt = "A dog", smartDuration = true, durationSeconds = 12.0)
        val body = QwenAIService.buildVideoRequestBody(setup, "A dog, cinematic", "t2v", DEFAULT_VIDEO_MODEL_ID)
        val params = body.getValue("parameters").jsonObject
        assertEquals("-1", params.str("duration"), "Smart Duration must send duration=-1 so WAN picks the length")
    }

    @Test
    fun videoEditOmitsDurationEvenWithSmartDuration() {
        val setup = GenerationSetup(
            kind = "video",
            prompt = "Repaint",
            videoUrl = "https://oss/base.mp4",
            smartDuration = true,
        )
        val body = QwenAIService.buildVideoRequestBody(setup, "Repaint, cinematic", "videoedit", DEFAULT_VIDEO_MODEL_ID)
        assertNull(
            body.getValue("parameters").jsonObject["duration"],
            "video-edit output length matches the base clip, so Smart Duration must not send duration",
        )
    }

    @Test
    fun r2vSendsReferenceImagesAsInputMediaList() {
        val refs = listOf("https://oss/a.png", "https://oss/b.png")
        val setup = GenerationSetup(kind = "video", prompt = "A hero", referenceImages = refs)
        assertEquals("r2v", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "A hero, cinematic", "r2v", DEFAULT_VIDEO_MODEL_ID)
        val input = body.input()

        assertEquals(refs, input.referenceUrls(), "R2V must send reference images as reference_image media objects under input.media")
        assertNull(input["ref_images_url"], "R2V must not send the legacy ref_images_url field")
        assertEquals(
            "720P",
            body.getValue("parameters").jsonObject.str("resolution"),
            "R2V must send an explicit output resolution, otherwise the task fails with a missing 'resolution' error",
        )
    }

    @Test
    fun videoEditSendsBaseVideoAsInputMediaList() {
        val setup = GenerationSetup(kind = "video", prompt = "Repaint", videoUrl = "https://oss/base.mp4")
        assertEquals("videoedit", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "Repaint, cinematic", "videoedit", DEFAULT_VIDEO_MODEL_ID)
        val input = body.input()

        assertEquals(
            listOf("https://oss/base.mp4"),
            input.videoUrls(),
            "video edit must send the base video as a video media object under input.media",
        )
        assertEquals(
            "video",
            input.mediaTypes().first(),
            "the video-edit model only accepts the media types 'video' or 'reference_image'; the base " +
                "clip at index 0 must be a 'video' entry (a 'reference_video' entry is rejected with " +
                "\"Input should be 'video' or 'reference_image': input.media.0.type\")",
        )
        assertTrue(input.referenceVideoUrls().isEmpty(), "video edit must not send a reference_video media object")
        assertNull(input["video_url"], "video edit must not send the legacy scalar video_url field")
        assertNull(input["ref_images_url"], "video edit must not send the legacy ref_images_url field")
        assertNull(input["img_url"], "video edit must not send the legacy img_url field")
        // The edited clip inherits the base video's resolution and length, so no output size or
        // duration parameter is sent.
        val parameters = body.getValue("parameters").jsonObject
        assertNull(parameters["resolution"], "video edit must not send an output resolution (it follows the base video)")
        assertNull(parameters["ratio"], "video edit must not send an output ratio (it follows the base video)")
        assertNull(parameters["duration"], "video edit must not send a duration (it follows the base video)")
    }

    @Test
    fun videoEditWithReferenceImageSendsOnlyVideoAndReferenceImageTypes() {
        // The exact scenario from the bug report: editing a video as another video while also
        // supplying a reference character (image). Model Studio's video-edit model only accepts the
        // media types 'video' or 'reference_image', so the base clip must be a 'video' entry at
        // index 0 and every reference image rides along as a 'reference_image' entry. Any
        // 'reference_video' / 'first_frame' entry is rejected with "Input should be 'video' or
        // 'reference_image': input.media.0.type".
        val setup = GenerationSetup(
            kind = "video",
            prompt = "Repaint",
            videoUrl = "https://oss/base.mp4",
            referenceImages = listOf("https://oss/ref-a.png", "https://oss/ref-b.png"),
            imageUrl = "https://oss/first-frame.png",
        )
        assertEquals("videoedit", setup.resolveVideoModelKind())

        val body = QwenAIService.buildVideoRequestBody(setup, "Repaint, cinematic", "videoedit", DEFAULT_VIDEO_MODEL_ID)
        val input = body.input()

        assertEquals(listOf("https://oss/base.mp4"), input.videoUrls())
        assertEquals(
            listOf("https://oss/ref-a.png", "https://oss/ref-b.png"),
            input.referenceUrls(),
            "optional reference images must ride along as reference_image media objects",
        )
        assertEquals(
            listOf("video", "reference_image", "reference_image"),
            input.mediaTypes(),
            "the video-edit model only accepts 'video' or 'reference_image' media types",
        )
        assertTrue(
            input.referenceVideoUrls().isEmpty(),
            "video edit must not send a reference_video media object",
        )
        assertTrue(
            input.firstFrameUrls().isEmpty(),
            "the video-edit model does not accept a first_frame media object",
        )
    }

    @Test
    fun i2vRefreshesOwnOssImageUrlBeforeSendingToQwen() {
        val objectKey = "uploads/first-frame.png"
        val staleUrl = ossUrl(objectKey)
        val setup = GenerationSetup(kind = "video", prompt = "A cat", imageUrl = staleUrl)

        val body = QwenAIService.buildVideoRequestBody(setup, "A cat, cinematic", "i2v", DEFAULT_VIDEO_MODEL_ID)
        val mediaUrl = body.input().firstFrameUrls().single()

        assertEquals(objectKey, OssService.objectKeyFromUrl(mediaUrl))
        assertNotEquals(staleUrl, mediaUrl)
    }

    @Test
    fun r2vWithDrivingAudioAppendsDrivingAudioMediaAfterReferences() {
        val refs = listOf("https://oss/a.png", "https://oss/b.png")
        val drivingKey = "ai-generated/m1/driving-sample.mp3"
        val staleDriving = ossUrl(drivingKey)
        val setup = GenerationSetup(kind = "video", prompt = "A hero says hello", referenceImages = refs)

        val body = QwenAIService.buildVideoRequestBody(
            setup,
            "A hero says hello",
            "r2v",
            DEFAULT_VIDEO_MODEL_ID,
            drivingAudioUrl = staleDriving,
        )
        val input = body.input()

        assertEquals(refs, input.referenceUrls())
        assertEquals(
            listOf("reference_image", "reference_image", "driving_audio"),
            input.mediaTypes(),
            "driving_audio must ride after reference images on R2V",
        )
        val drivingUrl = input.drivingAudioUrls().single()
        assertEquals(drivingKey, OssService.objectKeyFromUrl(drivingUrl))
        assertNotEquals(staleDriving, drivingUrl, "driving audio URL must be refreshed via freshDownloadUrl")
    }

    @Test
    fun i2vWithDrivingAudioCoexistsWithFirstAndLastFrame() {
        val setup = GenerationSetup(
            kind = "video",
            prompt = "A cat speaks",
            imageUrl = "https://oss/first-frame.png",
            endImageUrl = "https://oss/last-frame.png",
        )
        val body = QwenAIService.buildVideoRequestBody(
            setup,
            "A cat speaks",
            "i2v",
            DEFAULT_VIDEO_MODEL_ID,
            drivingAudioUrl = "https://oss/drive.mp3",
        )
        val input = body.input()

        assertEquals(listOf("https://oss/first-frame.png"), input.firstFrameUrls())
        assertEquals(listOf("https://oss/last-frame.png"), input.lastFrameUrls())
        assertEquals(
            listOf("first_frame", "last_frame", "driving_audio"),
            input.mediaTypes(),
        )
        assertEquals(listOf("https://oss/drive.mp3"), input.drivingAudioUrls())
    }

    @Test
    fun blankDrivingAudioKeepsTodayR2vBody() {
        val refs = listOf("https://oss/a.png")
        val setup = GenerationSetup(kind = "video", prompt = "A hero", referenceImages = refs)
        val body = QwenAIService.buildVideoRequestBody(
            setup,
            "A hero",
            "r2v",
            DEFAULT_VIDEO_MODEL_ID,
            drivingAudioUrl = "   ",
        )
        val input = body.input()
        assertEquals(listOf("reference_image"), input.mediaTypes())
        assertTrue(input.drivingAudioUrls().isEmpty())
    }

    @Test
    fun t2vAndVideoEditIgnoreDrivingAudio() {
        val t2v = QwenAIService.buildVideoRequestBody(
            GenerationSetup(kind = "video", prompt = "A dog"),
            "A dog",
            "t2v",
            DEFAULT_VIDEO_MODEL_ID,
            drivingAudioUrl = "https://oss/drive.mp3",
        )
        assertNull(t2v.input()["media"], "t2v has no media list; driving audio is ignored in v1")

        val edit = QwenAIService.buildVideoRequestBody(
            GenerationSetup(kind = "video", prompt = "Repaint", videoUrl = "https://oss/base.mp4"),
            "Repaint",
            "videoedit",
            DEFAULT_VIDEO_MODEL_ID,
            drivingAudioUrl = "https://oss/drive.mp3",
        )
        assertEquals(listOf("video"), edit.input().mediaTypes())
        assertTrue(edit.input().drivingAudioUrls().isEmpty())
    }
}
