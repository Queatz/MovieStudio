package app.moviestudio.database

import app.moviestudio.Job
import app.moviestudio.JobStatus
import com.arangodb.util.RawJson
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object JobRepository {
    private val logger = LoggerFactory.getLogger(JobRepository::class.java)
    private const val COLLECTION = "jobs"
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = false
    }

    private fun toDoc(job: Job): String {
        val element = json.encodeToJsonElement(Job.serializer(), job) as JsonObject
        val map = element.toMutableMap()
        map["_key"] = JsonPrimitive(job.id)
        return JsonObject(map).toString()
    }

    fun insert(job: Job): Job {
        val doc = toDoc(job)
        ArangoDatabase.db.collection(COLLECTION).insertDocument(RawJson.of(doc))
        return job
    }

    fun getById(id: String): Job? {
        val rawJson = ArangoDatabase.db.collection(COLLECTION).getDocument(id, RawJson::class.java) ?: return null
        return json.decodeFromString(Job.serializer(), rawJson.get())
    }

    fun update(job: Job): Job {
        val doc = toDoc(job)
        ArangoDatabase.db.collection(COLLECTION).updateDocument(job.id, RawJson.of(doc))
        return job
    }

    fun delete(id: String) {
        ArangoDatabase.db.collection(COLLECTION).deleteDocument(id)
    }

    fun pollPendingJobs(): List<Job> {
        val query = "FOR j IN $COLLECTION FILTER j.status == 'PENDING' RETURN j"
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java)
        val jobs = mutableListOf<Job>()
        for (rawJson in cursor) {
            jobs.add(json.decodeFromString(Job.serializer(), rawJson.get()))
        }
        return jobs
    }

    /**
     * Lists jobs for the background-generations panel: newest first, optionally restricted to a
     * movie. When [activeOnly] is set, only PENDING/RUNNING jobs are returned; [includeFailed]
     * additionally keeps FAILED jobs in that listing so the user can retry or dismiss them.
     */
    fun queryJobs(movieId: String?, activeOnly: Boolean, includeFailed: Boolean = false, limit: Int = 50): List<Job> {
        val filters = mutableListOf<String>()
        val bindVars = mutableMapOf<String, Any>()
        if (movieId != null) {
            filters.add("j.movieId == @movieId")
            bindVars["movieId"] = movieId
        }
        if (activeOnly) {
            if (includeFailed) {
                filters.add("(j.status == 'PENDING' OR j.status == 'RUNNING' OR j.status == 'FAILED')")
            } else {
                filters.add("(j.status == 'PENDING' OR j.status == 'RUNNING')")
            }
        }
        val filterClause = if (filters.isEmpty()) "" else "FILTER " + filters.joinToString(" AND ") + " "
        val query = "FOR j IN $COLLECTION $filterClause SORT j.createdAt DESC LIMIT @limit RETURN j"
        bindVars["limit"] = limit
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        val jobs = mutableListOf<Job>()
        for (rawJson in cursor) {
            jobs.add(json.decodeFromString(Job.serializer(), rawJson.get()))
        }
        return jobs
    }

    fun lockJobToRunning(jobId: String): Job? {
        val query = """
            FOR j IN $COLLECTION
            FILTER j._key == @jobId AND j.status == 'PENDING'
            UPDATE j WITH { status: 'RUNNING' } IN $COLLECTION
            RETURN NEW
        """.trimIndent()
        val bindVars = mapOf("jobId" to jobId)
        val cursor = ArangoDatabase.db.query(query, RawJson::class.java, bindVars)
        if (cursor.hasNext()) {
            val rawJson = cursor.next()
            return json.decodeFromString(Job.serializer(), rawJson.get())
        }
        return null
    }
}
