package com.example.agent.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Merkezi Agent Yaşam Döngüsü Yöneticisi (Central Agent Lifecycle Manager).
 * Tüm otonom görevlerin (Device Task, ReAct Loop, Visual Opener, Structured Exploration)
 * durumlarını (AgentState), aktif oturumunu (AgentTaskSession) ve eşzamanlılık kontrolünü tek bir noktadan yönetir.
 */
object AgentLifecycleManager {
    private const val TAG = "AgentLifecycle"
    private val mutex = Mutex()

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _currentSession = MutableStateFlow<AgentTaskSession?>(null)
    val currentSession: StateFlow<AgentTaskSession?> = _currentSession.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    /**
     * Yeni bir görev oturumu başlatır.
     * Halihazırda yürütülen bir görev varsa, onu güvenli şekilde iptal eder (Concurrent Task Prevention).
     */
    suspend fun startSession(
        taskGoal: String,
        budget: TaskBudget = TaskBudget(),
        initialState: AgentState = AgentState.PLANNING
    ): AgentTaskSession = mutex.withLock {
        val existingSession = _currentSession.value
        if (existingSession != null && !existingSession.isFinished) {
            Log.w(TAG, "Mevcut görev (${existingSession.taskId}) iptal edilerek yeni görev başlatılıyor: '$taskGoal'")
            val cancelled = existingSession.copy(
                currentState = AgentState.CANCELLED,
                isCancelled = true,
                cancellationReason = "Yeni bir görev başlatıldığı için önceki görev sonlandırıldı."
            )
            _currentSession.value = cancelled
            _agentState.value = AgentState.CANCELLED
        }

        val newSession = AgentTaskSession(
            taskGoal = taskGoal,
            budget = budget,
            currentState = initialState,
            currentStep = 0
        )

        val initialStatus = getDefaultStatusForState(initialState)
        _currentSession.value = newSession
        _agentState.value = initialState
        _statusText.value = initialStatus
        Log.i(TAG, "Yeni AgentTaskSession başlatıldı: id=${newSession.taskId}, goal='$taskGoal', state=$initialState")
        return newSession
    }

    /**
     * Mevcut oturumun durumunu güvenli şekilde günceller.
     * Geçersiz geçişleri engeller (örn. CANCELLED -> ACTING, FAILED -> ACTING).
     */
    suspend fun transitionState(
        taskId: String,
        newState: AgentState,
        step: Int? = null,
        customStatus: String? = null
    ): Boolean = mutex.withLock {
        val session = _currentSession.value
        if (session == null || session.taskId != taskId) {
            Log.w(TAG, "Geçersiz oturum veya eski taskId için transition reddedildi: current=${session?.taskId}, target=$taskId")
            return false
        }

        if (session.isFinished && !newState.isTerminal) {
            Log.w(TAG, "Sonlanmış oturum (${session.currentState}) için geçersiz durum geçişi: $newState reddedildi.")
            return false
        }

        if (!isValidTransition(session.currentState, newState)) {
            Log.w(TAG, "Geçersiz durum geçişi: ${session.currentState} -> $newState reddedildi.")
            return false
        }

        val updatedStep = step ?: session.currentStep
        val status = customStatus ?: getDefaultStatusForState(newState)

        val updatedSession = session.copy(
            currentState = newState,
            currentStep = updatedStep
        )

        _currentSession.value = updatedSession
        _agentState.value = newState
        _statusText.value = status
        Log.d(TAG, "State Transition: [${session.currentState} -> $newState] (step=$updatedStep, status='$status')")
        return true
    }

    /**
     * Görevi başarıyla tamamlar.
     */
    suspend fun completeSession(
        taskId: String,
        summary: String = "Görev başarıyla tamamlandı."
    ): Boolean = mutex.withLock {
        val session = _currentSession.value
        if (session == null || session.taskId != taskId) return false

        val completedSession = session.copy(
            currentState = AgentState.COMPLETED,
            resultSummary = summary
        )
        _currentSession.value = completedSession
        _agentState.value = AgentState.COMPLETED
        _statusText.value = summary
        Log.i(TAG, "AgentTaskSession tamamlandı: id=$taskId, summary='$summary'")
        return true
    }

    /**
     * Görevi hata ile sonlandırır.
     */
    suspend fun failSession(
        taskId: String,
        errorMessage: String
    ): Boolean = mutex.withLock {
        val session = _currentSession.value
        if (session == null || session.taskId != taskId) return false

        val failedSession = session.copy(
            currentState = AgentState.FAILED,
            errorMessage = errorMessage
        )
        _currentSession.value = failedSession
        _agentState.value = AgentState.FAILED
        val friendlyMessage = if (errorMessage.isNotBlank()) "Görev başarısız: $errorMessage" else "Görev başarısız."
        _statusText.value = friendlyMessage
        Log.w(TAG, "AgentTaskSession başarısız oldu: id=$taskId, error='$errorMessage'")
        return true
    }

    /**
     * Görevi iptal eder.
     */
    suspend fun cancelCurrentSession(reason: String = "Kullanıcı durdurdu."): Boolean = mutex.withLock {
        val session = _currentSession.value ?: return false
        if (session.isFinished) return false

        val cancelledSession = session.copy(
            currentState = AgentState.CANCELLED,
            isCancelled = true,
            cancellationReason = reason
        )
        _currentSession.value = cancelledSession
        _agentState.value = AgentState.CANCELLED
        _statusText.value = "Görev durduruldu."
        Log.i(TAG, "AgentTaskSession iptal edildi: id=${session.taskId}, reason='$reason'")
        return true
    }

    /**
     * Oturumu temizler ve agent'ı IDLE durumuna döndürür.
     */
    suspend fun resetToIdle() = mutex.withLock {
        _currentSession.value = null
        _agentState.value = AgentState.IDLE
        _statusText.value = ""
    }

    /**
     * İki durum arasındaki geçişin geçerli olup olmadığını denetler.
     */
    fun isValidTransition(from: AgentState, to: AgentState): Boolean {
        if (from == to) return true
        // Terminal durumlar (COMPLETED, FAILED, CANCELLED) başka bir çalışma durumuna geçemez
        if (from.isTerminal) return false

        return when (from) {
            AgentState.IDLE -> to == AgentState.PLANNING || to == AgentState.OBSERVING || to == AgentState.CANCELLED
            AgentState.PLANNING -> to == AgentState.OBSERVING || to == AgentState.ACTING || to == AgentState.COMPLETED || to == AgentState.FAILED || to == AgentState.CANCELLED
            AgentState.OBSERVING -> to == AgentState.PLANNING || to == AgentState.ACTING || to == AgentState.COMPLETED || to == AgentState.FAILED || to == AgentState.CANCELLED
            AgentState.ACTING -> to == AgentState.VERIFYING || to == AgentState.OBSERVING || to == AgentState.COMPLETED || to == AgentState.FAILED || to == AgentState.CANCELLED
            AgentState.VERIFYING -> to == AgentState.OBSERVING || to == AgentState.PLANNING || to == AgentState.RECOVERING || to == AgentState.COMPLETED || to == AgentState.FAILED || to == AgentState.CANCELLED
            AgentState.RECOVERING -> to == AgentState.ACTING || to == AgentState.OBSERVING || to == AgentState.PLANNING || to == AgentState.FAILED || to == AgentState.CANCELLED
            else -> false
        }
    }

    /**
     * Durum için kullanıcı dostu varsayılan açıklama üretir.
     */
    fun getDefaultStatusForState(state: AgentState): String {
        return when (state) {
            AgentState.IDLE -> ""
            AgentState.PLANNING -> "Planlanıyor..."
            AgentState.OBSERVING -> "Ekran inceleniyor..."
            AgentState.ACTING -> "Eylem gerçekleştiriliyor..."
            AgentState.VERIFYING -> "Sonuç kontrol ediliyor..."
            AgentState.RECOVERING -> "Alternatif yol aranıyor..."
            AgentState.COMPLETED -> "Görev tamamlandı"
            AgentState.FAILED -> "Görev başarısız"
            AgentState.CANCELLED -> "Görev durduruldu"
        }
    }
}
