package app.moviestudio

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCommonTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

    @Test
    fun testBaseUrlAndConfig() {
        assertEquals(BuildConfig.BASE_URL, getBaseUrl())
        // Ensure default URL is localhost:8080 when not overridden or matched
        if (BuildConfig.ENV == "dev") {
            assertEquals("http://localhost:8080", getBaseUrl())
        }
    }

    @Test
    fun testFilmSerialization() {
        val film = Film(
            id = "film-123",
            title = "Awesome Movie",
            totalDuration = 120.5,
            status = FilmStatus.DRAFT,
            createdAt = 1625292000000L
        )
        val json = Json.encodeToString(film)
        val decoded = Json.decodeFromString<Film>(json)
        assertEquals(film, decoded)
    }
}