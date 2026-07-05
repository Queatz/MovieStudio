package app.moviestudio.database

import app.moviestudio.Character
import app.moviestudio.RenderRecord
import app.moviestudio.Scene
import app.moviestudio.Tip
import app.moviestudio.VoiceClone
import com.arangodb.util.RawJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Small generic ArangoDB repository used by the flat "library" collections (characters, scenes,
 * voice clones, render records). Documents are stored with `_key` = model id, mirroring the
 * hand-written repositories for the core movie entities.
 */
open class SimpleCrudRepository<T>(
    private val collection: String,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String
) {
    protected val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private fun toDoc(entity: T): String {
        val element = json.encodeToJsonElement(serializer, entity) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(idOf(entity))
        return JsonObject(map).toString()
    }

    fun insert(entity: T): T {
        ArangoDatabase.db.collection(collection).insertDocument(RawJson.of(toDoc(entity)))
        return entity
    }

    fun getById(id: String): T? {
        val rawJson = ArangoDatabase.db.collection(collection).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(serializer, rawJson.get())
    }

    fun update(entity: T): T {
        ArangoDatabase.db.collection(collection).updateDocument(idOf(entity), RawJson.of(toDoc(entity)))
        return entity
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(collection).deleteDocument(id)
    }

    fun listAll(): List<T> {
        val query = "FOR d IN $collection SORT d.read DESC, d.createdAt DESC RETURN d"
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java)
        val items = mutableListOf<T>()
        for (rawJson in cursor) {
            items.add(json.decodeFromString(serializer, rawJson.get()))
        }
        return items
    }

    protected fun queryByField(field: String, value: String): List<T> {
        val query = "FOR d IN $collection FILTER d.$field == @value SORT d.createdAt DESC RETURN d"
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, mapOf("value" to value))
        val items = mutableListOf<T>()
        for (rawJson in cursor) {
            items.add(json.decodeFromString(serializer, rawJson.get()))
        }
        return items
    }
}

object CharacterRepository : SimpleCrudRepository<Character>("characters", Character.serializer(), { it.id })

object SceneRepository : SimpleCrudRepository<Scene>("scenes", Scene.serializer(), { it.id })

object VoiceCloneRepository : SimpleCrudRepository<VoiceClone>("voiceclones", VoiceClone.serializer(), { it.id })

object RenderRepository : SimpleCrudRepository<RenderRecord>("renders", RenderRecord.serializer(), { it.id }) {
    fun queryByMovieId(movieId: String): List<RenderRecord> = queryByField("movieId", movieId)
}

object TipRepository : SimpleCrudRepository<Tip>("tips", Tip.serializer(), { it.id }) {
    /**
     * Tips whose title or content contains [query] (case-insensitive), newest first. A blank
     * query returns all tips. Uses an AQL LIKE with the term wrapped in wildcards.
     */
    fun search(query: String): List<Tip> {
        val term = query.trim()
        if (term.isEmpty()) return listAll()
        val aql = """
            FOR d IN tips
                FILTER LIKE(LOWER(d.title), @term, true) OR LIKE(LOWER(d.content), @term, true)
                SORT d.read DESC, d.createdAt DESC
                RETURN d
        """.trimIndent()
        val bindVars = mapOf<String, Any>("term" to "%${term.lowercase()}%")
        val cursor = ArangoDatabase.db.query(aql, RawJson::class.java, bindVars)
        val items = mutableListOf<Tip>()
        for (rawJson in cursor) {
            items.add(json.decodeFromString(Tip.serializer(), rawJson.get()))
        }
        return items
    }
}
