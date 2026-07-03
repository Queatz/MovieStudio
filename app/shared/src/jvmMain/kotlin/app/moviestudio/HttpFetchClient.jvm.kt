package app.moviestudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class JvmHttpFetchClient : HttpFetchClient {
    private suspend fun request(method: String, urlString: String, body: String?): String = withContext(Dispatchers.IO) {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }
        }
        val responseCode = conn.responseCode
        if (responseCode in 200..299) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val response = reader.readText()
            reader.close()
            response
        } else {
            val reader = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8))
            val errorResponse = reader.readText()
            reader.close()
            throw Exception("HTTP $responseCode: $errorResponse")
        }
    }

    override suspend fun get(url: String): String = request("GET", url, null)
    override suspend fun post(url: String, body: String?): String = request("POST", url, body)
    override suspend fun put(url: String, body: String?): String = request("PUT", url, body)
    override suspend fun delete(url: String): String = request("DELETE", url, null)
}

actual fun getHttpFetchClient(): HttpFetchClient = JvmHttpFetchClient()

actual fun getBaseUrl(): String = BuildConfig.BASE_URL
