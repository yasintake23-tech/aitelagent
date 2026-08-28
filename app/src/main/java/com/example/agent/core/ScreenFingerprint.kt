package com.example.agent.core

import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import java.security.MessageDigest
import java.util.Locale

/**
 * Categorization of change between two observed screen states.
 */
enum class ScreenDifferenceType {
    /** Identical logical screen state and UI structure */
    SAME_STATE,

    /** Minor dynamic UI change (e.g. counter, clock, minor text update) */
    MINOR_CHANGE,

    /** Distinctly new screen, dialog, activity, or application view */
    NEW_STATE
}

/**
 * Structured container for a screen's deterministic fingerprint and high-level structural signature.
 */
data class ScreenFingerprint(
    val value: String,
    val packageName: String,
    val activityName: String,
    val structuralSignature: String,
    val meaningfulNodeCount: Int
) {
    override fun toString(): String = value
}

/**
 * High-performance, deterministic screen fingerprint generator and comparator.
 * Resilient against non-deterministic OS node ordering and minor jitter.
 */
object ScreenFingerprintGenerator {

    /**
     * Computes a deterministic SHA-256 fingerprint for a given [ScreenSnapshot].
     */
    fun generateFingerprint(snapshot: ScreenSnapshot): ScreenFingerprint {
        val normPkg = snapshot.packageName.lowercase(Locale.ROOT).trim()
        val normAct = snapshot.activityName.lowercase(Locale.ROOT).trim()

        // 1. Extract and normalize significant node signatures
        val nodeSignatures = mutableListOf<String>()
        val allNodes = snapshot.clickableNodes + snapshot.editableNodes

        for (node in allNodes) {
            val sig = buildNodeSignature(node)
            if (sig.isNotBlank()) {
                nodeSignatures.add(sig)
            }
        }

        // Sort node signatures to guarantee stability regardless of accessibility tree traversal ordering
        val sortedNodeSignatures = nodeSignatures.distinct().sorted()

        // 2. Extract and normalize top texts
        val sortedTexts = snapshot.texts
            .map { normalizeText(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(10)

        // 3. Assemble structural representation (excludes timestamp, transient coordinates, and volatile runtime IDs)
        val structuralBuffer = StringBuilder()
        structuralBuffer.append("PKG:").append(normPkg).append("|")
        structuralBuffer.append("ACT:").append(normAct).append("|")
        structuralBuffer.append("NODES:").append(sortedNodeSignatures.joinToString(",")).append("|")
        structuralBuffer.append("TEXTS:").append(sortedTexts.joinToString(","))

        val structuralString = structuralBuffer.toString()
        val hash = sha256Hex(structuralString)

        return ScreenFingerprint(
            value = hash,
            packageName = normPkg,
            activityName = normAct,
            structuralSignature = structuralString,
            meaningfulNodeCount = sortedNodeSignatures.size
        )
    }

    /**
     * Compares two screen snapshots and determines the level of state transition.
     */
    fun compareScreens(
        previous: ScreenSnapshot?,
        current: ScreenSnapshot?
    ): ScreenDifferenceType {
        if (previous == null || current == null) return ScreenDifferenceType.NEW_STATE

        val fp1 = generateFingerprint(previous)
        val fp2 = generateFingerprint(current)

        if (fp1.value == fp2.value) {
            return ScreenDifferenceType.SAME_STATE
        }

        // If package changed, it's definitely a new state
        if (fp1.packageName != fp2.packageName) {
            return ScreenDifferenceType.NEW_STATE
        }

        // If activity changed (and neither is blank), it's a new state
        if (fp1.activityName.isNotBlank() && fp2.activityName.isNotBlank() && fp1.activityName != fp2.activityName) {
            return ScreenDifferenceType.NEW_STATE
        }

        // Check node signature similarity (Jaccard index of normalized node signatures)
        val nodes1 = (previous.clickableNodes + previous.editableNodes)
            .map { buildNodeSignature(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val nodes2 = (current.clickableNodes + current.editableNodes)
            .map { buildNodeSignature(it) }
            .filter { it.isNotBlank() }
            .toSet()

        if (nodes1.isEmpty() && nodes2.isEmpty()) {
            return if (fp1.value == fp2.value) ScreenDifferenceType.SAME_STATE else ScreenDifferenceType.MINOR_CHANGE
        }

        val intersectionSize = nodes1.intersect(nodes2).size
        val unionSize = nodes1.union(nodes2).size

        val similarity = if (unionSize > 0) intersectionSize.toFloat() / unionSize.toFloat() else 0f

        return when {
            similarity >= 0.50f -> ScreenDifferenceType.MINOR_CHANGE
            else -> ScreenDifferenceType.NEW_STATE
        }
    }

    /**
     * Compares two screen fingerprints directly.
     */
    fun compareFingerprints(
        fp1: ScreenFingerprint,
        fp2: ScreenFingerprint
    ): ScreenDifferenceType {
        if (fp1.value == fp2.value) {
            return ScreenDifferenceType.SAME_STATE
        }
        if (fp1.packageName != fp2.packageName) {
            return ScreenDifferenceType.NEW_STATE
        }
        if (fp1.activityName.isNotBlank() && fp2.activityName.isNotBlank() && fp1.activityName != fp2.activityName) {
            return ScreenDifferenceType.NEW_STATE
        }
        return ScreenDifferenceType.MINOR_CHANGE
    }

    private fun buildNodeSignature(node: ScreenNodeData): String {
        val normId = node.viewId.substringAfterLast("/").lowercase(Locale.ROOT).trim().take(30)
        val normText = normalizeText(node.text).take(30)
        val normDesc = normalizeText(node.contentDescription).take(30)
        val normClass = node.className.substringAfterLast(".").lowercase(Locale.ROOT).trim().take(20)

        val isClickable = if (node.isClickable) "1" else "0"
        val isEditable = if (node.isEditable) "1" else "0"

        // If completely empty node without interactivity, ignore
        if (normId.isBlank() && normText.isBlank() && normDesc.isBlank() && !node.isClickable && !node.isEditable) {
            return ""
        }

        return "[$normClass:$normId:t=$normText:d=$normDesc:c=$isClickable:e=$isEditable]"
    }

    private fun normalizeText(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace('ı', 'i')
            .replace('İ', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace(Regex("[.,!?;:'\"`´\\-_]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
