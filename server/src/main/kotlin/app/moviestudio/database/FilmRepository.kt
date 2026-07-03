package app.moviestudio.database

import app.moviestudio.Film
import com.arangodb.util.RawJson
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object FilmRepository {
    private val logger = LoggerFactory.getLogger(FilmRepository::class.java)
    private const val COLLECTION = "movies"
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = false
    }

    private fun toDoc(film: Film): String {
        val element = json.encodeToJsonElement(Film.serializer(), film) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(film.id)
        return JsonObject(map).toString()
    }

    fun insert(film: Film): Film {
        val doc = toDoc(film)
        ArangoDatabase.db.collection(COLLECTION).insertDocument(RawJson.of(doc))
        return film
    }

    fun getById(id: String): Film? {
        val rawJson = ArangoDatabase.db.collection(COLLECTION).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(Film.serializer(), rawJson.get())
    }

    fun update(film: Film): Film {
        val doc = toDoc(film)
        ArangoDatabase.db.collection(COLLECTION).updateDocument(film.id, RawJson.of(doc))
        return film
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(COLLECTION).deleteDocument(id)
    }

    fun listAll(): List<Film> {
        val query = "FOR f IN $COLLECTION SORT f.createdAt DESC RETURN f"
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java)
        val films = mutableListOf<Film>()
        for (rawJson in cursor) {
            films.add(json.decodeFromString(Film.serializer(), rawJson.get()))
        }
        return films
    }
}
