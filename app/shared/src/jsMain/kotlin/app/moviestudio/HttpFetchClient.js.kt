package app.moviestudio

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.fetch.RequestInit

class JsHttpFetchClient : HttpFetchClient {
    private suspend fun request(method: String, url: String, body: String?): String {
        val headers = js("({ 'Content-Type': 'application/json' })")
        val options = js("({ method: method, headers: headers })")
        if (body != null) {
            options.body = body
        }
        val response = window.fetch(url, options.unsafeCast<RequestInit>()).await()
        if (!response.ok) {
            throw Exception("HTTP ${response.status}")
        }
        return response.text().await()
    }

    override suspend fun get(url: String): String = request("GET", url, null)
    override suspend fun post(url: String, body: String?): String = request("POST", url, body)
    override suspend fun put(url: String, body: String?): String = request("PUT", url, body)
    override suspend fun delete(url: String): String = request("DELETE", url, null)
}

actual fun getHttpFetchClient(): HttpFetchClient = JsHttpFetchClient()

actual fun getBaseUrl(): String = BuildConfig.BASE_URL
