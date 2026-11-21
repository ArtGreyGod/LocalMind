package com.example.data.hub

import android.content.Context
import com.example.data.storage.LocalModelRepository
import com.example.domain.engine.LocalModelEntry
import com.example.domain.engine.ModelFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

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
    val size: Long = 0L,
    /** LFS sha256 from the Hub tree API; used to verify download integrity. */
    val oid: String? = null
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
    val isMmproj: Boolean,
    val sha256: String? = null
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
    val errorMessage: String? = null,
    // Retained from the original tree-API entry so pause/resume never fabricates metadata.
    val format: ModelFormat = ModelFormat.GGUF,
    val quantizationLabel: String = "GGUF",
    val isMmproj: Boolean = false,
    val expectedSha256: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Queries Hugging Face Hub public model APIs and executes resilient HTTP Byte-Range
 * resumable downloads into app-private storage, registering completed models automatically.
 *
 * Download guarantees:
 * - Only one writer per task at a time (per-task mutex; pause cancels the OkHttp call).
 * - Byte-range resumes validate the Content-Range start and treat "already complete"
 *   (fully staged file or HTTP 416) as registration-ready instead of failing forever.
 * - Completed byte counts are checked against the expected total, and LFS sha256
 *   (when advertised) is verified before registration; corrupt artifacts never enter the vault.
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
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val taskLocks = ConcurrentHashMap<String, Mutex>()
    /** Original tree-API descriptors, kept so pause/resume never rebuilds fabricated stubs. */
    private val taskSourceFiles = ConcurrentHashMap<String, HfModelFile>()

    private val stagingDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, ModelDownloadTask>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownloadTask>> = _downloads.asStateFlow()

    init {
        sweepOrphanedPartFiles()
    }

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

    /**
     * Re-enqueues a paused/failed task using the exact HfModelFile captured when it
     * started. Safe to call after process restarts only while this repository instance
     * still holds the descriptor (in-memory).
     */
    fun resumeTask(taskId: String): Boolean {
        val file = taskSourceFiles[taskId] ?: return false
        startOrResumeDownload(file) { }
        return true
    }

    fun startOrResumeDownload(
        file: HfModelFile,
        onCompleted: (Result<LocalModelEntry>) -> Unit
    ) {
        val taskId = file.taskId
        if (activeJobs[taskId]?.isActive == true) return

        taskSourceFiles[taskId] = file
        taskLocks.putIfAbsent(taskId, Mutex())

        val partFile = stagingPartFile(file.repoId, file.fileName)
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        // A fully staged file that was never registered (process death between the final
        // write and the registry write) must not re-download or fail with HTTP 416.
        val alreadyComplete = file.sizeBytes > 0L && existingBytes >= file.sizeBytes

        _downloads.update { map ->
            val previous = map[taskId]
            map + (
                taskId to (previous ?: ModelDownloadTask(
                    taskId = taskId,
                    repoId = file.repoId,
                    fileName = file.fileName,
                    downloadUrl = file.downloadUrl,
                    downloadedBytes = existingBytes,
                    totalBytes = file.sizeBytes,
                    status = DownloadStatus.DOWNLOADING,
                    format = file.format,
                    quantizationLabel = file.quantizationLabel,
                    isMmproj = file.isMmproj
                )).copy(
                    status = DownloadStatus.DOWNLOADING,
                    errorMessage = null,
                    format = file.format,
                    quantizationLabel = file.quantizationLabel,
                    isMmproj = file.isMmproj
                )
                )
        }

        val job = scope.launch {
            taskLocks.getValue(taskId).withLock {
                try {
                    if (!alreadyComplete) {
                        executeRangeDownload(file, partFile)
                    }
                    // Registration mutates DataStore; once started, finish it even if
                    // the caller pauses the task so the vault never loses the file.
                    val regResult = withContext(Dispatchers.IO + NonCancellable) {
                        modelRepository.registerDownloadedModelFile(partFile, file.fileName)
                    }
                    if (regResult.isSuccess) {
                        taskSourceFiles.remove(taskId)
                        stagingPartFile(file.repoId, file.fileName).delete()
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
                    // Pause/cancel: the .part file is preserved for the next resume.
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
        }
        activeJobs[taskId] = job
    }

    fun pauseDownload(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        activeCalls.remove(taskId)?.cancel()
        _downloads.update { map ->
            val current = map[taskId] ?: return@update map
            map + (taskId to current.copy(status = DownloadStatus.PAUSED, bytesPerSec = 0L))
        }
    }

    fun cancelDownload(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        activeCalls.remove(taskId)?.cancel()
        val task = _downloads.value[taskId]
        if (task != null) {
            runCatching { stagingPartFile(task.repoId, task.fileName).delete() }
        }
        taskSourceFiles.remove(taskId)
        _downloads.update { it - taskId }
    }

    /**
     * Removes staged .part files left behind by processes that died mid-download and
     * were never resumed. Recent files (<= 7 days) are kept so their byte data can
     * still be resumed within this session; everything older is reclaimed.
     */
    private fun sweepOrphanedPartFiles() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            stagingDir.listFiles { file -> file.name.endsWith(".part") && file.lastModified() < cutoff }
                ?.forEach { file ->
                    runCatching { file.delete() }
                }
        }
    }

    /**
     * One range-download attempt. Throws [HttpRestart] when the server response makes a
     * byte-range append unsafe (mismatched Content-Range or a full 200 body after a resume),
     * in which case the caller restarts cleanly from byte 0.
     */
    private class HttpRestart : RuntimeException()

    private fun CoroutineScope.executeRangeDownload(
        file: HfModelFile,
        partFile: File
    ) {
        val taskId = file.taskId
        var restarted = false
        while (true) {
            val resumeOffset = if (partFile.exists()) partFile.length() else 0L
            if (file.sizeBytes in 1 until resumeOffset) {
                // Stale .part from an older revision of this repo file; a byte-range
                // append would produce a corrupt hybrid file. Restart from scratch.
                runCatching { partFile.delete() }
                if (restarted) error("Unable to reconcile staged download for ${file.fileName}; restart the download.")
                restarted = true
                continue
            }

            val requestBuilder = Request.Builder().url(file.downloadUrl)
            if (resumeOffset > 0L) {
                requestBuilder.header("Range", "bytes=$resumeOffset-")
            }
            val call = client.newCall(requestBuilder.build())
            activeCalls[taskId] = call
            try {
                runRangeAttempt(file, partFile, call, resumeOffset)
                return
            } catch (restart: HttpRestart) {
                if (restarted) error("Download stream could not be aligned for ${file.fileName}; restart the download.")
                runCatching { partFile.delete() }
                restarted = true
            } finally {
                activeCalls.remove(taskId)
            }
        }
    }

    private fun CoroutineScope.runRangeAttempt(
        file: HfModelFile,
        partFile: File,
        call: okhttp3.Call,
        resumeOffset: Long
    ) {
        val taskId = file.taskId
        var contentRange: String? = null
        call.execute().use { response ->
            when {
                response.code == 416 && file.sizeBytes > 0L && resumeOffset >= file.sizeBytes -> {
                    // Everything requested is already staged (e.g. the previous run died
                    // between the final write and registration); proceed to registration.
                    return
                }
                response.code == 206 -> {
                    contentRange = response.header("Content-Range")
                    val serverStart = contentRange?.substringAfter(" ")?.substringBefore("-")?.trim()?.toLongOrNull()
                    if (serverStart != null && serverStart != resumeOffset) {
                        throw HttpRestart()
                    }
                }
                response.code == 200 && resumeOffset > 0L -> throw HttpRestart()
                !response.isSuccessful -> error("Download failed with HTTP ${response.code}")
            }
            val body = response.body ?: error("Empty download response")
            val isPartial = response.code == 206
            val append = isPartial && resumeOffset > 0L
            val startingBytes = if (append) resumeOffset else 0L
            val contentLength = body.contentLength()
            val rangeTotal = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
            val expectedTotal = when {
                file.sizeBytes > 0L -> file.sizeBytes
                isPartial && rangeTotal != null && rangeTotal > 0L -> rangeTotal
                contentLength > 0L -> startingBytes + contentLength
                else -> 0L
            }

            val digest = file.sha256?.takeIf { it.length == 64 }?.let {
                runCatching { MessageDigest.getInstance("SHA-256") }.getOrNull()
            }
            var copied = startingBytes
            var lastTickNs = System.nanoTime()
            var bytesSinceTick = 0L

            try {
                body.byteStream().use { input ->
                    FileOutputStream(partFile, append).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            copied += read
                            bytesSinceTick += read

                            val nowNs = System.nanoTime()
                            val elapsedMs = (nowNs - lastTickNs) / 1_000_000L
                            if (elapsedMs >= 350L) {
                                val speed = (bytesSinceTick * 1000L) / elapsedMs.coerceAtLeast(1L)
                                lastTickNs = nowNs
                                bytesSinceTick = 0L
                                _downloads.update { map ->
                                    val prev = map[taskId] ?: return@update map
                                    map + (
                                        taskId to prev.copy(
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
            } catch (io: java.io.IOException) {
                if (call.isCanceled()) {
                    // A deliberate pause/cancel must surface as cancellation, not failure.
                    throw CancellationException("Download paused")
                }
                throw io
            }

            // Integrity gate 1: byte count. A short-but-clean stream close (CDN edge
            // error, proxy truncation) must never register a truncated GGUF. Keep the
            // .part file so the user can resume the remainder.
            if (expectedTotal > 0L && copied != expectedTotal) {
                error(
                    "Download incomplete: got $copied of $expectedTotal bytes. " +
                        "Tap resume to continue from where it stopped."
                )
            }

            // Integrity gate 2: content hash advertised by the Hub for this file.
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(file.sha256, ignoreCase = true)) {
                    runCatching { stagingPartFile(file.repoId, file.fileName).delete() }
                    error(
                        "Downloaded file failed integrity verification (sha256 mismatch). " +
                            "The staging file was removed; please re-download."
                    )
                }
            }

            _downloads.update { map ->
                val prev = map[taskId] ?: return@update map
                map + (
                    taskId to prev.copy(
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
                        isMmproj = isMmproj,
                        sha256 = item.lfs?.oid?.takeIf { it.length == 64 }
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
