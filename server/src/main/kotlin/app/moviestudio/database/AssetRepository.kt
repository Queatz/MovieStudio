package app.moviestudio.database

import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.LibraryPage
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

    /**
     * Paged library query. Optional [movieId]/[type]/[tags] narrow the set; [q] does a
     * case-insensitive substring match on the asset description. When [limit] is null every
     * matching asset is returned in a single page (used by the full-library refresh).
     */
    fun queryLibrary(
        movieId: String?,
        type: AssetType?,
        tags: List<String>?,
        q: String? = null,
        offset: Int = 0,
        limit: Int? = null,
    ): LibraryPage {
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
        val term = q?.trim().orEmpty()
        if (term.isNotEmpty()) {
            // Case-insensitive substring match on the user-facing description.
            filters.add("LIKE(LOWER(a.description), @term, true)")
            bindVars["term"] = "%${term.lowercase()}%"
        }

        val filterClause = if (filters.isEmpty()) "" else "FILTER " + filters.joinToString(" AND ") + " "
        val safeOffset = offset.coerceAtLeast(0)
        // null limit = unbounded page (full library load). Cap absurd values if a limit is given.
        val safeLimit = limit?.coerceIn(1, 500)

        val query = if (safeLimit == null) {
            """
                LET filtered = (
                    FOR a IN $COLLECTION
                        $filterClause
                        SORT a.createdAt DESC
                        RETURN a
                )
                RETURN { items: filtered, total: LENGTH(filtered) }
            """.trimIndent()
        } else {
            bindVars["offset"] = safeOffset
            bindVars["limit"] = safeLimit
            """
                LET filtered = (
                    FOR a IN $COLLECTION
                        $filterClause
                        SORT a.createdAt DESC
                        RETURN a
                )
                RETURN {
                    items: SLICE(filtered, @offset, @limit),
                    total: LENGTH(filtered)
                }
            """.trimIndent()
        }

        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val raw = cursor.firstOrNull()?.get() ?: return LibraryPage(offset = safeOffset, limit = safeLimit ?: 0)
        val obj = json.parseToJsonElement(raw).jsonObject
        val items = obj["items"]?.jsonArray?.map { element ->
            json.decodeFromJsonElement(Asset.serializer(), element)
        } ?: emptyList()
        val total = obj["total"]?.jsonPrimitive?.intOrNull ?: items.size
        val effectiveLimit = safeLimit ?: items.size
        return LibraryPage(
            items = items,
            total = total,
            offset = safeOffset,
            limit = effectiveLimit,
            hasMore = safeOffset + items.size < total,
        )
    }
}
