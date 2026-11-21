package com.example.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.engine.LocalModelEntry
import com.example.domain.engine.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Context.modelVaultDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "localmind_model_vault"
)

data class ImportProgress(
    val fileName: String,
    val bytesCopied: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (bytesCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Manages SAF model import into app-private storage, quantization/VL capability detection,
 * GGUF header metadata enrichment, mmproj pairing for GGUF VLMs, and persistent DataStore registry.
 *
 * All registry mutations are serialized through a single mutex because the registry is a
 * whole-list JSON blob: concurrent read-modify-write cycles would last-writer-wins and
 * silently drop a just-registered multi-GB model.
 */
class LocalModelRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val registryMutex = Mutex()

    private val modelsKey = stringPreferencesKey("registered_models_json")

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    private val mmprojDir: File
        get() = File(context.filesDir, "mmproj").apply { mkdirs() }

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: Flow<ImportProgress?> = _importProgress

    val models: Flow<List<LocalModelEntry>> = context.modelVaultDataStore.data.map { prefs ->
        val raw = prefs[modelsKey] ?: return@map emptyList()
        runCatching {
            json.decodeFromString<List<LocalModelEntry>>(raw)
                .filter { File(it.filePath).exists() }
        }.getOrDefault(emptyList())
    }

    suspend fun importModelFromSaf(uri: Uri): Result<LocalModelEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val (fileName, reportedSize) = queryDisplayNameAndSize(uri)
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val format = ModelFormat.entries.firstOrNull { ext in it.extensions }
                ?: error("Unsupported model extension '.$ext'. Select a .litertlm, .tflite, or .gguf file.")

            if (fileName.contains("mmproj", ignoreCase = true) && format == ModelFormat.GGUF) {
                error("Detected a multimodal projector ($fileName). Use 'Pair mmproj' on an existing GGUF model.")
            }

            // Copy into a .tmp file outside the registry lock, then finalize the
            // unique vault name + registry write atomically under the mutex.
            val tmpFile = copyUriToTmpFile(uri, fileName, reportedSize)
            registryMutex.withLock {
                val current = models.first()
                val finalFile = resolveUniqueVaultFile(modelsDir, fileName, current)
                finalizeTmpFile(tmpFile, finalFile)
                val ggufMeta = parseGgufMeta(finalFile)
                val entry = LocalModelEntry(
                    id = UUID.randomUUID().toString(),
                    displayName = formatDisplayName(fileName),
                    fileName = fileName,
                    filePath = finalFile.absolutePath,
                    fileSizeBytes = finalFile.length(),
                    format = format,
                    quantizationLabel = inferQuantizationLabel(fileName, format),
                    supportsNativeVision = inferNativeVisionSupport(fileName, format),
                    importedAtMillis = System.currentTimeMillis(),
                    architecture = ggufMeta?.architecture,
                    trainedContextLength = ggufMeta?.contextLength,
                    blockCount = ggufMeta?.blockCount,
                    parameterCount = ggufMeta?.parameterCount
                )
                saveAllLocked(listOf(entry) + current)
                entry
            }
        }.also {
            _importProgress.value = null
        }
    }

    suspend fun pairMmprojToModel(
        modelId: String,
        mmprojUri: Uri
    ): Result<LocalModelEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val (fileName, reportedSize) = queryDisplayNameAndSize(mmprojUri)
            require(fileName.endsWith(".gguf", ignoreCase = true)) {
                "Projector file must be a .gguf file (e.g. mmproj-model-f16.gguf)."
            }

            val tmpFile = copyUriToTmpFile(mmprojUri, fileName, reportedSize)
            registryMutex.withLock {
                val current = models.first()
                val target = current.firstOrNull { it.id == modelId }
                    ?: error("Target model not found in vault")
                require(target.format == ModelFormat.GGUF) {
                    "Multimodal projector pairing is only used with GGUF models."
                }

                // Clean up any previously paired mmproj file for this model
                target.mmprojFilePath?.let { oldPath ->
                    runCatching { File(oldPath).delete() }
                }

                val finalFile = resolveUniqueVaultFile(mmprojDir, "${target.id}_$fileName", current)
                finalizeTmpFile(tmpFile, finalFile)
                val updated = target.copy(
                    mmprojFilePath = finalFile.absolutePath,
                    mmprojFileName = fileName,
                    mmprojSizeBytes = finalFile.length()
                )
                saveAllLocked(current.map { if (it.id == updated.id) updated else it })
                updated
            }
        }.also {
            _importProgress.value = null
        }
    }

    suspend fun registerDownloadedModelFile(
        downloadedFile: File,
        originalFileName: String
    ): Result<LocalModelEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = originalFileName.substringAfterLast('.', "").lowercase()
            val format = ModelFormat.entries.firstOrNull { ext in it.extensions }
                ?: error("Unsupported model extension '.$ext'.")
            registryMutex.withLock {
                val current = models.first()
                val finalFile = resolveUniqueVaultFile(modelsDir, originalFileName, current)
                if (finalFile.absolutePath != downloadedFile.absolutePath) {
                    check(downloadedFile.renameTo(finalFile)) { "Failed to move downloaded model into vault" }
                }
                val ggufMeta = parseGgufMeta(finalFile)
                val entry = LocalModelEntry(
                    id = UUID.randomUUID().toString(),
                    displayName = formatDisplayName(originalFileName),
                    fileName = originalFileName,
                    filePath = finalFile.absolutePath,
                    fileSizeBytes = finalFile.length(),
                    format = format,
                    quantizationLabel = inferQuantizationLabel(originalFileName, format),
                    supportsNativeVision = inferNativeVisionSupport(originalFileName, format),
                    importedAtMillis = System.currentTimeMillis(),
                    architecture = ggufMeta?.architecture,
                    trainedContextLength = ggufMeta?.contextLength,
                    blockCount = ggufMeta?.blockCount,
                    parameterCount = ggufMeta?.parameterCount
                )
                saveAllLocked(listOf(entry) + current)
                entry
            }
        }
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            val current = models.first()
            val target = current.firstOrNull { it.id == modelId } ?: return@withLock
            runCatching { File(target.filePath).delete() }
            target.mmprojFilePath?.let { runCatching { File(it).delete() } }
            saveAllLocked(current.filterNot { it.id == modelId })
        }
    }

    /**
     * Picks a vault filename for [originalFileName] that cannot clobber a model that is
     * already registered: a fresh name is only reused when no live entry references it.
     */
    private fun resolveUniqueVaultFile(targetDir: File, originalFileName: String, registered: List<LocalModelEntry>): File {
        val sanitized = originalFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val candidate = File(targetDir, sanitized)
        val referencedPaths = registered.map { it.filePath }.toSet() + registered.mapNotNull { it.mmprojFilePath }
        if (!candidate.exists() || candidate.absolutePath !in referencedPaths) {
            return candidate
        }
        val base = sanitized.substringBeforeLast('.')
        val ext = sanitized.substringAfterLast('.', "").ifEmpty { null }
        repeat(64) { attempt ->
            val uniqueName = "${base}_${UUID.randomUUID().toString().take(6)}${ext?.let { ".$it" } ?: ""}"
            val unique = File(targetDir, uniqueName)
            if (!unique.exists()) return unique
        }
        error("Unable to allocate a unique vault file name for $originalFileName")
    }

    private fun finalizeTmpFile(tmpFile: File, finalFile: File) {
        if (tmpFile.absolutePath == finalFile.absolutePath) return
        if (finalFile.exists()) finalFile.delete()
        check(tmpFile.renameTo(finalFile)) { "Failed to finalize model file ${finalFile.name}" }
    }

    private fun copyUriToTmpFile(
        uri: Uri,
        safeName: String,
        totalSize: Long
    ): File {
        val sanitized = safeName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetDir = modelsDir
        val tmpFile = File(targetDir, "${sanitized}.${UUID.randomUUID().toString().take(8)}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        context.contentResolver.openInputStream(uri)?.use { input ->
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var totalCopied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    totalCopied += read
                    _importProgress.value = ImportProgress(
                        fileName = safeName,
                        bytesCopied = totalCopied,
                        totalBytes = totalSize
                    )
                }
                output.flush()
            }
        } ?: error("Cannot open SAF stream for $uri")
        return tmpFile
    }

    private suspend fun saveAllLocked(entries: List<LocalModelEntry>) {
        context.modelVaultDataStore.edit { prefs ->
            prefs[modelsKey] = json.encodeToString(entries)
        }
    }

    private fun parseGgufMeta(file: File): GgufMetadataParser.GgufMetadata? =
        if (file.extension.equals("gguf", ignoreCase = true)) {
            GgufMetadataParser.parse(file)
        } else null

    private fun queryDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model_${System.currentTimeMillis()}"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) {
                    cursor.getString(nameIdx)?.let { name = it }
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun formatDisplayName(fileName: String): String {
        return fileName.substringBeforeLast('.')
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
            .ifEmpty { fileName }
    }

    private fun inferQuantizationLabel(fileName: String, format: ModelFormat): String {
        val upper = fileName.uppercase()
        val ggufQuants = listOf(
            "Q4_K_M", "Q4_K_S", "Q5_K_M", "Q5_K_S", "Q6_K", "Q8_0",
            "Q3_K_M", "Q2_K", "IQ4_XS", "F16", "BF16"
        )
        ggufQuants.firstOrNull { upper.contains(it) }?.let { return it }
        if (upper.contains("INT4") || upper.contains("Q4")) return "INT4"
        if (upper.contains("INT8") || upper.contains("Q8")) return "INT8"
        if (upper.contains("FP16") || upper.contains("F16")) return "FP16"
        return when (format) {
            ModelFormat.LITERT_LM -> "LITERT-LM"
            ModelFormat.LITERT_TFLITE -> "TFLITE"
            ModelFormat.GGUF -> "GGUF"
        }
    }

    private fun inferNativeVisionSupport(fileName: String, format: ModelFormat): Boolean {
        if (format != ModelFormat.LITERT_LM) return false
        val lower = fileName.lowercase()
        return lower.contains("gemma-4") ||
            lower.contains("gemma4") ||
            lower.contains("fastvlm") ||
            lower.contains("vision") ||
            lower.contains("-vl") ||
            lower.contains("_vl") ||
            lower.contains("multimodal")
    }
}
