package app.moviestudio.database

import app.moviestudio.MovieDocument
import com.arangodb.util.RawJson
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object DocumentRepository {
    private val logger = LoggerFactory.getLogger(DocumentRepository::class.java)
    private val COLLECTION = DbCollection.DOCUMENTS
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun validate(document: MovieDocument) {
        require(document.title.isNotBlank()) { "Document title must not be blank" }
        require(document.parentId != document.id) { "A document cannot be its own parent" }
    }

    private fun toDoc(document: MovieDocument): String {
        val element = json.encodeToJsonElement(MovieDocument.serializer(), document) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(document.id)
        return JsonObject(map).toString()
    }

    fun insert(document: MovieDocument): MovieDocument {
        validate(document)
        val doc = toDoc(document)
        ArangoDatabase.db.collection(COLLECTION.collectionName).insertDocument(RawJson.of(doc))
        return document
    }

    fun getById(id: String): MovieDocument? {
        val rawJson = ArangoDatabase.db.collection(COLLECTION.collectionName).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(MovieDocument.serializer(), rawJson.get())
    }

    fun update(document: MovieDocument): MovieDocument {
        validate(document)
        val doc = toDoc(document)
        // replaceDocument (not update) so cleared fields like parentId = null are persisted.
        ArangoDatabase.db.collection(COLLECTION.collectionName).replaceDocument(document.id, RawJson.of(doc))
        return document
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(COLLECTION.collectionName).deleteDocument(id)
    }

    fun deleteByMovieId(movieId: String) {
        val query = "FOR d IN $COLLECTION FILTER d.movieId == @movieId REMOVE d IN $COLLECTION"
        val bindVars = mapOf("movieId" to movieId)
        ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
    }

    fun queryByMovieId(movieId: String): List<MovieDocument> {
        val query = "FOR d IN $COLLECTION FILTER d.movieId == @movieId SORT d.sortIndex, d.createdAt RETURN d"
        val bindVars = mapOf("movieId" to movieId)
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val documents = mutableListOf<MovieDocument>()
        for (rawJson in cursor) {
            documents.add(json.decodeFromString(MovieDocument.serializer(), rawJson.get()))
        }
        return documents
    }
}
