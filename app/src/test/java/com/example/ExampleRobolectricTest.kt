package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.InferenceEngineCoordinator
import com.example.data.engine.LiteRtEngineImpl
import com.example.data.engine.LlamaCppEngineImpl
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.data.engine.ThinkingStreamParser
import com.example.data.media.ImagePreprocessor
import com.example.data.storage.AppThemeMode
import com.example.domain.engine.EngineLifecycleState
import com.example.domain.engine.HardwareAccelerator
import com.example.domain.engine.LoadedModelInfo
import com.example.domain.engine.LocalModelEntry
import com.example.domain.engine.MemoryStats
import com.example.domain.engine.ModelFormat
import com.example.ui.components.MarkdownBlock
import com.example.ui.components.MarkdownParser
import com.example.ui.components.TelemetryAndTuningSheet
import com.example.ui.components.buildInlineMarkdown
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ModelManagerScreen
import com.example.ui.theme.LocalMindTheme
import com.example.ui.viewmodel.ChatMessageUi
import com.example.ui.viewmodel.ChatRole
import com.example.ui.viewmodel.InferenceTuningConfig
import com.example.ui.viewmodel.LocalMindUiState
import com.example.ui.viewmodel.LocalMindViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.performClick
import android.content.ComponentCallbacks2
import com.example.data.engine.MemoryPressureWarning
import com.example.data.hub.HfLfsMeta
import com.example.data.hub.HfRepoSummary
import com.example.data.hub.HfTreeApiItem
import com.example.data.hub.HuggingFaceHubRepository
import com.example.data.local.ChatHistoryRepository
import com.example.data.local.ChatThreadEntity
import com.example.data.local.DocumentChunkEntity
import com.example.data.local.DocumentCitationUi
import com.example.data.local.LocalMindDatabase
import com.example.data.local.WorkspaceEntity
import com.example.data.rag.LocalRagRepository
import com.example.data.rag.PdfTextExtractor
import com.example.data.voice.VoiceController
import com.example.domain.engine.GenerationTelemetry
import com.example.ui.screens.ThreadsHubScreen
import com.example.ui.viewmodel.HubBrowserState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app name matches LocalMind`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LocalMind", appName)
    }

    @Test
    fun `gguf model vision capability requires paired mmproj`() {
        val textOnlyGguf = LocalModelEntry(
            id = "1",
            displayName = "Qwen2.5 3B",
            fileName = "qwen2.5-3b-q4_k_m.gguf",
            filePath = "/tmp/qwen.gguf",
            fileSizeBytes = 1_800_000_000L,
            format = ModelFormat.GGUF,
            quantizationLabel = "Q4_K_M",
            supportsNativeVision = false,
            importedAtMillis = 0L
        )
        assertFalse(textOnlyGguf.isVisionCapable)

        val pairedVlGguf = textOnlyGguf.copy(
            mmprojFilePath = "/tmp/mmproj-f16.gguf",
            mmprojFileName = "mmproj-f16.gguf",
            mmprojSizeBytes = 400_000_000L
        )
        assertTrue(pairedVlGguf.isVisionCapable)
    }

    @Test
    fun `ram preflight flags oversized model against device memory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preprocessor = ImagePreprocessor(context)
        val coordinator = InferenceEngineCoordinator(
            context = context,
            liteRtEngine = LiteRtEngineImpl(context, preprocessor),
            llamaCppEngine = LlamaCppEngineImpl(context)
        )
        val hugeModel = LocalModelEntry(
            id = "huge",
            displayName = "Huge 70B",
            fileName = "llama-70b-q4_k_m.gguf",
            filePath = "/tmp/huge.gguf",
            fileSizeBytes = 48L * 1024L * 1024L * 1024L,
            format = ModelFormat.GGUF,
            quantizationLabel = "Q4_K_M",
            supportsNativeVision = false,
            importedAtMillis = 0L
        )
        val result = coordinator.checkRamPreflight(hugeModel, contextLength = 4096)
        assertFalse(result.canSafelyLoad)
        assertTrue(!result.warningMessage.isNullOrBlank())
    }

    @Test
    fun `thinking parser separates chunked think tags from final answer`() {
        val parser = ThinkingStreamParser(startInThought = false)
        val chunks = listOf("<th", "ink>Let's ", "verify 3 < 5", " first.</thi", "nk>\n\nYes, 3 < 5.")
        val thought = StringBuilder()
        val answer = StringBuilder()
        for (c in chunks) {
            parser.feed(c).forEach { part ->
                if (part.isThought) thought.append(part.text) else answer.append(part.text)
            }
        }
        parser.flush().forEach { part ->
            if (part.isThought) thought.append(part.text) else answer.append(part.text)
        }
        assertEquals("Let's verify 3 < 5 first.", thought.toString())
        assertEquals("Yes, 3 < 5.", answer.toString())
    }

    @Test
    fun `thinking parser handles template prefilled thought and gemma channel tags`() {
        val prefilled = ThinkingStreamParser(startInThought = true)
        val pThought = StringBuilder()
        val pAnswer = StringBuilder()
        listOf("Step 1. ", "Step 2.</think>\nFinal").forEach { chunk ->
            prefilled.feed(chunk).forEach { part ->
                if (part.isThought) pThought.append(part.text) else pAnswer.append(part.text)
            }
        }
        prefilled.flush().forEach { part ->
            if (part.isThought) pThought.append(part.text) else pAnswer.append(part.text)
        }
        assertEquals("Step 1. Step 2.", pThought.toString())
        assertEquals("Final", pAnswer.toString())

        val gemma = ThinkingStreamParser(startInThought = false)
        val gThought = StringBuilder()
        val gAnswer = StringBuilder()
        listOf("<|channel>thought", "\nReasoning here<channel|>\nAnswer!").forEach { chunk ->
            gemma.feed(chunk).forEach { part ->
                if (part.isThought) gThought.append(part.text) else gAnswer.append(part.text)
            }
        }
        gemma.flush().forEach { part ->
            if (part.isThought) gThought.append(part.text) else gAnswer.append(part.text)
        }
        assertEquals("\nReasoning here", gThought.toString())
        assertEquals("Answer!", gAnswer.toString())
    }

    @Test
    fun `thinking parser strips redundant open tag when prefilled and handles empty think block`() {
        val prefilled = ThinkingStreamParser(startInThought = true)
        val pThought = StringBuilder()
        val pAnswer = StringBuilder()
        listOf("<th", "ink>\nInspecting pixels.</think>\n\nIt is a laptop.").forEach { chunk ->
            prefilled.feed(chunk).forEach { part ->
                if (part.isThought) pThought.append(part.text) else pAnswer.append(part.text)
            }
        }
        prefilled.flush().forEach { part ->
            if (part.isThought) pThought.append(part.text) else pAnswer.append(part.text)
        }
        assertEquals("\nInspecting pixels.", pThought.toString())
        assertEquals("It is a laptop.", pAnswer.toString())

        val closedEmpty = ThinkingStreamParser(startInThought = false)
        val cThought = StringBuilder()
        val cAnswer = StringBuilder()
        listOf("<think>\n\n</think>\n\nDirect answer only.").forEach { chunk ->
            closedEmpty.feed(chunk).forEach { part ->
                if (part.isThought) cThought.append(part.text) else cAnswer.append(part.text)
            }
        }
        closedEmpty.flush().forEach { part ->
            if (part.isThought) cThought.append(part.text) else cAnswer.append(part.text)
        }
        assertEquals("\n\n", cThought.toString())
        assertEquals("Direct answer only.", cAnswer.toString())
    }

    @Test
    fun `markdown parser handles headings streaming code fences tables and lists`() {
        val md = """
            # Architecture
            
            > Offline first
            
            | Backend | Format |
            | --- | --- |
            | LiteRT-LM | `.litertlm` |
            | llama.cpp | `.gguf` |
            
            - Item one
            - Item two
            
            ```kotlin
            val x = 42
        """.trimIndent()

        val blocks = MarkdownParser.parseBlocks(md)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertEquals("Architecture", (blocks[0] as MarkdownBlock.Heading).text)
        assertTrue(blocks[1] is MarkdownBlock.Blockquote)
        assertTrue(blocks[2] is MarkdownBlock.Table)
        val table = blocks[2] as MarkdownBlock.Table
        assertEquals(listOf("Backend", "Format"), table.headers)
        assertEquals(2, table.rows.size)
        assertTrue(blocks[3] is MarkdownBlock.BulletList)
        assertTrue(blocks[4] is MarkdownBlock.CodeBlock)
        val codeBlock = blocks[4] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("val x = 42", codeBlock.code)
        assertFalse(codeBlock.isClosed)
    }

    @Test
    fun `inline markdown preserves snake_case and parses bold code and links`() {
        val annotated = buildInlineMarkdown(
            text = "Check **local_model_id** and `foo_bar` at [Docs](https://example.com)",
            inlineCodeBg = Color.DarkGray,
            linkColor = Color.Cyan
        )
        assertEquals("Check local_model_id and  foo_bar  at Docs", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
    }

    @Test
    fun `markdown transcript export includes user assistant and thought sections`() {
        val transcript = LocalMindViewModel.formatMarkdownTranscript(
            messages = listOf(
                ChatMessageUi(role = ChatRole.USER, text = "**Hello**"),
                ChatMessageUi(role = ChatRole.ASSISTANT, text = "Hi there!", thinkingText = "Polite greeting")
            ),
            modelName = "Qwen3 4B"
        )
        assertTrue(transcript.contains("# LocalMind Conversation"))
        assertTrue(transcript.contains("Qwen3 4B"))
        assertTrue(transcript.contains("### User"))
        assertTrue(transcript.contains("**Hello**"))
        assertTrue(transcript.contains("<summary>Thought Process</summary>"))
        assertTrue(transcript.contains("Polite greeting"))
        assertTrue(transcript.contains("### Assistant"))
    }

    @Test
    fun `pdf text extractor parses uncompressed and flate compressed streams`() {
        val plainPdf = "%PDF-1.4\n1 0 obj << /Length 36 >> stream\nBT (Hello LocalMind) Tj ET\nendstream\n%%EOF"
            .toByteArray(StandardCharsets.ISO_8859_1)
        val plainSections = PdfTextExtractor.extractPdfSections(plainPdf)
        assertEquals(1, plainSections.size)
        assertTrue(plainSections.first().text.contains("Hello LocalMind"))

        val content = "BT (Offline RAG works) Tj ET".toByteArray(StandardCharsets.ISO_8859_1)
        val deflater = Deflater().apply { setInput(content); finish() }
        val compressedOut = ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            compressedOut.write(buf, 0, count)
        }
        deflater.end()

        val header = "%PDF-1.4\n1 0 obj << /Filter /FlateDecode >> stream\n".toByteArray(StandardCharsets.ISO_8859_1)
        val footer = "\nendstream\n%%EOF".toByteArray(StandardCharsets.ISO_8859_1)
        val compressedPdf = header + compressedOut.toByteArray() + footer
        val flateSections = PdfTextExtractor.extractPdfSections(compressedPdf)
        assertEquals(1, flateSections.size)
        assertTrue(flateSections.first().text.contains("Offline RAG works"))
    }

    @Test
    fun `local rag bm25 ranks matching chunk highest`() {
        val c1 = DocumentChunkEntity(
            id = "1",
            documentId = "d1",
            chunkIndex = 0,
            sectionLabel = "Intro",
            content = "Sourdough baking requires flour, water, and starter fermentation.",
            normalizedTokens = LocalRagRepository.tokenize("Sourdough baking requires flour water starter fermentation").joinToString(" ")
        )
        val c2 = DocumentChunkEntity(
            id = "2",
            documentId = "d1",
            chunkIndex = 1,
            sectionLabel = "Engine",
            content = "KV cache quantization reduces RAM usage during transformer prefill.",
            normalizedTokens = LocalRagRepository.tokenize("KV cache quantization reduces RAM usage during transformer prefill").joinToString(" ")
        )
        val ranked = LocalRagRepository.scoreChunksBm25("How does KV cache quantization save RAM?", listOf(c1, c2), topK = 1)
        assertEquals(1, ranked.size)
        assertEquals("2", ranked.first().id)
    }

    @Test
    fun `room chat history repository persists threads, branches, and trim boundaries`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = LocalMindDatabase.createInMemory(context)
        try {
            val repo = ChatHistoryRepository(db)
            val thread = repo.ensureSeededAndGetInitialThread()

            val userMsg = ChatMessageUi(id = "u1", role = ChatRole.USER, text = "First prompt", turnOrder = 0, branchGroupId = "u1")
            repo.saveMessage(thread.id, userMsg, turnOrder = 0, branchGroupId = "u1")

            val rev1 = ChatMessageUi(id = "a1", role = ChatRole.ASSISTANT, text = "Draft 1", turnOrder = 1, branchGroupId = "grp_a1", revisionIndex = 0)
            repo.saveMessage(thread.id, rev1, turnOrder = 1, branchGroupId = "grp_a1", revisionIndex = 0, isActiveBranch = true)

            val rev2 = ChatMessageUi(id = "a2", role = ChatRole.ASSISTANT, text = "Draft 2", turnOrder = 1, branchGroupId = "grp_a1", revisionIndex = 1)
            repo.saveMessage(thread.id, rev2, turnOrder = 1, branchGroupId = "grp_a1", revisionIndex = 1, isActiveBranch = true)

            val loadedRev2 = repo.loadActiveBranchMessages(thread.id)
            assertEquals(2, loadedRev2.size)
            assertEquals("Draft 2", loadedRev2[1].text)
            assertEquals(1, loadedRev2[1].revisionIndex)
            assertEquals(listOf("a1", "a2"), loadedRev2[1].siblingIds)

            repo.selectBranch(thread.id, "grp_a1", "a1")
            val switchedBack = repo.loadActiveBranchMessages(thread.id, trimBeforeTurnOrder = 1)
            assertEquals("Draft 1", switchedBack[1].text)
            assertEquals(0, switchedBack[1].revisionIndex)
            assertTrue(switchedBack[0].isOutsideContextWindow)
            assertFalse(switchedBack[1].isOutsideContextWindow)
        } finally {
            db.close()
        }
    }

    @Test
    fun `huggingface hub parser extracts gguf litertlm files and voice strips markdown`() {
        val files = HuggingFaceHubRepository.parseModelFiles(
            repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            items = listOf(
                HfTreeApiItem(type = "file", path = "README.md", size = 2048L),
                HfTreeApiItem(type = "file", path = "qwen2.5-1.5b-q4_k_m.gguf", lfs = HfLfsMeta(980_000_000L)),
                HfTreeApiItem(type = "file", path = "mmproj-model-f16.gguf", lfs = HfLfsMeta(320_000_000L))
            )
        )
        assertEquals(2, files.size)
        assertFalse(files[0].isMmproj)
        assertEquals("Q4_K_M", files[0].quantizationLabel)
        assertTrue(files[1].isMmproj)

        val spoken = VoiceController.stripMarkdownForSpeech(
            "# Heading\nUse **Kotlin** with `Flow`.\n```kotlin\nval x = 1\n```"
        )
        assertFalse(spoken.contains("```"))
        assertFalse(spoken.contains("**"))
        assertTrue(spoken.contains("Code block omitted."))
        assertTrue(spoken.contains("Use Kotlin with Flow."))
    }

    @Test
    fun `telemetry sheet composes without runtime errors`() {
        val memoryStats = MemoryStats(
            totalDeviceRamBytes = 8L * 1024L * 1024L * 1024L,
            availableDeviceRamBytes = 3L * 1024L * 1024L * 1024L,
            nativeHeapAllocatedBytes = 256L * 1024L * 1024L,
            modelEstimatedFootprintBytes = 1024L * 1024L * 1024L,
            kvCacheUsedTokens = 34,
            kvCacheMaxTokens = 4096,
            activeAccelerator = HardwareAccelerator.CPU,
            activeBackend = "llama.cpp",
            isLowMemory = false
        )

        composeRule.setContent {
            LocalMindTheme(darkTheme = true) {
                TelemetryAndTuningSheet(
                    memoryStats = memoryStats,
                    livePrefillTokPerSec = 42.0,
                    liveDecodeTokPerSec = 14.5,
                    tuning = InferenceTuningConfig(),
                    themeMode = AppThemeMode.DARK,
                    autoLoadLastModel = true,
                    keepScreenOnDuringInference = true,
                    onUpdateTuning = {},
                    onUpdateThemeMode = {},
                    onSetAutoLoadLastModel = {},
                    onSetKeepScreenOn = {},
                    onDismiss = {},
                    estimatedContextTokens = 210,
                    canTrimContext = true
                )
            }
        }
        composeRule.onNodeWithTag("thinking_switch").assertExists()
        composeRule.onNodeWithTag("customize_done_button").assertExists()
        composeRule.onNodeWithTag("trim_context_button").assertExists()
    }

    @Test
    fun `model manager screen composes installed and discover hub tabs cleanly`() {
        val sampleModel = LocalModelEntry(
            id = "qwen35",
            displayName = "Qwen3.5 0.8B Q8_0",
            fileName = "Qwen3.5-0.8B-Q8_0.gguf",
            filePath = "/tmp/qwen35.gguf",
            fileSizeBytes = 900_000_000L,
            format = ModelFormat.GGUF,
            quantizationLabel = "Q8_0",
            supportsNativeVision = false,
            importedAtMillis = 0L
        )
        val memoryStats = MemoryStats(
            totalDeviceRamBytes = 8L * 1024L * 1024L * 1024L,
            availableDeviceRamBytes = 3L * 1024L * 1024L * 1024L,
            nativeHeapAllocatedBytes = 256L * 1024L * 1024L,
            modelEstimatedFootprintBytes = 1024L * 1024L * 1024L,
            kvCacheUsedTokens = 34,
            kvCacheMaxTokens = 4096,
            activeAccelerator = HardwareAccelerator.CPU,
            activeBackend = "llama.cpp",
            isLowMemory = false
        )
        val starterRepo = HfRepoSummary(
            repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            displayName = "Qwen2.5 0.5B Instruct",
            author = "Qwen",
            downloads = 1_250_000L,
            likes = 420,
            tags = listOf("gguf", "compact")
        )

        composeRule.setContent {
            LocalMindTheme(darkTheme = false) {
                ModelManagerScreen(
                    models = listOf(sampleModel),
                    activeModelId = sampleModel.id,
                    engineState = EngineLifecycleState.Idle,
                    memoryStats = memoryStats,
                    importProgress = null,
                    pendingPreflightWarning = null,
                    onImportModelUri = {},
                    onPairMmprojUri = { _, _ -> },
                    onLoadModel = { _, _ -> },
                    onUnloadModel = {},
                    onDeleteModel = {},
                    onDismissPreflightWarning = {},
                    onBackToWorkbench = {},
                    hubState = HubBrowserState(repos = listOf(starterRepo))
                )
            }
        }
        composeRule.onNodeWithTag("model_card_qwen35").assertExists()
        composeRule.onNodeWithTag("nav_workbench").assertExists()
        composeRule.onNodeWithTag("import_model_fab").assertExists()
        composeRule.onNodeWithTag("vault_tab_discover").performClick()
        composeRule.onNodeWithTag("hub_search_input").assertExists()
        composeRule.onNodeWithTag("vault_tab_installed").performClick()
        composeRule.onNodeWithTag("model_card_qwen35").assertExists()
    }

    @Test
    fun `threads hub screen renders threads and workspaces views cleanly`() {
        val ws = WorkspaceEntity(
            id = "workspace_general",
            name = "General",
            systemPrompt = "Helpful local AI",
            isDefault = true
        )
        val thread = ChatThreadEntity(
            id = "t1",
            workspaceId = ws.id,
            title = "Offline RAG design",
            lastModelName = "Qwen2.5 1.5B"
        )
        composeRule.setContent {
            LocalMindTheme(darkTheme = false) {
                ThreadsHubScreen(
                    threads = listOf(thread),
                    workspaces = listOf(ws),
                    activeThreadId = "t1",
                    activeWorkspaceId = ws.id,
                    onSelectThread = {},
                    onCreateThread = { _, _ -> },
                    onRenameThread = { _, _ -> },
                    onTogglePinThread = {},
                    onToggleArchiveThread = {},
                    onDeleteThread = {},
                    onSelectWorkspace = {},
                    onCreateWorkspace = { _, _ -> },
                    onDeleteWorkspace = {},
                    onOpenModelVault = {},
                    onBackToWorkbench = {}
                )
            }
        }
        composeRule.onNodeWithTag("threads_hub_title").assertExists()
        composeRule.onNodeWithTag("new_thread_fab").assertExists()
        composeRule.onNodeWithTag("thread_row_t1").assertExists()
        composeRule.onNodeWithTag("hub_tab_workspaces").performClick()
        composeRule.onNodeWithTag("workspace_row_workspace_general").assertExists()
        composeRule.onNodeWithTag("add_workspace_row").assertExists()
        composeRule.onNodeWithTag("hub_tab_threads").performClick()
        composeRule.onNodeWithTag("thread_row_t1").assertExists()
    }

    @Test
    fun `chat screen composes in light daylight generating and branching states without runtime errors`() {
        val sampleModel = LocalModelEntry(
            id = "qwen35",
            displayName = "Qwen3.5 0.8B Q8_0",
            fileName = "Qwen3.5-0.8B-Q8_0.gguf",
            filePath = "/tmp/qwen35.gguf",
            fileSizeBytes = 900_000_000L,
            format = ModelFormat.GGUF,
            quantizationLabel = "Q8_0",
            supportsNativeVision = false,
            importedAtMillis = 0L
        )
        val memoryStats = MemoryStats(
            totalDeviceRamBytes = 8L * 1024L * 1024L * 1024L,
            availableDeviceRamBytes = 3L * 1024L * 1024L * 1024L,
            nativeHeapAllocatedBytes = 256L * 1024L * 1024L,
            modelEstimatedFootprintBytes = 1024L * 1024L * 1024L,
            kvCacheUsedTokens = 34,
            kvCacheMaxTokens = 4096,
            activeAccelerator = HardwareAccelerator.CPU,
            activeBackend = "llama.cpp",
            isLowMemory = false
        )

        composeRule.setContent {
            LocalMindTheme(darkTheme = false) {
                ChatScreen(
                    uiState = LocalMindUiState(
                        memoryStats = memoryStats,
                        messages = listOf(
                            ChatMessageUi(role = ChatRole.USER, text = "Hi"),
                            ChatMessageUi(
                                id = "a_rev2",
                                role = ChatRole.ASSISTANT,
                                text = "```kotlin\nval x = 1\n```",
                                thinkingText = "Checking",
                                isStreaming = true,
                                revisionIndex = 1,
                                siblingIds = listOf("a_rev1", "a_rev2"),
                                citations = listOf(
                                    DocumentCitationUi(
                                        documentId = "doc1",
                                        documentName = "notes.pdf",
                                        sectionLabel = "Stream 1 • #1",
                                        snippet = "Key excerpt"
                                    )
                                )
                            )
                        )
                    ),
                    engineState = EngineLifecycleState.Generating(
                        LoadedModelInfo(
                            modelName = sampleModel.displayName,
                            format = ModelFormat.GGUF,
                            activeAccelerator = HardwareAccelerator.CPU,
                            backendDescription = "llama.cpp CPU",
                            visionSupported = false,
                            loadDurationMs = 120L
                        )
                    ),
                    activeModel = sampleModel,
                    onSendPrompt = {},
                    onStopGeneration = {},
                    onResetConversation = {},
                    onRegenerateLastResponse = {},
                    onEditLastUserMessage = {},
                    onDeleteMessage = {},
                    onBuildMarkdownTranscript = { "" },
                    onShowStatusBanner = {},
                    onAttachImageUri = {},
                    onClearPendingImage = {},
                    onToggleThinking = {},
                    onOpenModelVault = {},
                    onOpenTelemetrySheet = {}
                )
            }
        }
        composeRule.onNodeWithTag("thinking_toggle_chip").assertExists()
        composeRule.onNodeWithTag("stop_generation_button").assertExists()
        composeRule.onNodeWithTag("nav_vault").assertExists()
        composeRule.onNodeWithTag("nav_threads").assertExists()
        composeRule.onNodeWithTag("telemetry_button").assertExists()
        composeRule.onNodeWithTag("markdown_code_block").assertExists()
        composeRule.onNodeWithTag("citation_chip_0").assertExists()
        composeRule.onNodeWithTag("context_window_gauge_pill").assertExists()
    }

    @Test
    fun `checkpointStreamingMessage retains partial thinking and answer with interrupted flag and sanitizes non-finite telemetry`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = LocalMindDatabase.createInMemory(context)
        try {
            val repo = ChatHistoryRepository(db)
            val thread = repo.ensureSeededAndGetInitialThread()

            val initial = ChatMessageUi(
                id = "a_partial",
                role = ChatRole.ASSISTANT,
                text = "",
                thinkingText = "",
                isInterrupted = true,
                telemetry = GenerationTelemetry(
                    prefillTokens = 12,
                    prefillTimeMs = 10L,
                    prefillTokensPerSec = Double.POSITIVE_INFINITY,
                    decodeTokens = 4,
                    decodeTimeMs = 0L,
                    decodeTokensPerSec = Double.NaN,
                    activeAccelerator = HardwareAccelerator.CPU,
                    backendLabel = "llama.cpp",
                    thoughtTokens = 4
                ),
                turnOrder = 0,
                branchGroupId = "grp_partial"
            )
            repo.saveMessage(thread.id, initial, turnOrder = 0, branchGroupId = "grp_partial")

            repo.checkpointStreamingMessage(
                threadId = thread.id,
                messageId = "a_partial",
                text = "Partial answer so far",
                thinkingText = "Reasoning trace before crash",
                isInterrupted = true
            )

            val loaded = repo.loadActiveBranchMessages(thread.id)
            assertEquals(1, loaded.size)
            assertEquals("Partial answer so far", loaded.first().text)
            assertEquals("Reasoning trace before crash", loaded.first().thinkingText)
            assertTrue(loaded.first().isInterrupted)
            assertEquals(0.0, loaded.first().telemetry?.prefillTokensPerSec ?: -1.0, 0.001)
            assertEquals(0.0, loaded.first().telemetry?.decodeTokensPerSec ?: -1.0, 0.001)
        } finally {
            db.close()
        }
    }

    @Test
    fun `chat screen renders interrupted badge and warn-first low memory banner`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = InferenceEngineCoordinator(
            context = context,
            liteRtEngine = LiteRtEngineImpl(context, ImagePreprocessor(context)),
            llamaCppEngine = LlamaCppEngineImpl(context)
        )
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertNull(coordinator.memoryPressureWarning.value)

        var dismissed = false
        composeRule.setContent {
            LocalMindTheme(darkTheme = true) {
                ChatScreen(
                    uiState = LocalMindUiState(
                        memoryPressureWarning = MemoryPressureWarning(
                            level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                            severityLabel = "Critical low memory",
                            availableRamMb = 412L,
                            totalRamMb = 8192L
                        ),
                        messages = listOf(
                            ChatMessageUi(role = ChatRole.USER, text = "Explain KV cache", turnOrder = 0),
                            ChatMessageUi(
                                id = "a_interrupted",
                                role = ChatRole.ASSISTANT,
                                text = "Partial reply retained...",
                                thinkingText = "Checking memory pressure",
                                isStreaming = false,
                                isInterrupted = true,
                                turnOrder = 1
                            )
                        )
                    ),
                    engineState = EngineLifecycleState.Idle,
                    activeModel = null,
                    onSendPrompt = {},
                    onStopGeneration = {},
                    onResetConversation = {},
                    onRegenerateLastResponse = {},
                    onEditLastUserMessage = {},
                    onDeleteMessage = {},
                    onBuildMarkdownTranscript = { "" },
                    onShowStatusBanner = {},
                    onAttachImageUri = {},
                    onClearPendingImage = {},
                    onToggleThinking = {},
                    onOpenModelVault = {},
                    onOpenTelemetrySheet = {},
                    onDismissMemoryWarning = { dismissed = true }
                )
            }
        }
        composeRule.onNodeWithTag("interrupted_badge").assertExists()
        composeRule.onNodeWithTag("low_memory_warning_banner").assertExists()
        composeRule.onNodeWithTag("thinking_block").performClick()
        composeRule.onNodeWithTag("memory_warning_dismiss_button").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `curated LocalMindIcons vectors instantiate with valid 24dp bounds`() {
        val icons = listOf(
            com.example.ui.components.LocalMindIcons.AddPhotoAlternate,
            com.example.ui.components.LocalMindIcons.Archive,
            com.example.ui.components.LocalMindIcons.ArrowDownward,
            com.example.ui.components.LocalMindIcons.ArrowUpward,
            com.example.ui.components.LocalMindIcons.AutoAwesome,
            com.example.ui.components.LocalMindIcons.Bedtime,
            com.example.ui.components.LocalMindIcons.CameraAlt,
            com.example.ui.components.LocalMindIcons.ChatBubble,
            com.example.ui.components.LocalMindIcons.ChatBubbleOutline,
            com.example.ui.components.LocalMindIcons.CloudDownload,
            com.example.ui.components.LocalMindIcons.ContentCopy,
            com.example.ui.components.LocalMindIcons.ContentCut,
            com.example.ui.components.LocalMindIcons.CreateNewFolder,
            com.example.ui.components.LocalMindIcons.DeleteOutline,
            com.example.ui.components.LocalMindIcons.Description,
            com.example.ui.components.LocalMindIcons.ExpandLess,
            com.example.ui.components.LocalMindIcons.ExpandMore,
            com.example.ui.components.LocalMindIcons.FolderOpen,
            com.example.ui.components.LocalMindIcons.Forum,
            com.example.ui.components.LocalMindIcons.ForumOutlined,
            com.example.ui.components.LocalMindIcons.GraphicEq,
            com.example.ui.components.LocalMindIcons.Memory,
            com.example.ui.components.LocalMindIcons.Mic,
            com.example.ui.components.LocalMindIcons.MoreHoriz,
            com.example.ui.components.LocalMindIcons.Palette,
            com.example.ui.components.LocalMindIcons.Pause,
            com.example.ui.components.LocalMindIcons.Psychology,
            com.example.ui.components.LocalMindIcons.PushPin,
            com.example.ui.components.LocalMindIcons.Replay,
            com.example.ui.components.LocalMindIcons.Stop,
            com.example.ui.components.LocalMindIcons.Storage,
            com.example.ui.components.LocalMindIcons.StorageOutlined,
            com.example.ui.components.LocalMindIcons.Tune,
            com.example.ui.components.LocalMindIcons.Unarchive,
            com.example.ui.components.LocalMindIcons.Visibility,
            com.example.ui.components.LocalMindIcons.VolumeUp,
            com.example.ui.components.LocalMindIcons.WarningAmber
        )
        assertEquals(37, icons.size)
        icons.forEach { icon ->
            assertEquals(24f, icon.viewportWidth, 0.01f)
            assertEquals(24f, icon.viewportHeight, 0.01f)
        }
    }
}
