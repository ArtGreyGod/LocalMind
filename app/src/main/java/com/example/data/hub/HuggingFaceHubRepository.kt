package com.example.data.hub

import android.content.Context
import com.example.data.storage.LocalModelRepository
import com.example.domain.engine.LocalModelEntry
import com.example.domain.engine.ModelFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class HfRepoApiItem(
    val id: String = "",
    val downloads: Long = 0L,
    val likes: Int = 0,
    val tags: List<String> = emptyList(),
    @SerialName("pipeline_tag") val pipelineTag: String? = null
)

@Serializable
data class HfTreeApiItem(
    val type: String = "",
    val path: String = "",
    val size: Long = 0L,
    val lfs: HfLfsMeta? = null
)

@Serializable
data class HfLfsMeta(
    val size: Long = 0L
)

data class HfRepoSummary(
    val repoId: String,
    val displayName: String,
    val author: String,
    val downloads: Long,
    val likes: Int,
    val tags: List<String>
)

data class HfModelFile(
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long,
    val format: ModelFormat,
    val quantizationLabel: String,
    val isMmproj: Boolean
) {
    val taskId: String get() = "$repoId::$fileName"
    val downloadUrl: String get() = "https://huggingface.co/$repoId/resolve/main/$fileName?download=true"
}

enum class DownloadStatus {
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class ModelDownloadTask(
    val taskId: String,
    val repoId: String,
    val fileName: String,
    val downloadUrl: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSec: Long = 0L,
    val status: DownloadStatus,
    val errorMessage: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Queries Hugging Face Hub public model APIs and executes resilient HTTP Byte-Range
 * resumable downloads into app-private storage, registering completed models automatically.
 */
class HuggingFaceHubRepository(
    private val context: Context,
    private val modelRepository: LocalModelRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()

    private val stagingDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, ModelDownloadTask>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownloadTask>> = _downloads.asStateFlow()

    val curatedStarterRepos: List<HfRepoSummary> = listOf(
        HfRepoSummary(
            repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            displayName = "Qwen2.5 0.5B Instruct",
            author = "Qwen",
            downloads = 1_250_000L,
            likes = 420,
            tags = listOf("gguf", "compact", "mobile")
        ),
        HfRepoSummary(
            repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            displayName = "Qwen2.5 1.5B Instruct",
            author = "Qwen",
            downloads = 2_100_000L,
            likes = 680,
            tags = listOf("gguf", "q4_k_m", "mobile")
        ),
        HfRepoSummary(
            repoId = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            displayName = "Llama 3.2 1B Instruct",
            author = "bartowski",
            downloads = 1_840_000L,
            likes = 590,
            tags = listOf("gguf", "llama", "mobile")
        ),
        HfRepoSummary(
            repoId = "unsloth/Qwen3-0.6B-GGUF",
            displayName = "Qwen3 0.6B Thinking",
            author = "unsloth",
            downloads = 640_000L,
            likes = 310,
            tags = listOf("gguf", "thinking", "qwen3")
        ),
        HfRepoSummary(
            repoId = "litert-community/Gemma3-1B-IT",
            displayName = "Gemma 3 1B IT (LiteRT)",
            author = "litert-community",
            downloads = 420_000L,
            likes = 295,
            tags = listOf("litertlm", "npu", "gpu")
        )
    )

    suspend fun searchRepos(query: String): Result<List<HfRepoSummary>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.success(curatedStarterRepos)
        }
        runCatching {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://huggingface.co/api/models?search=$encoded&limit=18&sort=downloads&direction=-1"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Hugging Face API returned HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val rawItems = json.decodeFromString<List<HfRepoApiItem>>(body)
                rawItems
                    .filter { it.id.contains('/') }
                    .map { item ->
                        val author = item.id.substringBefore('/')
                        val name = item.id.substringAfter('/').replace('-', ' ')
                        HfRepoSummary(
                            repoId = item.id,
                            displayName = name,
                            author = author,
                            downloads = item.downloads,
                            likes = item.likes,
                            tags = item.tags.take(4)
                        )
                    }
            }
        }
    }

    suspend fun fetchRepoFiles(repoId: String): Result<List<HfModelFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://huggingface.co/api/models/$repoId/tree/main"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Unable to inspect $repoId files (HTTP ${response.code})")
                }
                val body = response.body?.string().orEmpty()
                val items = json.decodeFromString<List<HfTreeApiItem>>(body)
                parseModelFiles(repoId, items)
            }
        }
    }

    fun startOrResumeDownload(
        file: HfModelFile,
        onCompleted: (Result<LocalModelEntry>) -> Unit
    ) {
        val taskId = file.taskId
        if (activeJobs[taskId]?.isActive == true) return

        val partFile = stagingPartFile(file.repoId, file.fileName)
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        _downloads.update { map ->
            map + (
                taskId to ModelDownloadTask(
                    taskId = taskId,
                    repoId = file.repoId,
                    fileName = file.fileName,
                    downloadUrl = file.downloadUrl,
                    downloadedBytes = existingBytes,
                    totalBytes = file.sizeBytes,
                    status = DownloadStatus.DOWNLOADING
                )
                )
        }

        val job = scope.launch {
            try {
                executeRangeDownload(file, partFile, existingBytes)
                val regResult = modelRepository.registerDownloadedModelFile(partFile, file.fileName)
                if (regResult.isSuccess) {
                    _downloads.update { map ->
                        val current = map[taskId]
                        if (current != null) {
                            map + (taskId to current.copy(status = DownloadStatus.COMPLETED, bytesPerSec = 0L))
                        } else map
                    }
                } else {
                    _downloads.update { map ->
                        val current = map[taskId]
                        if (current != null) {
                            map + (taskId to current.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = regResult.exceptionOrNull()?.message
                            ))
                        } else map
                    }
                }
                withContext(Dispatchers.Main) {
                    onCompleted(regResult)
                }
            } catch (_: CancellationException) {
                // Preserved .part file on pause or cancellation handler handles deletion
            } catch (e: Exception) {
                _downloads.update { map ->
                    val current = map[taskId]
                    if (current != null) {
                        map + (taskId to current.copy(
                            status = DownloadStatus.FAILED,
                            bytesPerSec = 0L,
                            errorMessage = e.message ?: "Download failed"
                        ))
                    } else map
                }
                withContext(Dispatchers.Main) {
                    onCompleted(Result.failure(e))
                }
            }
        }
        activeJobs[taskId] = job
    }

    fun pauseDownload(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        _downloads.update { map ->
            val current = map[taskId] ?: return@update map
            map + (taskId to current.copy(status = DownloadStatus.PAUSED, bytesPerSec = 0L))
        }
    }

    fun cancelDownload(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        val task = _downloads.value[taskId]
        if (task != null) {
            runCatching { stagingPartFile(task.repoId, task.fileName).delete() }
        }
        _downloads.update { it - taskId }
    }

    private fun CoroutineScope.executeRangeDownload(
        file: HfModelFile,
        partFile: File,
        resumeOffset: Long
    ) {
        val reqBuilder = Request.Builder().url(file.downloadUrl)
        if (resumeOffset > 0L) {
            reqBuilder.header("Range", "bytes=$resumeOffset-")
        }
        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                error("Download failed with HTTP ${response.code}")
            }
            val body = response.body ?: error("Empty download response")
            val isPartial = response.code == 206
            val append = isPartial && resumeOffset > 0L
            val startingBytes = if (append) resumeOffset else 0L
            val contentLength = body.contentLength()
            val expectedTotal = when {
                file.sizeBytes > 0L -> file.sizeBytes
                contentLength > 0L -> startingBytes + contentLength
                else -> 0L
            }

            var copied = startingBytes
            var lastTickNs = System.nanoTime()
            var bytesSinceTick = 0L

            body.byteStream().use { input ->
                FileOutputStream(partFile, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        bytesSinceTick += read

                        val nowNs = System.nanoTime()
                        val elapsedMs = (nowNs - lastTickNs) / 1_000_000L
                        if (elapsedMs >= 350L) {
                            val speed = (bytesSinceTick * 1000L) / elapsedMs.coerceAtLeast(1L)
                            lastTickNs = nowNs
                            bytesSinceTick = 0L
                            _downloads.update { map ->
                                val prev = map[file.taskId] ?: return@update map
                                map + (
                                    file.taskId to prev.copy(
                                        downloadedBytes = copied,
                                        totalBytes = expectedTotal,
                                        bytesPerSec = speed,
                                        status = DownloadStatus.DOWNLOADING
                                    )
                                    )
                            }
                        }
                    }
                    output.flush()
                }
            }

            _downloads.update { map ->
                val prev = map[file.taskId] ?: return@update map
                map + (
                    file.taskId to prev.copy(
                        downloadedBytes = copied,
                        totalBytes = expectedTotal.coerceAtLeast(copied)
                    )
                    )
            }
        }
    }

    private fun stagingPartFile(repoId: String, fileName: String): File {
        val key = "${repoId}_${fileName}".replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(stagingDir, "$key.part")
    }

    companion object {
        internal fun parseModelFiles(repoId: String, items: List<HfTreeApiItem>): List<HfModelFile> {
            return items
                .filter { it.type == "file" }
                .mapNotNull { item ->
                    val name = item.path.substringAfterLast('/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val format = ModelFormat.entries.firstOrNull { ext in it.extensions } ?: return@mapNotNull null
                    val totalSize = item.lfs?.size?.takeIf { it > 0L } ?: item.size
                    val isMmproj = name.contains("mmproj", ignoreCase = true)
                    HfModelFile(
                        repoId = repoId,
                        fileName = name,
                        sizeBytes = totalSize,
                        format = format,
                        quantizationLabel = inferQuant(name, format),
                        isMmproj = isMmproj
                    )
                }
                .sortedWith(compareBy({ it.isMmproj }, { it.sizeBytes }))
        }

        private fun inferQuant(fileName: String, format: ModelFormat): String {
            val upper = fileName.uppercase()
            val quants = listOf(
                "Q4_K_M", "Q4_K_S", "Q5_K_M", "Q5_K_S", "Q6_K", "Q8_0",
                "Q3_K_M", "Q2_K", "IQ4_XS", "F16", "BF16"
            )
            quants.firstOrNull { upper.contains(it) }?.let { return it }
            if (upper.contains("INT4") || upper.contains("Q4")) return "INT4"
            if (upper.contains("INT8") || upper.contains("Q8")) return "INT8"
            return format.label
        }
    }
}
