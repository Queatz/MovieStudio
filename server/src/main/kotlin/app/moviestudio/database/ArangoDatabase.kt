package app.moviestudio.database

import app.moviestudio.config.Env
import com.arangodb.ArangoDB
import com.arangodb.ArangoDatabase as ArangoDbInstance
import org.slf4j.LoggerFactory

object ArangoDatabase {
    private val logger = LoggerFactory.getLogger(ArangoDatabase::class.java)

    val host = Env.get("ARANGO_HOST", "127.0.0.1")
    val port = Env.get("ARANGO_PORT", "8539").toInt()
    val user = Env.get("ARANGO_USER", "root")
    val password = Env.get("ARANGO_PASSWORD", "password")
    val dbName = Env.get("ARANGO_DB_NAME", "moviestudio")

    val client: ArangoDB by lazy {
        ArangoDB.Builder()
            .host(host, port)
            .user(user)
            .password(password)
            .build()
    }

    val db: ArangoDbInstance by lazy {
        client.db(dbName)
    }

    fun init() {
        try {
            logger.info("Initializing ArangoDB connection to {}:{}", host, port)
            if (!client.databases.contains(dbName)) {
                logger.info("Creating database: {}", dbName)
                client.createDatabase(dbName)
            }

            val collections = listOf(
                "movies",
                "assets",
                "tracks",
                "clips",
                "jobs",
                "characters",
                "scenes",
                "voiceclones",
                "renders",
                "notes"
            )
            for (col in collections) {
                val collection = db.collection(col)
                if (!collection.exists()) {
                    logger.info("Creating collection: {}", col)
                    db.createCollection(col)
                }
            }
            logger.info("ArangoDB initialization complete.")
        } catch (e: Exception) {
            logger.error("Failed to initialize ArangoDB", e)
            throw e
        }
    }
}
