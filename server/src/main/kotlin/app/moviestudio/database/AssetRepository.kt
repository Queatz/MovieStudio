package app.moviestudio.database

import app.moviestudio.Asset
import app.moviestudio.AssetType
import com.arangodb.util.RawJson
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object AssetRepository {
    private val logger = LoggerFactory.getLogger(AssetRepository::class.java)
    private val COLLECTION = DbCollection.ASSETS
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = false
    }

    private fun toDoc(asset: Asset): String {
        val element = json.encodeToJsonElement(Asset.serializer(), asset) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(asset.id)
        return JsonObject(map).toString()
    }

    fun insert(asset: Asset): Asset {
        val doc = toDoc(asset)
        ArangoDatabase.db.collection(COLLECTION.collectionName).insertDocument(RawJson.of(doc))
        return asset
    }

    fun getById(id: String): Asset? {
        val rawJson = ArangoDatabase.db.collection(COLLECTION.collectionName).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(Asset.serializer(), rawJson.get())
    }

    fun update(asset: Asset): Asset {
        val doc = toDoc(asset)
        ArangoDatabase.db.collection(COLLECTION.collectionName).updateDocument(asset.id, RawJson.of(doc))
        return asset
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(COLLECTION.collectionName).deleteDocument(id)
    }

    fun queryByMovieId(movieId: String?): List<Asset> {
        val query = if (movieId == null) {
            "FOR a IN $COLLECTION FILTER a.movieId == null RETURN a"
        } else {
            "FOR a IN $COLLECTION FILTER a.movieId == @movieId RETURN a"
        }
        val bindVars = mutableMapOf<String, Any>()
        if (movieId != null) {
            bindVars["movieId"] = movieId
        }
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val assets = mutableListOf<Asset>()
        for (rawJson in cursor) {
            assets.add(json.decodeFromString(Asset.serializer(), rawJson.get()))
        }
        return assets
    }

    fun queryLibrary(movieId: String?, type: AssetType?, tags: List<String>?): List<Asset> {
        val queryBuilder = StringBuilder("FOR a IN $COLLECTION ")
        val filters = mutableListOf<String>()
        val bindVars = mutableMapOf<String, Any>()

        if (movieId != null) {
            if (movieId == "global" || movieId == "null") {
                filters.add("a.movieId == null")
            } else {
                filters.add("a.movieId == @movieId")
                bindVars["movieId"] = movieId
            }
        }
        if (type != null) {
            filters.add("a.type == @type")
            bindVars["type"] = type.name
        }
        if (!tags.isNullOrEmpty()) {
            filters.add("LENGTH(INTERSECTION(a.tags, @tags)) > 0")
            bindVars["tags"] = tags
        }

        if (filters.isNotEmpty()) {
            queryBuilder.append("FILTER ")
            queryBuilder.append(filters.joinToString(" AND "))
        }
        queryBuilder.append(" SORT a.createdAt DESC RETURN a")

        val cursor = ArangoDatabase.db.query(queryBuilder.toString(), RawJson::class.java, bindVars)
        val assets = mutableListOf<Asset>()
        for (rawJson in cursor) {
            assets.add(json.decodeFromString(Asset.serializer(), rawJson.get()))
        }
        return assets
    }
}
