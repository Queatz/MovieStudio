package app.moviestudio.storage

import app.moviestudio.config.Env
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.HttpMethod
import com.aliyun.oss.model.GeneratePresignedUrlRequest
import com.aliyun.oss.model.ObjectMetadata
import com.aliyun.oss.model.PutObjectRequest
import org.slf4j.LoggerFactory
import java.util.Date

object OssService {
    private val logger = LoggerFactory.getLogger(OssService::class.java)

    val endpoint = Env.get("OSS_ENDPOINT", "oss-cn-hangzhou.aliyuncs.com")
    val bucketName = Env.get("OSS_BUCKET_NAME", "movie-studio-bucket")
    val accessKeyId = Env.get("OSS_ACCESS_KEY_ID", "mock-access-key-id")
    val accessKeySecret = Env.get("OSS_ACCESS_KEY_SECRET", "mock-access-key-secret")

    // Only talk to OSS when real credentials are present. Without them (dev/CI), the SDK would
    // block on connection retries before failing, so we skip the network and return the URL.
    private val isConfigured: Boolean =
        accessKeyId.isNotBlank() && accessKeyId != "mock-access-key-id" &&
            accessKeySecret.isNotBlank() && accessKeySecret != "mock-access-key-secret"

    // Objects are stored privately (the bucket has "block public access" enabled, so public-read
    // ACLs are rejected). We hand out long-lived pre-signed GET URLs, signed with the configured
    // access key, so the browser can read the object without a public ACL.
    private const val DOWNLOAD_URL_TTL_MILLIS = 3600L * 1000L * 24L * 365L * 10L // ~10 years

    /** The plain (unsigned) URL of an object. Only used for the unconfigured dev/CI mock path. */
    private fun publicUrl(objectKey: String): String {
        val endpointClean = endpoint.removePrefix("http://").removePrefix("https://")
        return "https://$bucketName.$endpointClean/$objectKey"
    }

    /**
     * A pre-signed GET URL for reading a private object. This grants temporary, credential-signed
     * read access without requiring a public ACL, which the bucket forbids ("Put public object acl
     * is not allowed"). The TTL is long so persisted asset URLs keep working.
     */
    private fun signedGetUrl(objectKey: String): String {
        val expiration = Date(System.currentTimeMillis() + DOWNLOAD_URL_TTL_MILLIS)
        val request = GeneratePresignedUrlRequest(bucketName, objectKey, HttpMethod.GET).apply {
            this.expiration = expiration
        }
        return ossClient.generatePresignedUrl(request).toString()
    }

    /**
     * The persisted read URL of an object: a long-lived pre-signed GET URL when OSS is configured,
     * or the plain public URL on the unconfigured dev/CI mock path.
     */
    fun downloadUrl(objectKey: String): String =
        if (isConfigured) signedGetUrl(objectKey) else publicUrl(objectKey)

    private val ossClient by lazy {
        val endpointUrl = if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            "https://$endpoint"
        } else {
            endpoint
        }
        OSSClientBuilder().build(endpointUrl, accessKeyId, accessKeySecret)
    }

    /**
     * Make sure the configured bucket exists, creating it if necessary. Called on server startup so
     * uploads don't later fail with `NoSuchBucket`. No-op when OSS credentials aren't configured
     * (dev/CI) since we never touch the network there.
     */
    fun ensureBucketExists() {
        if (!isConfigured) {
            logger.info("OSS credentials not configured; skipping bucket existence check for {}", bucketName)
            return
        }
        try {
            if (ossClient.doesBucketExist(bucketName)) {
                logger.info("OSS bucket '{}' already exists.", bucketName)
            } else {
                logger.info("OSS bucket '{}' does not exist; creating it.", bucketName)
                ossClient.createBucket(bucketName)
                logger.info("Created OSS bucket '{}'.", bucketName)
            }
            // Note: we intentionally do NOT set a public-read ACL here. The bucket has "block public
            // access" enabled, so public ACLs are rejected ("Put public object acl is not allowed").
            // Objects stay private and are served via pre-signed GET URLs instead (see signedGetUrl).
        } catch (e: Exception) {
            logger.error("Failed to ensure OSS bucket '{}' exists.", bucketName, e)
            throw e
        }
    }

    /**
     * The Content-Type an uploader MUST send when PUTting [objectKey] through a pre-signed upload
     * URL. OSS includes the Content-Type header in the V1 signature, so the URL is signed with this
     * exact value and any other header value makes the PUT fail with `SignatureDoesNotMatch`.
     */
    fun uploadContentType(objectKey: String): String =
        contentTypeFor(objectKey) ?: "application/octet-stream"

    fun generatePreSignedUploadUrl(objectKey: String): String {
        try {
            logger.info("Generating pre-signed upload URL for objectKey: {}", objectKey)
            val expiration = Date(System.currentTimeMillis() + 3600 * 1000) // 1 hour validity
            val request = GeneratePresignedUrlRequest(bucketName, objectKey, HttpMethod.PUT).apply {
                this.expiration = expiration
                // Sign the Content-Type so browsers/desktop clients can send it on the PUT. Without
                // this, any Content-Type header breaks the signature (`SignatureDoesNotMatch`) and
                // the upload fails. Clients must send exactly [uploadContentType].
                contentType = uploadContentType(objectKey)
            }
            val signedUrl = ossClient.generatePresignedUrl(request)
            return signedUrl.toString()
        } catch (e: Exception) {
            logger.error("Failed to generate pre-signed upload URL for {}", objectKey, e)
            throw e
        }
    }

    /**
     * Explicit content type by file extension. Browsers refuse to play media served as
     * `application/octet-stream` (e.g. sequencer WAVs), so uploads always carry a proper type.
     */
    private fun contentTypeFor(objectKey: String): String? = when (objectKey.substringAfterLast('.', "").lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        else -> null
    }

    fun uploadFile(objectKey: String, file: java.io.File): String {
        if (!isConfigured) {
            logger.warn("OSS credentials not configured; skipping upload for {} (returning mock URL)", objectKey)
            return publicUrl(objectKey)
        }
        try {
            logger.info("Uploading file to OSS: {} -> {}", file.absolutePath, objectKey)
            // Upload the File directly (reliable content length) while forcing a proper
            // Content-Type. Browsers refuse to play media served as application/octet-stream, which
            // is why previously rendered sequencer WAVs were silent on the timeline.
            val metadata = ObjectMetadata().apply {
                contentLength = file.length()
                contentTypeFor(objectKey)?.let { contentType = it }
                // Do NOT set a public-read object ACL: the bucket blocks public access and rejects
                // it with "Put public object acl is not allowed". The object stays private and is
                // served through a pre-signed GET URL below.
            }
            val request = PutObjectRequest(bucketName, objectKey, file).apply { setMetadata(metadata) }
            ossClient.putObject(request)
            // Return a credential-signed GET URL so the browser can read the private object.
            return signedGetUrl(objectKey)
        } catch (e: Exception) {
            // Do NOT swallow the failure by returning a public URL: the object was never stored, so
            // that URL would 404 and the caller would persist a broken asset. Surface the error so
            // the route responds with a real failure the user can see (e.g. an invalid bucket name).
            logger.error("Failed to upload file to OSS: {}", objectKey, e)
            throw e
        }
    }
}
