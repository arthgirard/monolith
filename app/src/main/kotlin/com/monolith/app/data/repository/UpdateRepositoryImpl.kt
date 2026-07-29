package com.monolith.app.data.repository

import android.content.Context
import com.monolith.app.BuildConfig
import com.monolith.app.domain.model.DownloadState
import com.monolith.app.domain.model.UpdateCheckResult
import com.monolith.app.domain.repository.UpdateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

@Singleton
class UpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext UpdateCheckResult.Failure("GitHub returned HTTP ${connection.responseCode}.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }

            val release = json.decodeFromString(GithubRelease.serializer(), body)
            val latestVersion = release.tagName.removePrefix("v")
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext UpdateCheckResult.Failure("Latest release has no APK attached.")

            if (isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.UpdateAvailable(latestVersion, apkAsset.browserDownloadUrl)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failure(e.message ?: "Network error.")
        }
    }

    override fun downloadApk(url: String): Flow<DownloadState> = flow {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                instanceFollowRedirects = true
            }
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                emit(DownloadState.Failed("Download failed: HTTP ${connection.responseCode}."))
                return@flow
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            val outFile = File(context.cacheDir, "monolith-update.apk")

            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        emit(DownloadState.Progress(totalBytes?.let { bytesRead.toFloat() / it }))
                    }
                }
            }
            emit(DownloadState.Complete(outFile))
        } catch (e: Exception) {
            emit(DownloadState.Failed(e.message ?: "Download failed."))
        }
    }.flowOn(Dispatchers.IO)

    override fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Pairwise numeric comparison, e.g. "1.10.0" > "1.9.0" (unlike a plain string compare). */
    private fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    companion object {
        private const val RELEASES_API_URL = "https://api.github.com/repos/arthgirard/monolith/releases/latest"
        private const val TIMEOUT_MILLIS = 15_000
        private const val BUFFER_SIZE = 8 * 1024
    }
}
