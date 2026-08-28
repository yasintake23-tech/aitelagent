package com.example.agent.core

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot

/**
 * Ekran ağacını (AccessibilityNodeInfo) güvenli bir şekilde tarayarak
 * gürültüden arındırılmış anlamsal ScreenSnapshot nesnesi üreten gözlemci bileşen.
 */
object ScreenObserver {

    /**
     * Aktif pencerenin kök düğümünden ekran görüntüsü (ScreenSnapshot) üretir.
     */
    fun observeScreen(
        root: AccessibilityNodeInfo?,
        currentPackage: String = "",
        currentActivity: String = ""
    ): ScreenSnapshot {
        if (root == null) {
            return ScreenSnapshot(
                packageName = currentPackage,
                activityName = currentActivity,
                nodeCount = 0,
                texts = emptyList(),
                clickableNodes = emptyList(),
                editableNodes = emptyList()
            )
        }

        val textList = mutableListOf<String>()
        val clickableList = mutableListOf<ScreenNodeData>()
        val editableList = mutableListOf<ScreenNodeData>()
        var count = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            count++

            // Görünmeyen düğümleri filtrele
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && !node.isVisibleToUser) {
                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i))
                }
                return
            }

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val rawViewId = node.viewIdResourceName ?: ""
            val viewId = rawViewId.substringAfterLast("/")
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Sıfır veya negatif alana sahip düğümleri atla
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i))
                }
                return
            }

            if (text.isNotBlank() && text.length <= 120) textList.add(text)
            if (desc.isNotBlank() && desc != text && desc.length <= 120) textList.add(desc)

            val isEditable = node.isEditable || (node.className?.contains("EditText", ignoreCase = true) == true)
            val isClickable = node.isClickable || node.isCheckable
            val hasMeaningfulLabel = text.isNotBlank() || desc.isNotBlank() || viewId.isNotBlank() || isClickable || isEditable

            if (hasMeaningfulLabel) {
                val nodeData = ScreenNodeData(
                    text = text.take(80),
                    contentDescription = desc.take(80),
                    viewId = viewId.take(40),
                    bounds = bounds,
                    isClickable = isClickable,
                    isScrollable = node.isScrollable,
                    isEditable = isEditable,
                    className = node.className?.toString() ?: "",
                    packageName = node.packageName?.toString() ?: ""
                )

                // Görünür ve geçerli düğümleri listeye ekle
                if (clickableList.size < 40) {
                    val isDuplicate = clickableList.any { existing ->
                        existing.bounds == bounds && (existing.text == text || existing.contentDescription == desc)
                    }
                    if (!isDuplicate) {
                        clickableList.add(nodeData)
                    }
                }
                if (isEditable && editableList.size < 10) {
                    editableList.add(nodeData)
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)

        return ScreenSnapshot(
            packageName = currentPackage.ifBlank { root.packageName?.toString() ?: "" },
            activityName = currentActivity.ifBlank { root.className?.toString() ?: "" },
            nodeCount = count,
            texts = textList.distinct().take(15),
            clickableNodes = clickableList,
            editableNodes = editableList
        )
    }

    /**
     * Verilen ScreenSnapshot için deterministik SHA-256 ekran parmak izi (fingerprint) üretir.
     */
    fun computeFingerprint(snapshot: ScreenSnapshot): String {
        return ScreenFingerprintGenerator.generateFingerprint(snapshot).value
    }

    /**
     * Verilen ScreenSnapshot için yapılandırılmış ScreenFingerprint nesnesi üretir.
     */
    fun computeScreenFingerprint(snapshot: ScreenSnapshot): ScreenFingerprint {
        return ScreenFingerprintGenerator.generateFingerprint(snapshot)
    }
}
