package com.example.agent.core

import android.graphics.Rect
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenFingerprintTest {

    private fun createSampleSnapshot(
        packageName: String = "com.android.settings",
        activityName: String = "com.android.settings.SettingsActivity",
        texts: List<String> = listOf("Ayarlar", "Ağ ve İnternet", "Bağlı Cihazlar", "Uygulamalar"),
        clickableNodes: List<ScreenNodeData> = listOf(
            ScreenNodeData(
                text = "Ağ ve İnternet",
                contentDescription = "Wi-Fi, Mobil, Hotspot",
                viewId = "com.android.settings:id/network_settings",
                bounds = Rect(0, 100, 1080, 250),
                isClickable = true,
                isScrollable = false,
                className = "android.widget.TextView",
                packageName = "com.android.settings"
            ),
            ScreenNodeData(
                text = "Bağlı Cihazlar",
                contentDescription = "Bluetooth, Eşleşme",
                viewId = "com.android.settings:id/connected_devices",
                bounds = Rect(0, 260, 1080, 410),
                isClickable = true,
                isScrollable = false,
                className = "android.widget.TextView",
                packageName = "com.android.settings"
            )
        ),
        editableNodes: List<ScreenNodeData> = emptyList(),
        timestamp: Long = 1000000L
    ): ScreenSnapshot {
        return ScreenSnapshot(
            packageName = packageName,
            activityName = activityName,
            nodeCount = 20,
            texts = texts,
            clickableNodes = clickableNodes,
            editableNodes = editableNodes,
            timestamp = timestamp
        )
    }

    @Test
    fun testSameScreenSnapshotProducesSameFingerprint() {
        val snapshot1 = createSampleSnapshot()
        val snapshot2 = createSampleSnapshot()

        val fp1 = ScreenFingerprintGenerator.generateFingerprint(snapshot1)
        val fp2 = ScreenFingerprintGenerator.generateFingerprint(snapshot2)

        assertEquals("Fingerprints must be identical for identical snapshots", fp1.value, fp2.value)
        assertEquals(ScreenDifferenceType.SAME_STATE, ScreenFingerprintGenerator.compareScreens(snapshot1, snapshot2))
    }

    @Test
    fun testPackageChangeChangesFingerprint() {
        val snapshot1 = createSampleSnapshot(packageName = "com.android.settings")
        val snapshot2 = createSampleSnapshot(packageName = "com.whatsapp")

        val fp1 = ScreenFingerprintGenerator.generateFingerprint(snapshot1)
        val fp2 = ScreenFingerprintGenerator.generateFingerprint(snapshot2)

        assertNotEquals("Fingerprint must change when package changes", fp1.value, fp2.value)
        assertEquals(ScreenDifferenceType.NEW_STATE, ScreenFingerprintGenerator.compareScreens(snapshot1, snapshot2))
    }

    @Test
    fun testActivityChangeChangesFingerprint() {
        val snapshot1 = createSampleSnapshot(activityName = "com.android.settings.SettingsActivity")
        val snapshot2 = createSampleSnapshot(activityName = "com.android.settings.NetworkActivity")

        val fp1 = ScreenFingerprintGenerator.generateFingerprint(snapshot1)
        val fp2 = ScreenFingerprintGenerator.generateFingerprint(snapshot2)

        assertNotEquals("Fingerprint must change when activity changes", fp1.value, fp2.value)
        assertEquals(ScreenDifferenceType.NEW_STATE, ScreenFingerprintGenerator.compareScreens(snapshot1, snapshot2))
    }

    @Test
    fun testMeaningfulNodeTextChangeChangesFingerprint() {
        val snapshot1 = createSampleSnapshot()
        val modifiedNodes = listOf(
            ScreenNodeData(
                text = "Pil ve Güç Tasarrufu",
                contentDescription = "%85 - Yaklaşık 1 gün kaldı",
                viewId = "com.android.settings:id/battery_settings",
                bounds = Rect(0, 100, 1080, 250),
                isClickable = true,
                isScrollable = false,
                className = "android.widget.TextView",
                packageName = "com.android.settings"
            )
        )
        val snapshot2 = createSampleSnapshot(
            texts = listOf("Ayarlar", "Pil"),
            clickableNodes = modifiedNodes
        )

        val fp1 = ScreenFingerprintGenerator.generateFingerprint(snapshot1)
        val fp2 = ScreenFingerprintGenerator.generateFingerprint(snapshot2)

        assertNotEquals("Fingerprint must change when significant nodes change", fp1.value, fp2.value)
    }

    @Test
    fun testTimestampChangeDoesNotChangeFingerprint() {
        val snapshot1 = createSampleSnapshot(timestamp = 1000L)
        val snapshot2 = createSampleSnapshot(timestamp = 999999999L)

        val fp1 = ScreenFingerprintGenerator.generateFingerprint(snapshot1)
        val fp2 = ScreenFingerprintGenerator.generateFingerprint(snapshot2)

        assertEquals("Timestamp must not affect the fingerprint", fp1.value, fp2.value)
    }

    @Test
    fun testNodeOrderPermutationPreservesFingerprint() {
        val nodeA = ScreenNodeData(
            text = "Ağ ve İnternet",
            contentDescription = "Wi-Fi",
            viewId = "id/network",
            bounds = Rect(0, 100, 1080, 250),
            isClickable = true,
            isScrollable = false,
            className = "android.widget.TextView",
            packageName = "com.android.settings"
        )
        val nodeB = ScreenNodeData(
            text = "Ekran",
            contentDescription = "Parlaklık, Gece Işığı",
            viewId = "id/display",
            bounds = Rect(0, 260, 1080, 410),
            isClickable = true,
            isScrollable = false,
            className = "android.widget.TextView",
            packageName = "com.android.settings"
        )

        val snapshot1 = createSampleSnapshot(
            clickableNodes = listOf(nodeA, nodeB),
            texts = listOf("Ağ ve İnternet", "Ekran")
        )
        val snapshot2 = createSampleSnapshot(
            clickableNodes = listOf(nodeB, nodeA),
            texts = listOf("Ekran", "Ağ ve İnternet")
        )

        val fp1 = ScreenFingerprintGenerator.generateFingerprint(snapshot1)
        val fp2 = ScreenFingerprintGenerator.generateFingerprint(snapshot2)

        assertEquals("Reordered nodes must produce identical fingerprint due to sorting", fp1.value, fp2.value)
    }

    @Test
    fun testScreenObserverComputeFingerprintHelper() {
        val snapshot = createSampleSnapshot()
        val fpValue = ScreenObserver.computeFingerprint(snapshot)
        val fpObj = ScreenObserver.computeScreenFingerprint(snapshot)

        assertEquals(fpValue, fpObj.value)
        assertEquals("com.android.settings", fpObj.packageName)
        assertEquals("com.android.settings.settingsactivity", fpObj.activityName)
    }

    @Test
    fun testMinorChangeDetection() {
        val baseSnapshot = createSampleSnapshot()
        // Same package and activity, minor text or single added item
        val minorSnapshot = createSampleSnapshot(
            texts = listOf("Ayarlar", "Ağ ve İnternet", "Bağlı Cihazlar", "Uygulamalar", "12:45"),
            clickableNodes = baseSnapshot.clickableNodes + listOf(
                ScreenNodeData(
                    text = "Arama Yap",
                    contentDescription = "Ara",
                    viewId = "id/search",
                    bounds = Rect(0, 0, 1080, 80),
                    isClickable = true,
                    isScrollable = false,
                    className = "android.widget.Button",
                    packageName = "com.android.settings"
                )
            )
        )

        val diff = ScreenFingerprintGenerator.compareScreens(baseSnapshot, minorSnapshot)
        assertEquals(ScreenDifferenceType.MINOR_CHANGE, diff)
    }
}
