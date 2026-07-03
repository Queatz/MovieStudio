package app.moviestudio

interface HttpFetchClient {
    suspend fun get(url: String): String
    suspend fun post(url: String, body: String? = null): String
    suspend fun put(url: String, body: String? = null): String
    suspend fun delete(url: String): String
}

expect fun getHttpFetchClient(): HttpFetchClient

expect fun getBaseUrl(): String
