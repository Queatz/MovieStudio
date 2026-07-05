package app.moviestudio.database

import app.moviestudio.Clip
import com.arangodb.util.RawJson
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object ClipRepository {
    private val logger = LoggerFactory.getLogger(ClipRepository::class.java)
    private const val COLLECTION = "clips"
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = false
    }

    fun validate(clip: Clip) {
        require(clip.timelineStart >= 0f) { "timelineStart must be greater than or equal to 0" }
        require(clip.trimIn >= 0f) { "trimIn must be greater than or equal to 0" }
        require(clip.trimOut >= clip.trimIn) { "trimOut must be greater than or equal to trimIn" }
    }

    private fun toDoc(clip: Clip): String {
        val element = json.encodeToJsonElement(Clip.serializer(), clip) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(clip.id)
        return JsonObject(map).toString()
    }

    fun insert(clip: Clip): Clip {
        validate(clip)
        val doc = toDoc(clip)
        ArangoDatabase.db.collection(COLLECTION).insertDocument(RawJson.of(doc))
        return clip
    }

    fun getById(id: String): Clip? {
        val rawJson = ArangoDatabase.db.collection(COLLECTION).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(Clip.serializer(), rawJson.get())
    }

    fun update(clip: Clip): Clip {
        validate(clip)
        val doc = toDoc(clip)
        ArangoDatabase.db.collection(COLLECTION).updateDocument(clip.id, RawJson.of(doc))
        return clip
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(COLLECTION).deleteDocument(id)
    }

    fun deleteByTrackId(trackId: String) {
        val query = "FOR c IN $COLLECTION FILTER c.trackId == @trackId REMOVE c IN $COLLECTION"
        val bindVars = mapOf("trackId" to trackId)
        ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
    }

    fun queryByTrackId(trackId: String): List<Clip> {
        val query = "FOR c IN $COLLECTION FILTER c.trackId == @trackId RETURN c"
        val bindVars = mapOf("trackId" to trackId)
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val clips = mutableListOf<Clip>()
        for (rawJson in cursor) {
            clips.add(json.decodeFromString(Clip.serializer(), rawJson.get()))
        }
        return clips
    }

    fun queryByTrackIds(trackIds: List<String>): List<Clip> {
        if (trackIds.isEmpty()) return emptyList()
        val query = "FOR c IN $COLLECTION FILTER c.trackId IN @trackIds RETURN c"
        val bindVars = mapOf("trackIds" to trackIds)
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val clips = mutableListOf<Clip>()
        for (rawJson in cursor) {
            clips.add(json.decodeFromString(Clip.serializer(), rawJson.get()))
        }
        return clips
    }

    /** All clips that reference the given asset (used to keep clips in sync when its media changes). */
    fun queryByAssetId(assetId: String): List<Clip> {
        val query = "FOR c IN $COLLECTION FILTER c.assetId == @assetId RETURN c"
        val bindVars = mapOf("assetId" to assetId)
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val clips = mutableListOf<Clip>()
        for (rawJson in cursor) {
            clips.add(json.decodeFromString(Clip.serializer(), rawJson.get()))
        }
        return clips
    }
}
