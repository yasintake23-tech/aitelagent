package com.example.agent.core

import android.graphics.PointF
import com.example.ai.AIAgentScreenReasoner
import com.example.ai.AgentActionType
import com.example.ai.AgentStepDecision
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import java.util.Locale

/**
 * Keşif sırasında zararlı veya riskli olabilecek işlemleri filtreleyen güvenlik motoru.
 */
object ExplorationSafety {
    private val DANGEROUS_KEYWORDS = listOf(
        "sil", "kaldır", "fabrika", "sıfırla", "format", "satın al", "öde", "ödeme",
        "abone ol", "şifre", "password", "pin", "hesabı sil", "hesabı kapat",
        "yetki ver", "admin", "delete", "uninstall", "reset", "purchase", "pay",
        "buy", "factory reset", "erase", "gönder", "send", "kart", "credit card",
        "kart ekle", "para gönder", "transfer", "onayla ve öde"
    )

    /**
     * Verilen metin veya tanımlayıcının güvenli olup olmadığını kontrol eder.
     */
    fun isSafeText(text: String): Boolean {
        if (text.isBlank()) return true
        val lower = text.lowercase(Locale("tr", "TR"))
        return DANGEROUS_KEYWORDS.none { lower.contains(it) }
    }

    /**
     * Ekrandaki bir UI düğümünün tıklanması/etkileşime girilmesi güvenli mi kontrol eder.
     */
    fun isSafeNode(node: ScreenNodeData): Boolean {
        if (!isSafeText(node.text)) return false
        if (!isSafeText(node.contentDescription)) return false
        if (!isSafeText(node.viewId)) return false
        return true
    }
}

/**
 * Otonom Keşif oturumlarında her adımda atılacak en anlamlı ve güvenli adımı belirleyen karar motoru.
 * Rastgele hareketleri kesinlikle engeller; Reasoner, semantik düğüm analizi ve yapısal kurtarma kullanır.
 */
object ExplorationDecisionMaker {

    /**
     * Bir düğüm için tekil semantik imza üretir.
     */
    fun generateNodeSignature(packageName: String, node: ScreenNodeData): String {
        val cleanPkg = packageName.trim().lowercase(Locale.ROOT)
        val cleanClass = node.className.substringAfterLast(".")
        val cleanText = node.text.trim().take(25).lowercase(Locale("tr", "TR"))
        val cleanDesc = node.contentDescription.trim().take(25).lowercase(Locale("tr", "TR"))
        val cleanId = node.viewId.substringAfterLast("/").take(25)
        return "$cleanPkg/$cleanClass/id=$cleanId/t=$cleanText/d=$cleanDesc"
    }

    /**
     * Mevcut ekran gözlemine ve keşif oturumu geçmişine göre sıradaki en uygun adımı kararlaştırır.
     */
    suspend fun decideNextExplorationAction(
        snapshot: ScreenSnapshot,
        session: ExplorationTaskSession,
        reasoner: AIAgentScreenReasoner? = null,
        memories: List<MemoryEntryEntity> = emptyList(),
        profile: UserProfileEntity? = null
    ): AgentStepDecision {
        val currentFingerprint = ScreenFingerprintGenerator.generateFingerprint(snapshot)
        val currentPkg = snapshot.packageName

        // 1. STUCK RECOVERY: Aynı ekranda takılma (consecutiveSameStateCount >= 2 veya failure >= 2)
        if (session.consecutiveSameStateCount >= 2 || session.consecutiveFailures >= 2) {
            val unvisitedSafeNode = findNextUnvisitedSafeNode(snapshot, session)
            if (unvisitedSafeNode != null) {
                val sig = generateNodeSignature(currentPkg, unvisitedSafeNode.node)
                session.visitedNodeSignatures.add(sig)
                val targetName = unvisitedSafeNode.node.text.ifBlank { unvisitedSafeNode.node.contentDescription.ifBlank { "Menü ögesi" } }
                return AgentStepDecision(
                    actionType = AgentActionType.CLICK_NODE,
                    thought = "Aynı ekranda takılma algılandı. Henüz denenmemiş alternatif güvenli ögeye tıklanıyor: $targetName",
                    speechStatus = "$targetName açılıyor",
                    targetIndex = unvisitedSafeNode.index,
                    targetText = targetName,
                    coordinates = PointF(
                        unvisitedSafeNode.node.bounds.centerX().toFloat(),
                        unvisitedSafeNode.node.bounds.centerY().toFloat()
                    )
                )
            }

            // Eğer tıklanacak denenmemiş öge kalmadıysa, sayfayı aşağı kaydır
            val lastAction = session.actionHistory.lastOrNull()
            if (lastAction?.actionType != AgentActionType.SWIPE_DOWN) {
                return AgentStepDecision(
                    actionType = AgentActionType.SWIPE_DOWN,
                    thought = "Ekrandaki tüm ögeler denendi. Yeni içerik ve düğmeleri görmek için sayfa aşağı kaydırılıyor.",
                    speechStatus = "Daha fazla seçenek için sayfa kaydırılıyor"
                )
            }

            // Kaydırma da yapılmışsa, güvenli bir şekilde bir önceki ekrana dön (PRESS_BACK)
            return AgentStepDecision(
                actionType = AgentActionType.PRESS_BACK,
                thought = "Mevcut ekranın keşfi tamamlandı veya yanıt vermedi. Güvenli şekilde bir önceki ekrana dönülüyor.",
                speechStatus = "Önceki ekrana dönülüyor"
            )
        }

        // 2. REASONER İLE KARAR: Model varsa exploration prompt'u ile sor
        if (reasoner != null) {
            try {
                val explorationPrompt = "Cihazı ve uygulamaları keşfet. Yeni, güvenli ve anlamlı ekranları ziyaret et. Zararlı (silme, ödeme vb.) butonlara dokunma."
                val visitedElementsSummary = session.visitedNodeSignatures.toList().takeLast(10).toSet()

                val modelDecision = reasoner.decideNextScreenAction(
                    snapshot = snapshot,
                    taskPrompt = explorationPrompt,
                    stepNumber = session.stepCount + 1,
                    visitedElements = visitedElementsSummary,
                    memories = memories,
                    profile = profile,
                    liveScreenshot = null
                )

                // Model kararı güvenli mi kontrol et
                val isSafe = when (modelDecision.actionType) {
                    AgentActionType.CLICK_NODE, AgentActionType.CLICK_COORD -> {
                        val node = if (modelDecision.targetIndex in snapshot.clickableNodes.indices) {
                            snapshot.clickableNodes[modelDecision.targetIndex]
                        } else null

                        val isNodeSafe = node?.let { ExplorationSafety.isSafeNode(it) } ?: true
                        val isTextSafe = ExplorationSafety.isSafeText(modelDecision.targetText)
                        isNodeSafe && isTextSafe
                    }
                    AgentActionType.TASK_COMPLETE -> true
                    AgentActionType.PRESS_BACK, AgentActionType.PRESS_HOME -> true
                    AgentActionType.SWIPE_DOWN, AgentActionType.SWIPE_UP, AgentActionType.SWIPE_LEFT, AgentActionType.SWIPE_RIGHT -> true
                    AgentActionType.OPEN_APP -> ExplorationSafety.isSafeText(modelDecision.appName)
                    else -> false
                }

                if (isSafe && modelDecision.actionType != AgentActionType.IDLE) {
                    if (modelDecision.targetIndex in snapshot.clickableNodes.indices) {
                        val node = snapshot.clickableNodes[modelDecision.targetIndex]
                        session.visitedNodeSignatures.add(generateNodeSignature(currentPkg, node))
                    }
                    return modelDecision
                }
            } catch (e: Exception) {
                // Reasoner hatası durumunda deterministik seçiciye geç
            }
        }

        // 3. DETERMINISTIC SEMANTIC SELECTION (Rastgele hareket YOK!)
        val unvisitedNode = findNextUnvisitedSafeNode(snapshot, session)
        if (unvisitedNode != null) {
            val sig = generateNodeSignature(currentPkg, unvisitedNode.node)
            session.visitedNodeSignatures.add(sig)
            val targetName = unvisitedNode.node.text.ifBlank { unvisitedNode.node.contentDescription.ifBlank { "Bileşen" } }
            return AgentStepDecision(
                actionType = AgentActionType.CLICK_NODE,
                thought = "Keşif için ekrandaki yeni güvenli öge seçildi: $targetName",
                speechStatus = "$targetName inceleniyor",
                targetIndex = unvisitedNode.index,
                targetText = targetName,
                coordinates = PointF(
                    unvisitedNode.node.bounds.centerX().toFloat(),
                    unvisitedNode.node.bounds.centerY().toFloat()
                )
            )
        }

        // Tıklanabilir güvenli öge kalmadıysa önce kaydır, sonra geri dön
        val lastAction = session.actionHistory.lastOrNull()
        if (lastAction?.actionType != AgentActionType.SWIPE_DOWN && snapshot.clickableNodes.size > 2) {
            return AgentStepDecision(
                actionType = AgentActionType.SWIPE_DOWN,
                thought = "Ekrandaki görünür ögeler tamamlandı. Alt kısımdaki yeni menüleri keşfetmek için kaydırılıyor.",
                speechStatus = "Sayfa kaydırılıyor"
            )
        }

        return AgentStepDecision(
            actionType = AgentActionType.PRESS_BACK,
            thought = "Bu ekranda keşfedilecek yeni öge kalmadı. Bir üst menüye güvenle dönülüyor.",
            speechStatus = "Önceki ekrana dönülüyor"
        )
    }

    private data class IndexedNode(val index: Int, val node: ScreenNodeData)

    private fun findNextUnvisitedSafeNode(
        snapshot: ScreenSnapshot,
        session: ExplorationTaskSession
    ): IndexedNode? {
        val currentPkg = snapshot.packageName

        // Güvenli ve henüz ziyaret edilmemiş düğümleri filtrele
        val candidates = snapshot.clickableNodes.mapIndexedNotNull { idx, node ->
            if (!ExplorationSafety.isSafeNode(node)) return@mapIndexedNotNull null
            val sig = generateNodeSignature(currentPkg, node)
            if (session.visitedNodeSignatures.contains(sig)) return@mapIndexedNotNull null
            IndexedNode(idx, node)
        }

        if (candidates.isEmpty()) return null

        // Önceliklendirme: Anlamlı başlığı veya açıklaması olan düğümler > İsimsiz düğümler
        val namedCandidate = candidates.firstOrNull {
            it.node.text.isNotBlank() || it.node.contentDescription.isNotBlank()
        }

        return namedCandidate ?: candidates.firstOrNull()
    }
}
