package com.example.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Manages offline-capable Android SpeechRecognizer dictation and TextToSpeech read-aloud.
 */
class VoiceController(context: Context) {

    private val appContext = context.applicationContext

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var pendingSpeakPair: Pair<String, String>? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    fun isSpeechRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startDictation(onFinalTranscript: (String) -> Unit) {
        if (!isSpeechRecognitionAvailable()) {
            _voiceError.value = "Speech recognition service unavailable on this device."
            return
        }
        stopDictation()
        _voiceError.value = null
        _partialTranscript.value = ""

        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _isListening.value = false
            }

            override fun onError(error: Int) {
                _isListening.value = false
                _partialTranscript.value = ""
                if (error != SpeechRecognizer.ERROR_CLIENT &&
                    error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    _voiceError.value = "Dictation error (code $error)"
                }
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                _partialTranscript.value = ""
                if (text.isNotEmpty()) {
                    onFinalTranscript(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    _partialTranscript.value = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
        _isListening.value = true
    }

    fun stopDictation() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
        _isListening.value = false
        _partialTranscript.value = ""
    }

    fun toggleSpeakMessage(messageId: String, markdownText: String) {
        if (_speakingMessageId.value == messageId) {
            stopSpeaking()
            return
        }
        val cleanText = stripMarkdownForSpeech(markdownText)
        if (cleanText.isBlank()) return

        if (tts == null) {
            pendingSpeakPair = messageId to cleanText
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInitialized = true
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _speakingMessageId.value = utteranceId
                        }

                        override fun onDone(utteranceId: String?) {
                            if (_speakingMessageId.value == utteranceId) {
                                _speakingMessageId.value = null
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (_speakingMessageId.value == utteranceId) {
                                _speakingMessageId.value = null
                            }
                        }
                    })
                    pendingSpeakPair?.let { (id, text) ->
                        pendingSpeakPair = null
                        speakNow(id, text)
                    }
                } else {
                    _voiceError.value = "Text-to-Speech engine unavailable"
                    pendingSpeakPair = null
                }
            }
        } else if (ttsInitialized) {
            speakNow(messageId, cleanText)
        } else {
            pendingSpeakPair = messageId to cleanText
        }
    }

    private fun speakNow(messageId: String, cleanText: String) {
        _speakingMessageId.value = messageId
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, messageId)
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
        pendingSpeakPair = null
        _speakingMessageId.value = null
    }

    fun clearError() {
        _voiceError.value = null
    }

    fun release() {
        stopDictation()
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
        ttsInitialized = false
    }

    companion object {
        internal fun stripMarkdownForSpeech(markdown: String): String {
            return markdown
                .replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
                .replace(Regex("[*_~>#|]+"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
        }
    }
}
