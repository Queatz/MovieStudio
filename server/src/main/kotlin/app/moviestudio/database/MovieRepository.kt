package app.moviestudio.database

import app.moviestudio.Movie
import com.arangodb.util.RawJson
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object MovieRepository {
    private val logger = LoggerFactory.getLogger(MovieRepository::class.java)
    private val COLLECTION = DbCollection.MOVIES
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = false
    }

    private fun toDoc(movie: Movie): String {
        val element = json.encodeToJsonElement(Movie.serializer(), movie) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(movie.id)
        return JsonObject(map).toString()
    }

    fun insert(movie: Movie): Movie {
        val doc = toDoc(movie)
        ArangoDatabase.db.collection(COLLECTION.collectionName).insertDocument(RawJson.of(doc))
        return movie
    }

    fun getById(id: String): Movie? {
        val rawJson = ArangoDatabase.db.collection(COLLECTION.collectionName).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(Movie.serializer(), rawJson.get())
    }

    fun update(movie: Movie): Movie {
        val doc = toDoc(movie)
        // replaceDocument (not update) so fields reset to their default are persisted. With a
        // partial update, defaults omitted by the serializer (encodeDefaults = false) — e.g. the
        // aspectRatio switched back to "16:9" — would be merge-kept at their old value, silently
        // reverting the user's change.
        ArangoDatabase.db.collection(COLLECTION.collectionName).replaceDocument(movie.id, RawJson.of(doc))
        return movie
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(COLLECTION.collectionName).deleteDocument(id)
    }

    fun listAll(): List<Movie> {
        val query = "FOR f IN $COLLECTION SORT f.createdAt DESC RETURN f"
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java)
        val movies = mutableListOf<Movie>()
        for (rawJson in cursor) {
            movies.add(json.decodeFromString(Movie.serializer(), rawJson.get()))
        }
        return movies
    }
}
