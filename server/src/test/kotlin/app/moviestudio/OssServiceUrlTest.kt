package app.moviestudio

import app.moviestudio.storage.OssService
import kotlin.test.*

class OssServiceUrlTest {

    private val endpointHost = OssService.endpoint.removePrefix("http://").removePrefix("https://").removeSuffix("/")

    @Test
    fun objectKeyFromVirtualHostedUrlIgnoresSignedQuery() {
        val url = "https://${OssService.bucketName}.$endpointHost/uploads/image.png?Expires=1&Signature=old"

        assertEquals("uploads/image.png", OssService.objectKeyFromUrl(url))
    }

    @Test
    fun objectKeyFromPathStyleUrlIgnoresBucketPrefixAndSignedQuery() {
        val url = "https://$endpointHost/${OssService.bucketName}/uploads/image.png?Expires=1&Signature=old"

        assertEquals("uploads/image.png", OssService.objectKeyFromUrl(url))
    }

    @Test
    fun freshDownloadUrlLeavesExternalUrlsUntouched() {
        val url = "https://cdn.example.com/uploads/image.png?Expires=1"

        assertEquals(url, OssService.freshDownloadUrl(url))
    }
}