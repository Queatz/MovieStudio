package app.moviestudio.database

import app.moviestudio.TimelineNote
import com.arangodb.util.RawJson
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object NoteRepository {
    private val logger = LoggerFactory.getLogger(NoteRepository::class.java)
    private const val COLLECTION = "notes"
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = false
    }

    fun validate(note: TimelineNote) {
        require(note.atSeconds >= 0.0) { "atSeconds must be greater than or equal to 0" }
        require(note.text.isNotBlank()) { "Note text must not be blank" }
    }

    private fun toDoc(note: TimelineNote): String {
        val element = json.encodeToJsonElement(TimelineNote.serializer(), note) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(note.id)
        return JsonObject(map).toString()
    }

    fun insert(note: TimelineNote): TimelineNote {
        validate(note)
        val doc = toDoc(note)
        ArangoDatabase.db.collection(COLLECTION).insertDocument(RawJson.of(doc))
        return note
    }

    fun getById(id: String): TimelineNote? {
        val rawJson = ArangoDatabase.db.collection(COLLECTION).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(TimelineNote.serializer(), rawJson.get())
    }

    fun update(note: TimelineNote): TimelineNote {
        validate(note)
        val doc = toDoc(note)
        ArangoDatabase.db.collection(COLLECTION).updateDocument(note.id, RawJson.of(doc))
        return note
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(COLLECTION).deleteDocument(id)
    }

    fun deleteByMovieId(movieId: String) {
        val query = "FOR n IN $COLLECTION FILTER n.movieId == @movieId REMOVE n IN $COLLECTION"
        val bindVars = mapOf("movieId" to movieId)
        ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
    }

    fun queryByMovieId(movieId: String): List<TimelineNote> {
        val query = "FOR n IN $COLLECTION FILTER n.movieId == @movieId SORT n.atSeconds RETURN n"
        val bindVars = mapOf("movieId" to movieId)
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val notes = mutableListOf<TimelineNote>()
        for (rawJson in cursor) {
            notes.add(json.decodeFromString(TimelineNote.serializer(), rawJson.get()))
        }
        return notes
    }
}
