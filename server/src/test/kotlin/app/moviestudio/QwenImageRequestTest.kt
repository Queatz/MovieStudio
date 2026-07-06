package app.moviestudio

import app.moviestudio.service.QwenAIService
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

        val body = QwenAIService.buildImageRequestBody(setup, "qwen-image-max")
        val content = body.firstContent()

        assertEquals("A moonlit street", content.single().jsonObject.str("text"))
        assertEquals("1024*768", body.getValue("parameters").jsonObject.str("size"))
    }

    @Test
    fun imageEditRefreshesOwnOssImageUrlBeforeSendingToQwen() {
        val objectKey = "uploads/base-image.png"
        val staleUrl = ossUrl(objectKey)
        val setup = GenerationSetup(kind = "image", prompt = "Restyle", imageUrl = staleUrl)

        val body = QwenAIService.buildImageRequestBody(setup, "qwen-image-edit-max-2026-01-16")
        val content = body.firstContent()
        val imageUrl = content.first().jsonObject.str("image")

        assertEquals(objectKey, imageUrl?.let(OssService::objectKeyFromUrl))
        assertNotEquals(staleUrl, imageUrl)
        assertEquals("Restyle", content.last().jsonObject.str("text"))
    }
}