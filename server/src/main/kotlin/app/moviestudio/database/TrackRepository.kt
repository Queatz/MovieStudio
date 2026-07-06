package app.moviestudio.database

import app.moviestudio.Track
import com.arangodb.util.RawJson
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object TrackRepository {
    private val logger = LoggerFactory.getLogger(TrackRepository::class.java)
    private val COLLECTION = DbCollection.TRACKS
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = false
    }

    private fun toDoc(track: Track): String {
        val element = json.encodeToJsonElement(Track.serializer(), track) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(track.id)
        return JsonObject(map).toString()
    }

    fun insert(track: Track): Track {
        val doc = toDoc(track)
        ArangoDatabase.db.collection(COLLECTION.collectionName).insertDocument(RawJson.of(doc))
        return track
    }

    fun getById(id: String): Track? {
        val rawJson = ArangoDatabase.db.collection(COLLECTION.collectionName).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(Track.serializer(), rawJson.get())
    }

    fun update(track: Track): Track {
        val doc = toDoc(track)
        ArangoDatabase.db.collection(COLLECTION.collectionName).updateDocument(track.id, RawJson.of(doc))
        return track
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(COLLECTION.collectionName).deleteDocument(id)
    }

    fun queryByMovieId(movieId: String): List<Track> {
        val query = "FOR t IN $COLLECTION FILTER t.movieId == @movieId SORT t.zIndex RETURN t"
        val bindVars = mapOf("movieId" to movieId)
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val tracks = mutableListOf<Track>()
        for (rawJson in cursor) {
            tracks.add(json.decodeFromString(Track.serializer(), rawJson.get()))
        }
        return tracks
    }
}
