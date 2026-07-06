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

    /**
     * True when the current JVM is executing our test suite. Tests truncate whole collections in
     * [setUp]/[tearDown], so they must NEVER touch the production database. We detect a test run in
     * two runner-independent ways:
     *  - the `app.moviestudio.testDatabase` system property set by the Gradle `test` task, and
     *  - the presence of JUnit on the classpath (test-only dependency, absent in production),
     * which also covers running individual tests straight from the IDE.
     */
    private val runningUnderTest: Boolean by lazy {
        System.getProperty("app.moviestudio.testDatabase") == "true" ||
            runCatching { Class.forName("org.junit.Test") }.isSuccess
    }

    /**
     * The database the server talks to. During tests this is a dedicated, isolated database
     * (default `<main>_test`, overridable via `ARANGO_TEST_DB_NAME`) so the main `moviestudio`
     * database is never read from or wiped by a test run.
     */
    val dbName: String by lazy {
        val configured = Env.get("ARANGO_DB_NAME", "moviestudio")
        if (runningUnderTest) Env.get("ARANGO_TEST_DB_NAME", "${configured}_test") else configured
    }

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

            for (col in DbCollection.entries) {
                val collection = db.collection(col.collectionName)
                if (!collection.exists()) {
                    logger.info("Creating collection: {}", col.collectionName)
                    db.createCollection(col.collectionName)
                }
            }
            logger.info("ArangoDB initialization complete.")
        } catch (e: Exception) {
            logger.error("Failed to initialize ArangoDB", e)
            throw e
        }
    }
}
