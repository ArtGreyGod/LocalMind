package com.example.data.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.DocumentChunkEntity
import com.example.data.local.DocumentCitationUi
import com.example.data.local.IndexedDocumentEntity
import com.example.data.local.LocalMindDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.ln

data class RetrievedRagResult(
    val citations: List<DocumentCitationUi>,
    val promptContextBlock: String
)

/**
 * Pluggable semantic-embedding provider. When available (an embedding GGUF is loaded),
 * retrieval reranks the BM25 candidates by cosine similarity; otherwise pure BM25 is used.
 */
interface RagEmbeddingProvider {
    val isAvailable: Boolean
    suspend fun embed(text: String): FloatArray?
}

class LocalRagRepository(
    private val context: Context,
    database: LocalMindDatabase
) {
    private val documentDao = database.documentDao()

    companion object {
        private const val CHUNK_TARGET_CHARS = 1100
        private const val CHUNK_OVERLAP_CHARS = 180
        private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
        private const val SQLITE_VARIABLE_LIMIT = 500
        private const val RERANK_CANDIDATES = 24

        private val stopWords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
            "have", "has", "had", "not", "but", "what", "when", "where", "which", "who",
            "how", "can", "could", "would", "should", "about", "into", "your", "you"
        )

        internal fun tokenize(text: String): List<String> {
            return text.lowercase()
                .split(Regex("[^a-z0-9_]+"))
                .filter { it.length >= 2 && it !in stopWords }
        }

        internal fun chunkSections(
            sections: List<ExtractedPageSection>,
            targetChars: Int = CHUNK_TARGET_CHARS,
            overlapChars: Int = CHUNK_OVERLAP_CHARS
        ): List<ExtractedPageSection> {
            val output = mutableListOf<ExtractedPageSection>()
            for (section in sections) {
                val cleaned = section.text.trim()
                if (cleaned.isEmpty()) continue
                if (cleaned.length <= targetChars) {
                    output += ExtractedPageSection(section.sectionLabel, cleaned)
                    continue
                }
                var start = 0
                while (start < cleaned.length) {
                    var end = (start + targetChars).coerceAtMost(cleaned.length)
                    if (end < cleaned.length) {
                        val breakPoint = cleaned.lastIndexOf('\n', end).takeIf { it > start + targetChars / 2 }
                            ?: cleaned.lastIndexOf(' ', end).takeIf { it > start + targetChars / 2 }
                        if (breakPoint != null) {
                            end = breakPoint
                        }
                    }
                    val piece = cleaned.substring(start, end).trim()
                    if (piece.isNotEmpty()) {
                        output += ExtractedPageSection(section.sectionLabel, piece)
                    }
                    if (end >= cleaned.length) break
                    start = (end - overlapChars).coerceAtLeast(start + 1)
                }
            }
            return output
        }

        internal fun scoreChunksBm25(
            query: String,
            chunks: List<DocumentChunkEntity>,
            topK: Int = 3
        ): List<DocumentChunkEntity> {
            if (chunks.isEmpty()) return emptyList()
            val queryTokens = tokenize(query).distinct()
            if (queryTokens.isEmpty()) return chunks.take(topK)

            val docCount = chunks.size.toDouble()
            val tokenizedChunks = chunks.map { chunk ->
                val terms = if (chunk.normalizedTokens.isNotBlank()) {
                    chunk.normalizedTokens.split(' ')
                } else {
                    tokenize(chunk.content)
                }
                chunk to terms
            }
            val avgDocLen = tokenizedChunks.map { it.second.size }.average().coerceAtLeast(1.0)

            val docFreq = mutableMapOf<String, Int>()
            for (q in queryTokens) {
                docFreq[q] = tokenizedChunks.count { (_, terms) -> q in terms }
            }

            val k1 = 1.5
            val b = 0.75

            return tokenizedChunks
                .map { (chunk, terms) ->
                    val tfMap = terms.groupingBy { it }.eachCount()
                    val docLen = terms.size.toDouble().coerceAtLeast(1.0)
                    var score = 0.0
                    for (q in queryTokens) {
                        val tf = tfMap[q]?.toDouble() ?: 0.0
                        if (tf <= 0.0) continue
                        val df = docFreq[q]?.toDouble() ?: 0.0
                        val idf = ln(((docCount - df + 0.5) / (df + 0.5)) + 1.0)
                        val numerator = tf * (k1 + 1.0)
                        val denominator = tf + k1 * (1.0 - b + b * (docLen / avgDocLen))
                        score += idf * (numerator / denominator)
                    }
                    chunk to score
                }
                .filter { it.second > 0.0 }
                .sortedByDescending { it.second }
                .take(topK)
                .map { it.first }
                .ifEmpty { chunks.take(minOf(2, topK)) }
        }
    }

    fun observeDocuments(threadId: String, workspaceId: String): Flow<List<IndexedDocumentEntity>> =
        documentDao.observeForContext(threadId = threadId, workspaceId = workspaceId)

    suspend fun attachDocumentFromUri(
        uri: Uri,
        threadId: String?,
        workspaceId: String?
    ): Result<IndexedDocumentEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val (displayName, sizeBytes, mimeType) = queryDocumentMetadata(uri)
            if (sizeBytes > MAX_FILE_BYTES) {
                error("File exceeds 16 MB local indexing limit ($displayName).")
            }

            // Cap the read even when the provider reports no size (size == 0/-1), so an
            // unbounded document can never be fully materialized into memory.
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream(MAX_FILE_BYTES.toInt().coerceAtLeast(64 * 1024))
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) {
                        error("File exceeds 16 MB local indexing limit ($displayName).")
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: error("Cannot read document stream for $displayName")
            if (rawBytes.isEmpty()) error("Document $displayName is empty.")

            val ext = displayName.substringAfterLast('.', "").lowercase()
            val isPdf = ext == "pdf" || mimeType.contains("pdf", ignoreCase = true)

            val sections = if (isPdf) {
                val extracted = PdfTextExtractor.extractPdf(rawBytes)
                if (extracted.sections.isEmpty()) {
                    error(
                        if (extracted.skippedGarbledStreams > 0) {
                            "$displayName uses a custom font encoding that can't be read as text. " +
                                "If it is a scanned PDF, take a photo for Vision analysis."
                        } else {
                            "No selectable text found in $displayName. If it is a scanned PDF, take a photo for Vision analysis."
                        }
                    )
                }
                extracted.sections
            } else {
                val decoded = String(rawBytes, StandardCharsets.UTF_8).trim()
                if (decoded.isEmpty()) error("Document $displayName is empty.")
                listOf(ExtractedPageSection(sectionLabel = displayName, text = decoded))
            }

            val chunkSections = chunkSections(sections)
            val docId = UUID.randomUUID().toString()
            val totalChars = chunkSections.sumOf { it.text.length }

            val chunkEntities = chunkSections.mapIndexed { idx, piece ->
                DocumentChunkEntity(
                    id = "${docId}_$idx",
                    documentId = docId,
                    chunkIndex = idx,
                    sectionLabel = "${piece.sectionLabel} • #${idx + 1}",
                    content = piece.text,
                    normalizedTokens = tokenize(piece.text).joinToString(" ")
                )
            }

            val docEntity = IndexedDocumentEntity(
                id = docId,
                threadId = threadId,
                workspaceId = workspaceId,
                displayName = displayName,
                mimeType = mimeType.ifBlank { if (isPdf) "application/pdf" else "text/plain" },
                chunkCount = chunkEntities.size,
                charCount = totalChars
            )

            documentDao.saveDocumentWithChunks(docEntity, chunkEntities)
            docEntity
        }
    }

    suspend fun removeDocument(docId: String) = withContext(Dispatchers.IO) {
        documentDao.deleteDocument(docId)
    }

    suspend fun retrieveContextForPrompt(
        query: String,
        attachedDocs: List<IndexedDocumentEntity>,
        topK: Int = 3,
        embeddingProvider: RagEmbeddingProvider? = null
    ): RetrievedRagResult? = withContext(Dispatchers.IO) {
        if (attachedDocs.isEmpty()) return@withContext null
        val docIds = attachedDocs.map { it.id }
        val docById = attachedDocs.associateBy { it.id }
        // SQLite has a per-statement host-variable limit; large document sets must be
        // queried in batches and merged.
        val allChunks = docIds.chunked(SQLITE_VARIABLE_LIMIT).flatMap { batch ->
            documentDao.getChunksForDocuments(batch)
        }
        if (allChunks.isEmpty()) return@withContext null

        val bm25Ranked = scoreChunksBm25(query, allChunks, topK = RERANK_CANDIDATES)
        val topChunks = if (embeddingProvider?.isAvailable == true && bm25Ranked.isNotEmpty()) {
            rerankByEmbedding(query, bm25Ranked, topK, embeddingProvider)
        } else {
            bm25Ranked.take(topK)
        }
        if (topChunks.isEmpty()) return@withContext null

        val citations = topChunks.map { chunk ->
            val docName = docById[chunk.documentId]?.displayName ?: "Document"
            DocumentCitationUi(
                documentId = chunk.documentId,
                documentName = docName,
                sectionLabel = chunk.sectionLabel,
                snippet = chunk.content.take(180).replace('\n', ' ').trim()
            )
        }

        val block = buildString {
            appendLine("<local_documents_context>")
            appendLine("Use these grounded excerpts from the user's attached local documents when relevant and cite the filename:")
            topChunks.forEachIndexed { idx, chunk ->
                val docName = docById[chunk.documentId]?.displayName ?: "Document"
                appendLine("[Source ${idx + 1}: $docName (${chunk.sectionLabel})]")
                appendLine(chunk.content)
                appendLine()
            }
            appendLine("</local_documents_context>")
        }.trim()

        RetrievedRagResult(citations = citations, promptContextBlock = block)
    }

    /**
     * Semantic rerank: cosine similarity between the query embedding and each BM25
     * candidate. BM25 keeps recall (rare-term matches), the embedding pass lifts
     * paraphrased matches that lexical scoring misses.
     */
    private suspend fun rerankByEmbedding(
        query: String,
        bm25Candidates: List<DocumentChunkEntity>,
        topK: Int,
        provider: RagEmbeddingProvider
    ): List<DocumentChunkEntity> {
        val queryVec = runCatching { provider.embed(query) }.getOrNull() ?: return bm25Candidates.take(topK)
        if (queryVec.isEmpty()) return bm25Candidates.take(topK)
        data class Scored(val chunk: DocumentChunkEntity, val bm25Rank: Int, val cosine: Double)
        val scored = buildList {
            bm25Candidates.forEachIndexed { rank, chunk ->
                val chunkVec = runCatching { provider.embed(chunk.content) }.getOrNull()
                if (chunkVec != null && chunkVec.size == queryVec.size) {
                    add(Scored(chunk, rank, cosineSimilarity(queryVec, chunkVec)))
                } else {
                    add(Scored(chunk, rank, 0.0))
                }
            }
        }
        // Blend: lexical rank keeps strong exact matches stable while cosine promotes
        // paraphrases. rankBias ≈ 1/rank rewards BM25 winners without zeroing the rest.
        return scored
            .map { s -> s.chunk to (s.cosine + 0.05 / (s.bm25Rank + 1)) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom > 0.0) dot / denom else 0.0
    }

    private fun queryDocumentMetadata(uri: Uri): Triple<String, Long, String> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document.txt"
        var size = 0L
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
        val mime = context.contentResolver.getType(uri) ?: ""
        return Triple(name, size, mime)
    }
}
