package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.example.data.engine.InferenceEngineCoordinator
import com.example.data.engine.LiteRtEngineImpl
import com.example.data.engine.LlamaCppEngineImpl
import com.example.data.hub.HuggingFaceHubRepository
import com.example.data.local.ChatHistoryRepository
import com.example.data.local.LocalMindDatabase
import com.example.data.media.ImagePreprocessor
import com.example.data.rag.LocalRagRepository
import com.example.data.storage.LocalModelRepository
import com.example.data.storage.PreferencesRepository
import com.example.data.voice.VoiceController

class LocalMindApplication : Application(), ComponentCallbacks2 {

    lateinit var imagePreprocessor: ImagePreprocessor
        private set

    lateinit var modelRepository: LocalModelRepository
        private set

    lateinit var preferencesRepository: PreferencesRepository
        private set

    lateinit var database: LocalMindDatabase
        private set

    lateinit var chatHistoryRepository: ChatHistoryRepository
        private set

    lateinit var localRagRepository: LocalRagRepository
        private set

    lateinit var huggingFaceHubRepository: HuggingFaceHubRepository
        private set

    lateinit var voiceController: VoiceController
        private set

    lateinit var engineCoordinator: InferenceEngineCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        imagePreprocessor = ImagePreprocessor(this)
        modelRepository = LocalModelRepository(this)
        preferencesRepository = PreferencesRepository(this)
        database = LocalMindDatabase.create(this)
        chatHistoryRepository = ChatHistoryRepository(database)
        localRagRepository = LocalRagRepository(this, database)
        huggingFaceHubRepository = HuggingFaceHubRepository(this, modelRepository)
        voiceController = VoiceController(this)

        val liteRtEngine = LiteRtEngineImpl(this, imagePreprocessor)
        val llamaCppEngine = LlamaCppEngineImpl(this)
        engineCoordinator = InferenceEngineCoordinator(
            context = this,
            liteRtEngine = liteRtEngine,
            llamaCppEngine = llamaCppEngine
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::engineCoordinator.isInitialized) {
            engineCoordinator.onTrimMemory(level)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::engineCoordinator.isInitialized) {
            engineCoordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
