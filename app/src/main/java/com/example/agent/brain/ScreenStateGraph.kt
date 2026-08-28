package com.example.agent.brain

/**
 * Ekran durumları ve bunlar arasındaki eylem geçişlerini temsil eden graf yapısı.
 */
data class ScreenStateNode(
    val packageName: String,
    val screenFingerprint: String,
    val appTitle: String? = null
)

data class ScreenTransition(
    val fromNode: ScreenStateNode,
    val toNode: ScreenStateNode,
    val actionType: String,
    val target: String?,
    val isSuccess: Boolean
)

class ScreenStateGraph {
    private val nodes = mutableSetOf<ScreenStateNode>()
    private val transitions = mutableListOf<ScreenTransition>()

    @Synchronized
    fun recordTransition(
        fromPkg: String,
        fromFingerprint: String,
        fromTitle: String?,
        toPkg: String,
        toFingerprint: String,
        toTitle: String?,
        actionType: String,
        target: String?,
        isSuccess: Boolean
    ) {
        val fromNode = ScreenStateNode(fromPkg, fromFingerprint, fromTitle)
        val toNode = ScreenStateNode(toPkg, toFingerprint, toTitle)
        nodes.add(fromNode)
        nodes.add(toNode)
        transitions.add(ScreenTransition(fromNode, toNode, actionType, target, isSuccess))
    }

    @Synchronized
    fun getSummaryString(currentFingerprint: String?): String {
        val recent = transitions.takeLast(5).joinToString("\n") {
            "${it.fromNode.packageName}(${it.fromNode.screenFingerprint.take(8)}) -> [${it.actionType}:${it.target ?: ""}] -> ${it.toNode.packageName}(${it.toNode.screenFingerprint.take(8)}) (Success:${it.isSuccess})"
        }
        val failedTransitions = transitions.filter { !it.isSuccess }.takeLast(3).joinToString("\n") {
            "FAILED: ${it.fromNode.packageName} -> ${it.actionType}:${it.target ?: ""} (${it.toNode.packageName})"
        }

        return """
            STATE GRAPH SUMMARY:
            Visited Unique States: ${nodes.size}
            Current Fingerprint: ${currentFingerprint?.take(8) ?: "N/A"}
            Recent Transitions:
            ${recent.ifBlank { "Henüz geçiş yok" }}
            Failed Transitions:
            ${failedTransitions.ifBlank { "Yok" }}
        """.trimIndent()
    }

    @Synchronized
    fun reset() {
        nodes.clear()
        transitions.clear()
    }
}
