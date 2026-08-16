package app.moviestudio

import app.moviestudio.service.QwenAIService
import app.moviestudio.service.QwenConfig
import app.moviestudio.storage.OssService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class QwenImageRequestTest {

    private fun JsonObject.messages(): JsonArray = getValue("input").jsonObject.getValue("messages").jsonArray

    private fun JsonObject.firstContent(): JsonArray = messages().first().jsonObject.getValue("content").jsonArray

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun ossUrl(objectKey: String): String {
        val endpointHost = OssService.endpoint.removePrefix("http://").removePrefix("https://").removeSuffix("/")
        return "https://${OssService.bucketName}.$endpointHost/$objectKey?Expires=1&Signature=old"
    }

    @Test
    fun textToImageSendsOnlyTextContent() {
        val setup = GenerationSetup(kind = "image", prompt = "A moonlit street", resolution = "1024x768")

        val body = QwenAIService.buildImageRequestBody(setup, DEFAULT_IMAGE_MODEL_ID)
        val content = body.firstContent()

        assertEquals("A moonlit street", content.single().jsonObject.str("text"))
        assertEquals("1024*768", body.getValue("parameters").jsonObject.str("size"))
    }

    @Test
    fun imageEditRefreshesOwnOssImageUrlBeforeSendingToQwen() {
        val objectKey = "uploads/base-image.png"
        val staleUrl = ossUrl(objectKey)
        val setup = GenerationSetup(kind = "image", prompt = "Restyle", imageUrl = staleUrl)

        val body = QwenAIService.buildImageRequestBody(setup, DEFAULT_IMAGE_MODEL_ID)
        val content = body.firstContent()
        val imageUrl = content.first().jsonObject.str("image")

        assertEquals(objectKey, imageUrl?.let(OssService::objectKeyFromUrl))
        assertNotEquals(staleUrl, imageUrl)
        assertEquals("Restyle", content.last().jsonObject.str("text"))
    }

    @Test
    fun r2iSendsRefImagesAndRefreshesTheirUrls() {
        val staleUrl1 = ossUrl("uploads/ref1.png")
        val staleUrl2 = ossUrl("uploads/ref2.png")
        val setup = GenerationSetup(
            kind = "image",
            prompt = "Create a style mix",
            referenceImages = listOf(staleUrl1, staleUrl2)
        )

        val body = QwenAIService.buildImageRequestBody(setup, DEFAULT_IMAGE_MODEL_ID)
        val input = body.getValue("input").jsonObject
        val refImages = input.getValue("ref_images").jsonArray

        assertEquals(2, refImages.size)
        val objectKey1 = OssService.objectKeyFromUrl(refImages[0].jsonPrimitive.content)
        val objectKey2 = OssService.objectKeyFromUrl(refImages[1].jsonPrimitive.content)
        assertEquals("uploads/ref1.png", objectKey1)
        assertEquals("uploads/ref2.png", objectKey2)
        assertNotEquals(staleUrl1, refImages[0].jsonPrimitive.content)
        assertNotEquals(staleUrl2, refImages[1].jsonPrimitive.content)
    }

    @Test
    fun resolveImageModelHonorsUserSelectionWhenNoReferences() {
        val setup = GenerationSetup(kind = "image", model = "wan3.0-image-pro")
        val resolved = QwenAIService.resolveImageModel(setup, isEdit = false)
        assertEquals("wan3.0-image-pro", resolved)
    }

    @Test
    fun resolveImageModelForcesQwenWhenReferencesArePresent() {
        // Direct reference images
        val setupWithRefs = GenerationSetup(kind = "image", model = "wan3.0-image-pro", referenceImages = listOf("http://foo.com/img.png"))
        val resolvedWithRefs = QwenAIService.resolveImageModel(setupWithRefs, isEdit = false)
        assertEquals(QwenConfig.imageModel, resolvedWithRefs)

        // Character reference
        val setupWithChars = GenerationSetup(kind = "image", model = "wan3.0-image-pro", characterIds = listOf("char123"))
        val resolvedWithChars = QwenAIService.resolveImageModel(setupWithChars, isEdit = false)
        assertEquals(QwenConfig.imageModel, resolvedWithChars)

        // Scene reference
        val setupWithScenes = GenerationSetup(kind = "image", model = "wan3.0-image-pro", sceneIds = listOf("scene123"))
        val resolvedWithScenes = QwenAIService.resolveImageModel(setupWithScenes, isEdit = false)
        assertEquals(QwenConfig.imageModel, resolvedWithScenes)
    }
}