package app.moviestudio.storage

import app.moviestudio.config.Env
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.HttpMethod
import com.aliyun.oss.model.GeneratePresignedUrlRequest
import org.slf4j.LoggerFactory
import java.util.Date

object OssService {
    private val logger = LoggerFactory.getLogger(OssService::class.java)

    val endpoint = Env.get("OSS_ENDPOINT", "oss-cn-hangzhou.aliyuncs.com")
    val bucketName = Env.get("OSS_BUCKET_NAME", "movie-studio-bucket")
    val accessKeyId = Env.get("OSS_ACCESS_KEY_ID", "mock-access-key-id")
    val accessKeySecret = Env.get("OSS_ACCESS_KEY_SECRET", "mock-access-key-secret")

    private val ossClient by lazy {
        val endpointUrl = if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            "https://$endpoint"
        } else {
            endpoint
        }
        OSSClientBuilder().build(endpointUrl, accessKeyId, accessKeySecret)
    }

    fun generatePreSignedUploadUrl(objectKey: String): String {
        try {
            logger.info("Generating pre-signed upload URL for objectKey: {}", objectKey)
            val expiration = Date(System.currentTimeMillis() + 3600 * 1000) // 1 hour validity
            val request = GeneratePresignedUrlRequest(bucketName, objectKey, HttpMethod.PUT).apply {
                this.expiration = expiration
            }
            val signedUrl = ossClient.generatePresignedUrl(request)
            return signedUrl.toString()
        } catch (e: Exception) {
            logger.error("Failed to generate pre-signed upload URL for {}", objectKey, e)
            throw e
        }
    }

    fun uploadFile(objectKey: String, file: java.io.File): String {
        try {
            logger.info("Uploading file to OSS: {} -> {}", file.absolutePath, objectKey)
            ossClient.putObject(bucketName, objectKey, file)
            val endpointClean = endpoint.removePrefix("http://").removePrefix("https://")
            return "https://$bucketName.$endpointClean/$objectKey"
        } catch (e: Exception) {
            logger.error("Failed to upload file to OSS (mock/no credentials?), falling back to mock URL: {}", objectKey, e)
            val endpointClean = endpoint.removePrefix("http://").removePrefix("https://")
            return "https://$bucketName.$endpointClean/$objectKey"
        }
    }
}
