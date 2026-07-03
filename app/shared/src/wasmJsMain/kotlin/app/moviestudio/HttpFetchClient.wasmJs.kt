package app.moviestudio

import kotlin.js.Promise
import kotlinx.coroutines.await

@JsFun("""
async (method, url, body) => {
    try {
        const options = {
            method: method,
            headers: {
                'Content-Type': 'application/json'
            }
        };
        if (body) {
            options.body = body;
        }
        const response = await fetch(url, options);
        if (!response.ok) {
            throw new Error('HTTP ' + response.status + ': ' + await response.text());
        }
        return await response.text();
    } catch (e) {
        throw e;
    }
}
""")
private external fun fetchPromise(method: String, url: String, body: String?): Promise<JsAny>

@JsFun("(jsString) => jsString")
private external fun jsStringToString(jsString: JsAny): String

class WasmHttpFetchClient : HttpFetchClient {
    override suspend fun get(url: String): String = jsStringToString(fetchPromise("GET", url, null).await())
    override suspend fun post(url: String, body: String?): String = jsStringToString(fetchPromise("POST", url, body).await())
    override suspend fun put(url: String, body: String?): String = jsStringToString(fetchPromise("PUT", url, body).await())
    override suspend fun delete(url: String): String = jsStringToString(fetchPromise("DELETE", url, null).await())
}

actual fun getHttpFetchClient(): HttpFetchClient = WasmHttpFetchClient()

actual fun getBaseUrl(): String = BuildConfig.BASE_URL
