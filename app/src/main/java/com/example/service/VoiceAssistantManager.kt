package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceAssistantManager(
    private val context: Context,
    private val onCommandReceived: (String) -> Unit
) : RecognitionListener, TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking = _isSpeaking.asStateFlow()

    private val _spokenText = MutableStateFlow("")
    val spokenText = _spokenText.asStateFlow()

    private val _rmsAudioLevel = MutableStateFlow(0f)
    val rmsAudioLevel = _rmsAudioLevel.asStateFlow()

    private val _isContinuousModeActive = MutableStateFlow(true)
    val isContinuousModeActive = _isContinuousModeActive.asStateFlow()

    private var isDestroyed = false

    // Deduplication tracking: Ignore duplicate speech inputs within 2000ms
    private var lastRecognizedText: String = ""
    private var lastRecognizedTimestamp: Long = 0L

    private val restartListeningRunnable = Runnable {
        if (!isDestroyed && _isContinuousModeActive.value && !_isSpeaking.value && !_isListening.value && (tts?.isSpeaking != true)) {
            startListening()
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext, this)
        initSpeechRecognizer()
    }

    private fun initSpeechRecognizer() {
        if (isDestroyed) return
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@VoiceAssistantManager)
            }
        }
    }

    fun setContinuousMode(enabled: Boolean) {
        _isContinuousModeActive.value = enabled
        if (enabled) {
            if (!_isSpeaking.value && !_isListening.value && (tts?.isSpeaking != true)) {
                startListening()
            }
        } else {
            mainHandler.removeCallbacks(restartListeningRunnable)
            stopListening()
        }
    }

    fun startListening() {
        if (isDestroyed || _isSpeaking.value || (tts?.isSpeaking == true)) {
            return
        }

        mainHandler.post {
            if (isDestroyed || _isSpeaking.value || (tts?.isSpeaking == true)) {
                return@post
            }

            try {
                if (speechRecognizer == null) {
                    initSpeechRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                }

                speechRecognizer?.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                android.util.Log.e("VoiceManager", "Error starting speech recognition", e)
                _isListening.value = false
                scheduleRestartIfContinuous(1000)
            }
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.stopListening()
            _isListening.value = false
            _rmsAudioLevel.value = 0f
        } catch (e: Exception) {
            android.util.Log.e("VoiceManager", "Error stopping speech recognition", e)
        }
    }

    /**
     * TTS Echo Cancellation & Smart Queueing:
     * When TTS starts speaking, immediately stop/mute microphone.
     * When TTS is done, wait exactly 300ms before restarting microphone listening.
     *
     * @param queueMode TextToSpeech.QUEUE_FLUSH (replaces speech) or TextToSpeech.QUEUE_ADD (appends speech)
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH, onComplete: (() -> Unit)? = null) {
        val cleanText = text.trim()
        if (!isTtsReady || cleanText.isBlank() || isDestroyed) {
            _isSpeaking.value = false
            onComplete?.invoke()
            scheduleRestartIfContinuous(300)
            return
        }

        mainHandler.removeCallbacks(restartListeningRunnable)
        stopListening()

        _isSpeaking.value = true

        val utteranceId = "AI_REPLY_${System.currentTimeMillis()}_${cleanText.hashCode()}"
        if (onComplete != null) {
            pendingCallbacks[utteranceId] = onComplete
        }

        ensureUtteranceListener()

        tts?.speak(cleanText, queueMode, null, utteranceId)
    }

    private val pendingCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private var isListenerAttached = false

    private fun ensureUtteranceListener() {
        if (isListenerAttached) return
        isListenerAttached = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                mainHandler.post {
                    stopListening()
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) {
                    pendingCallbacks.remove(utteranceId)?.invoke()
                }
                // If TTS is no longer speaking anything in the queue
                if (tts?.isSpeaking != true) {
                    _isSpeaking.value = false
                    scheduleRestartIfContinuous(300)
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId != null) {
                    pendingCallbacks.remove(utteranceId)?.invoke()
                }
                if (tts?.isSpeaking != true) {
                    _isSpeaking.value = false
                    scheduleRestartIfContinuous(300)
                }
            }
        })
    }

    fun stopSpeaking() {
        tts?.stop()
        pendingCallbacks.clear()
        _isSpeaking.value = false
        scheduleRestartIfContinuous(300)
    }

    private fun scheduleRestartIfContinuous(delayMs: Long = 300) {
        if (isDestroyed || !_isContinuousModeActive.value) return

        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed(restartListeningRunnable, delayMs)
    }

    fun destroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            android.util.Log.e("VoiceManager", "Destroy error", e)
        }
    }

    // ----------------- RecognitionListener Callbacks -----------------

    override fun onReadyForSpeech(params: Bundle?) {
        if (_isSpeaking.value || tts?.isSpeaking == true) {
            stopListening()
            return
        }
        _isListening.value = true
    }

    override fun onBeginningOfSpeech() {
        if (_isSpeaking.value || tts?.isSpeaking == true) {
            stopListening()
            return
        }
        _isListening.value = true
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (!_isSpeaking.value) {
            _rmsAudioLevel.value = (rmsdB.coerceIn(0f, 10f) / 10f)
        }
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        _isListening.value = false
    }

    override fun onError(error: Int) {
        _isListening.value = false
        _rmsAudioLevel.value = 0f
        android.util.Log.d("VoiceManager", "Speech recognition error code: $error")

        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            initSpeechRecognizer()
        }
        scheduleRestartIfContinuous(500)
    }

    override fun onResults(results: Bundle?) {
        _isListening.value = false
        _rmsAudioLevel.value = 0f

        // If speech recognition fired while TTS was still actively outputting sound, discard to prevent echo
        if (_isSpeaking.value || tts?.isSpeaking == true) {
            scheduleRestartIfContinuous(300)
            return
        }

        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val text = matches[0].trim()
            if (text.isNotBlank()) {
                val now = System.currentTimeMillis()
                // Deduplication: Ignore identical speech input within 2 seconds
                if (text.equals(lastRecognizedText, ignoreCase = true) && (now - lastRecognizedTimestamp) < 2000L) {
                    android.util.Log.d("VoiceManager", "Ignored duplicate voice input within 2s: $text")
                    scheduleRestartIfContinuous(300)
                    return
                }

                lastRecognizedText = text
                lastRecognizedTimestamp = now
                _spokenText.value = text
                onCommandReceived(text)
            } else {
                scheduleRestartIfContinuous(300)
            }
        } else {
            scheduleRestartIfContinuous(300)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!_isSpeaking.value) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                _spokenText.value = matches[0]
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ----------------- TextToSpeech.OnInitListener -----------------

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("tr", "TR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.05f)
            isTtsReady = true
        }
    }
}
