package app.moviestudio

import app.moviestudio.routing.GenerateTextRequest
import app.moviestudio.service.AIGenerationService
import app.moviestudio.service.QwenAIService
import app.moviestudio.service.foldChatIntoPrompt
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import kotlin.test.*

/**
 * Covers the conversational text-generation flow used by the AI prompt dialogs (lyrics/theme):
 * request decoding with and without a message history, folding a conversation into a single
 * prompt for single-turn backends, and the default [AIGenerationService.generateChat] delegation.
 */
class AiChatGenerationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun restoreRealService() {
        AIGenerationService.setInstance(QwenAIService)
    }

    @Test
    fun legacyPromptOnlyRequestsStillDecode() {
        val request = json.decodeFromString(
            GenerateTextRequest.serializer(),
            """{"prompt":"An upbeat song","movieTitle":"My Movie"}"""
        )
        assertEquals("An upbeat song", request.prompt)
        assertEquals("My Movie", request.movieTitle)
        assertTrue(request.messages.isEmpty())
    }

    @Test
    fun requestCarriesConversationHistory() {
        val request = json.decodeFromString(
            GenerateTextRequest.serializer(),
            """
            {
              "movieTitle": "My Movie",
              "messages": [
                {"role": "user", "content": "An upbeat song"},
                {"role": "assistant", "content": "[verse] La la la"},
                {"role": "user", "content": "Make it slower"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(3, request.messages.size)
        assertEquals(AiChatRole.USER, request.messages[0].role)
        assertEquals(AiChatRole.ASSISTANT, request.messages[1].role)
        assertEquals("Make it slower", request.messages[2].content)
    }

    @Test
    fun foldingASingleMessagePassesItThroughUnchanged() {
        val folded = foldChatIntoPrompt(
            listOf(AiChatMessage(role = AiChatRole.USER, content = "An upbeat song"))
        )
        assertEquals("An upbeat song", folded)
    }

    @Test
    fun foldingAConversationKeepsEveryTurnInOrder() {
        val folded = foldChatIntoPrompt(
            listOf(
                AiChatMessage(role = AiChatRole.USER, content = "An upbeat song"),
                AiChatMessage(role = AiChatRole.ASSISTANT, content = "[verse] La la la"),
                AiChatMessage(role = AiChatRole.USER, content = "Make it slower")
            )
        )
        assertTrue(folded.contains("An upbeat song"))
        assertTrue(folded.contains("[verse] La la la"))
        assertTrue(folded.contains("Make it slower"))
        assertTrue(folded.indexOf("An upbeat song") < folded.indexOf("[verse] La la la"))
        assertTrue(folded.indexOf("[verse] La la la") < folded.indexOf("Make it slower"))
        // The assistant turn is labeled as the AI's previous response, the user turns as "User".
        assertTrue(folded.contains("Your previous response"))
        assertTrue(folded.contains("User:"))
    }

    @Test
    fun defaultGenerateChatFoldsTheHistoryIntoGenerateText() = runBlocking {
        var capturedSystem: String? = null
        var capturedUser: String? = null
        val service = object : AIGenerationService {
            override suspend fun generateTranscript(asset: Asset): Asset = asset
            override suspend fun generateText(system: String, user: String): String {
                capturedSystem = system
                capturedUser = user
                return "generated"
            }
            override suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone =
                fail("not expected")
            override suspend fun executeAiGenerationJob(
                job: Job,
                onProgress: suspend (progress: Int, message: String) -> Unit
            ) = fail("not expected")
        }
        val reply = service.generateChat(
            system = "You are a music supervisor.",
            messages = listOf(
                AiChatMessage(role = AiChatRole.USER, content = "A soundtrack theme"),
                AiChatMessage(role = AiChatRole.ASSISTANT, content = "Uplifting electro-pop"),
                AiChatMessage(role = AiChatRole.USER, content = "Darker please")
            )
        )
        assertEquals("generated", reply)
        assertEquals("You are a music supervisor.", capturedSystem)
        val folded = capturedUser ?: fail("generateText was not called")
        assertTrue(folded.contains("A soundtrack theme"))
        assertTrue(folded.contains("Uplifting electro-pop"))
        assertTrue(folded.contains("Darker please"))
    }

    /** A recording stand-in for the AI service so endpoint tests stay deterministic/offline. */
    private class RecordingService : AIGenerationService {
        var capturedSystem: String? = null
        var capturedMessages: List<AiChatMessage>? = null
        override suspend fun generateChat(system: String, messages: List<AiChatMessage>): String {
            capturedSystem = system
            capturedMessages = messages
            return "canned response"
        }
        override suspend fun generateTranscript(asset: Asset): Asset = asset
        override suspend fun generateText(system: String, user: String): String = "canned response"
        override suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone =
            throw UnsupportedOperationException()
        override suspend fun executeAiGenerationJob(
            job: Job,
            onProgress: suspend (progress: Int, message: String) -> Unit
        ) = throw UnsupportedOperationException()
    }

    @Test
    fun themeEndpointForwardsTheConversationToTheAi() = testApplication {
        application { module() }
        // The module installs the real service on startup, so boot first, then swap in the fake.
        startApplication()
        val recording = RecordingService()
        AIGenerationService.setInstance(recording)

        val response = client.post("/api/generate/theme") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "movieTitle": "My Movie",
                  "messages": [
                    {"role": "user", "content": "A soundtrack theme"},
                    {"role": "assistant", "content": "Uplifting electro-pop"},
                    {"role": "user", "content": "Darker please"}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = json.parseToJsonElement(response.bodyAsText())
            .jsonObject["text"]?.jsonPrimitive?.content
        assertEquals("canned response", text)

        val messages = recording.capturedMessages ?: fail("generateChat was not called")
        assertEquals(3, messages.size)
        assertEquals("Darker please", messages.last().content)
        assertTrue(recording.capturedSystem!!.contains("THEME"))
    }

    @Test
    fun lyricsEndpointStillAcceptsLegacySinglePromptRequests() = testApplication {
        application { module() }
        // The module installs the real service on startup, so boot first, then swap in the fake.
        startApplication()
        val recording = RecordingService()
        AIGenerationService.setInstance(recording)

        val response = client.post("/api/generate/lyrics") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "An upbeat song", "movieTitle": "My Movie"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val messages = recording.capturedMessages ?: fail("generateChat was not called")
        assertEquals(1, messages.size)
        assertEquals(AiChatRole.USER, messages.single().role)
        assertEquals("An upbeat song", messages.single().content)
        assertTrue(recording.capturedSystem!!.contains("LYRIC"))
    }
}
