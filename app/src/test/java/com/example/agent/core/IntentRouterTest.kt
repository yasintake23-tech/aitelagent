package com.example.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {

    @Test
    fun testConversationalGreetings() {
        val greetings = listOf(
            "merhaba",
            "Merhaba",
            "MERHABA",
            "selam",
            "selamlar",
            "günaydın",
            "iyi günler",
            "iyi akşamlar",
            "hey"
        )
        for (query in greetings) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected CONVERSATIONAL for '$query'", UserIntent.CONVERSATIONAL, result.intent)
        }
    }

    @Test
    fun testConversationalSocialAndChat() {
        val social = listOf(
            "nasılsın",
            "nasilsin",
            "ne haber",
            "naber",
            "sen kimsin",
            "adın ne",
            "kendini tanıt",
            "neler yapabilirsin"
        )
        for (query in social) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected CONVERSATIONAL for '$query'", UserIntent.CONVERSATIONAL, result.intent)
        }
    }

    @Test
    fun testConversationalGratitude() {
        val gratitude = listOf(
            "teşekkürler",
            "teşekkür ederim",
            "sağ ol",
            "eline sağlık",
            "harikasın",
            "süpersin"
        )
        for (query in gratitude) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected CONVERSATIONAL for '$query'", UserIntent.CONVERSATIONAL, result.intent)
        }
    }

    @Test
    fun testConversationalKnowledgeQuestions() {
        val questions = listOf(
            "bugün hava nasıl",
            "hava durumu nasıl",
            "bir şey anlat",
            "bana bir fıkra anlat",
            "dolar kaç tl",
            "Türkiye'nin başkenti neresi"
        )
        for (query in questions) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected CONVERSATIONAL for '$query'", UserIntent.CONVERSATIONAL, result.intent)
        }
    }

    @Test
    fun testDeviceTaskAppLaunch() {
        val appLaunches = listOf(
            "WhatsApp'ı aç",
            "whatsapp aç",
            "Instagram'a gir",
            "YouTube başlat",
            "Kamerayı aç",
            "Galeriyi aç",
            "Ayarları aç",
            "Rehberi aç",
            "Hesap makinesini aç"
        )
        for (query in appLaunches) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected DEVICE_TASK for '$query'", UserIntent.DEVICE_TASK, result.intent)
            assertNotNull("Target app should be identified for '$query'", result.targetApp)
        }
    }

    @Test
    fun testDeviceTaskWhatsAppMessageAutomation() {
        val messages = listOf(
            "Ahmet'e WhatsApp'tan merhaba yaz",
            "Mehmet'e wp'den selam gönder",
            "WhatsApp'tan Ayşe'ye geliyorum mesajı at",
            "wp'den babama arayacağım yaz"
        )
        for (query in messages) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected DEVICE_TASK for '$query'", UserIntent.DEVICE_TASK, result.intent)
            assertEquals("WhatsApp", result.targetApp)
        }
    }

    @Test
    fun testDeviceTaskSystemControlsAndGestures() {
        val controls = listOf(
            "sesi azalt",
            "sesi aç",
            "sesi kıs",
            "sesi %30 yap",
            "ekranı aşağı kaydır",
            "yukarı kaydır",
            "ana sayfaya git",
            "geri dön",
            "bildirimleri aç",
            "hızlı ayarları aç",
            "ekranda ne var",
            "ekranı oku"
        )
        for (query in controls) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected DEVICE_TASK for '$query'", UserIntent.DEVICE_TASK, result.intent)
        }
    }

    @Test
    fun testExplorationTask() {
        val explorations = mapOf(
            "telefonu 15 dakika gez" to 15,
            "cihazı 10 dakika keşfet" to 10,
            "30 dk telefonu kurcala" to 30,
            "cihazı keşfet" to 15
        )
        for ((query, expectedMinutes) in explorations) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected EXPLORATION_TASK for '$query'", UserIntent.EXPLORATION_TASK, result.intent)
            assertEquals(expectedMinutes, result.durationMinutes)
        }
    }

    @Test
    fun testAmbiguousCommands() {
        val ambiguous = listOf(
            "telefonla ilgilen",
            "bir şeyler yap",
            "bak bakalım",
            "şuraya bak",
            "hallet"
        )
        for (query in ambiguous) {
            val result = IntentRouter.classifyIntent(query)
            assertEquals("Expected AMBIGUOUS for '$query'", UserIntent.AMBIGUOUS, result.intent)
        }
    }

    @Test
    fun testExplicitCancellation() {
        val cancels = listOf(
            "durdur",
            "iptal",
            "sus",
            "kontrolü bırak",
            "dur artık"
        )
        for (query in cancels) {
            val result = IntentRouter.classifyIntent(query)
            assertTrue("Expected isExplicitCancel for '$query'", result.isExplicitCancel)
        }
    }
}
