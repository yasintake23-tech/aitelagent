package com.example.agent.core

import android.util.Log
import java.util.Locale

/**
 * High-level classification of user inputs into semantic operational streams.
 */
enum class UserIntent {
    /** Pure conversation, questions, chit-chat, greetings, queries -> Chat LLM / Assistant */
    CONVERSATIONAL,

    /** Specific, actionable device commands -> Device Agent ReAct / Visual Grounding / Native Actions */
    DEVICE_TASK,

    /** Continuous, multi-minute exploration / discovery -> Exploration Session */
    EXPLORATION_TASK,

    /** Ambiguous, vague or unclear user intent -> Request clarification, do not randomly touch device */
    AMBIGUOUS
}

/**
 * Result of intent classification containing confidence, justification and metadata.
 */
data class IntentClassificationResult(
    val intent: UserIntent,
    val confidence: Float,
    val reason: String,
    val durationMinutes: Int? = null,
    val targetApp: String? = null,
    val isExplicitCancel: Boolean = false
)

object IntentRouter {

    private const val TAG = "IntentRouter"

    // Known common app names / identifiers normalized (a-z)
    private val KNOWN_APPS = listOf(
        "whatsapp", "wp", "instagram", "insta", "youtube", "yt", "galeri", "kamera",
        "ayarlar", "settings", "rehber", "kisiler", "mesajlar", "sms", "haritalar", "harita", "maps",
        "hesap makinesi", "hesap", "chrome", "google", "play store", "playstore", "saat", "alarm",
        "dosyalar", "gmail", "posta", "e-posta", "spotify", "muzik", "notlar", "ses kaydedici"
    )

    // Explicit cancel / stop tokens normalized
    private val CANCEL_KEYWORDS = listOf(
        "durdur", "iptal", "sus", "kontrolu birak", "dur artik", "yeter", "vazgec"
    )

    // Pure conversational greetings / social phrases normalized
    private val GREETING_EXACT = setOf(
        "merhaba", "merhabalar", "selam", "selamlar", "gunaydin", "iyi gunler",
        "iyi aksamlar", "iyi geceler", "hey", "hello", "hi", "selam aleykum", "selamun aleykum",
        "aleykum selam", "kolay gelsin", "hos geldin"
    )

    private val SOCIAL_CHAT_PHRASES = listOf(
        "nasilsin", "ne haber", "naber", "neler yapiyorsun", "ne yapiyorsun",
        "sen kimsin", "kimsin", "adin ne", "ismin ne", "kendini tanit", "sen necisin",
        "nasilsiniz", "iyi misin", "iyiyim", "sen nasilsin", "neler yapabilirsin", "ne ise yararsin"
    )

    private val GRATITUDE_PHRASES = listOf(
        "tesekkurler", "tesekkur ederim", "sag ol", "sagol",
        "eline saglik", "harikasin", "superson", "eyvallah", "cok iyisin", "aferin", "helal", "sagolasin"
    )

    // Pure conversational knowledge question patterns normalized
    private val KNOWLEDGE_QUESTION_PATTERNS = listOf(
        Regex("^(bugun\\s+)?hava(\\s+durumu)?(\\s+nasil|\\s+kac derece)?$"),
        Regex("^(bana\\s+)?(bir\\s+)?(fikra|hikaye|masal|saka|espri)\\s+(anlat|soyle|oku)$"),
        Regex("^(bir\\s+)?sey\\s+(anlat|soyle)$"),
        Regex("^(dolar|euro|altin|borsa|btc|bitcoin)\\s+(kac|ne kadar|fiyati ne)$"),
        Regex(".+\\s+(nedir|kimdir|nerededir|kactir|ne zaman|nasil yapilir|kac yasinda)$")
    )

    // Ambiguous trigger phrases that do not specify a clear action or app
    private val AMBIGUOUS_PATTERNS = listOf(
        Regex("^(telefonla|cihazla)\\s+(ilgilen|ugras|bak|oyna|bir seyler yap)$"),
        Regex("^(bir\\s+)?seyler\\s+(yap|dene|kurcala)$"),
        Regex("^bak\\s+bakalim$"),
        Regex("^suraya\\s+bak$"),
        Regex("^hallet$"),
        Regex("^telefona\\s+bak$"),
        Regex("^cihazi\\s+kontrol\\s+et$")
    )

    /**
     * Normalizes text by removing diacritics, punctuation, apostrophes for stable matching.
     */
    fun normalizeForMatching(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace('ı', 'i')
            .replace('İ', 'i')
            .replace('I', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace(Regex("['’`´]"), "")
            .replace(Regex("[.,!?;:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Classifies a user text / command into a structured UserIntent.
     * Guaranteed not to trigger device execution for normal conversation.
     */
    fun classifyIntent(rawText: String): IntentClassificationResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return IntentClassificationResult(
                intent = UserIntent.CONVERSATIONAL,
                confidence = 1.0f,
                reason = "Boş metin"
            )
        }

        val norm = normalizeForMatching(trimmed)

        // 1. Explicit Cancel / Stop Command
        if (CANCEL_KEYWORDS.any { norm == it || norm.startsWith("$it ") }) {
            return IntentClassificationResult(
                intent = UserIntent.DEVICE_TASK,
                confidence = 1.0f,
                reason = "Açık iptal/durdurma komutu",
                isExplicitCancel = true
            )
        }

        // 2. Exploration Intent (Explicit Timed Exploration / General Exploration)
        val durationMinutes = extractDurationMinutes(trimmed)
        val hasExploreVerb = listOf("kesfet", "gez", "kurcala", "dolas", "incele").any { verb ->
            norm.contains(verb)
        }
        val mentionsDevice = listOf("telefon", "cihaz", "ekran", "sistem", "uygulama").any { norm.contains(it) }

        if (durationMinutes != null && (hasExploreVerb || mentionsDevice || norm.contains("kontrol et"))) {
            return IntentClassificationResult(
                intent = UserIntent.EXPLORATION_TASK,
                confidence = 0.95f,
                reason = "Süre belirtilmiş cihaz keşif görevi ($durationMinutes dk)",
                durationMinutes = durationMinutes
            )
        }

        if (hasExploreVerb && mentionsDevice) {
            val defaultDuration = 15
            return IntentClassificationResult(
                intent = UserIntent.EXPLORATION_TASK,
                confidence = 0.90f,
                reason = "Genel cihaz keşif ve gezinme görevi",
                durationMinutes = defaultDuration
            )
        }

        // 3. Conversational Checks (Greetings, Social, Gratitude, Knowledge)
        if (GREETING_EXACT.contains(norm)) {
            return IntentClassificationResult(
                intent = UserIntent.CONVERSATIONAL,
                confidence = 1.0f,
                reason = "Selamlaşma/Karşılama ifadesi"
            )
        }

        if (SOCIAL_CHAT_PHRASES.any { norm.contains(it) }) {
            return IntentClassificationResult(
                intent = UserIntent.CONVERSATIONAL,
                confidence = 0.95f,
                reason = "Sohbet / Hal hatır sorma"
            )
        }

        if (GRATITUDE_PHRASES.any { norm == it || norm.startsWith(it) }) {
            return IntentClassificationResult(
                intent = UserIntent.CONVERSATIONAL,
                confidence = 0.95f,
                reason = "Teşekkür / Nezaket ifadesi"
            )
        }

        if (KNOWLEDGE_QUESTION_PATTERNS.any { it.containsMatchIn(norm) }) {
            return IntentClassificationResult(
                intent = UserIntent.CONVERSATIONAL,
                confidence = 0.90f,
                reason = "Genel bilgi / sohbet sorusu"
            )
        }

        // 4. WhatsApp / Messaging Specific Device Task
        if (isWhatsAppMessageIntent(norm)) {
            return IntentClassificationResult(
                intent = UserIntent.DEVICE_TASK,
                confidence = 0.98f,
                reason = "WhatsApp mesaj otomasyonu görevi",
                targetApp = "WhatsApp"
            )
        }

        // 5. Explicit App Launching Intent
        val detectedApp = detectAppLaunch(norm)
        if (detectedApp != null) {
            return IntentClassificationResult(
                intent = UserIntent.DEVICE_TASK,
                confidence = 0.95f,
                reason = "Uygulama başlatma komutu: $detectedApp",
                targetApp = detectedApp
            )
        }

        // 6. Explicit Device Controls & Gestures
        if (isExplicitDeviceControl(norm)) {
            return IntentClassificationResult(
                intent = UserIntent.DEVICE_TASK,
                confidence = 0.95f,
                reason = "Sistem kontrolü / Jest / Ekran okuma komutu"
            )
        }

        // 7. Ambiguous Command Detection
        if (AMBIGUOUS_PATTERNS.any { it.containsMatchIn(norm) }) {
            return IntentClassificationResult(
                intent = UserIntent.AMBIGUOUS,
                confidence = 0.85f,
                reason = "Belirsiz cihaz komutu (açıklama gerekli)"
            )
        }

        // 8. Default Fallback -> Conversational (Safest choice: never touch device unless sure)
        return IntentClassificationResult(
            intent = UserIntent.CONVERSATIONAL,
            confidence = 0.70f,
            reason = "Varsayılan güvenli sohbet akışı"
        )
    }

    /**
     * Checks if the normalized text represents a WhatsApp message automation command.
     */
    private fun isWhatsAppMessageIntent(norm: String): Boolean {
        val hasWp = norm.contains("whatsapp") || norm.contains("wp") || norm.contains("watsap")
        if (!hasWp) return false

        val messageVerbs = listOf("mesaj", "yaz", "gonder", "at", "soyle", "de")
        return messageVerbs.any { norm.contains(it) }
    }

    /**
     * Detects app launch intents such as "WhatsApp'ı aç", "Instagrama gir", "YouTube başlat".
     */
    private fun detectAppLaunch(norm: String): String? {
        val launchVerbs = listOf("ac", "gir", "baslat", "calistir", "goster")
        val words = norm.split(Regex("\\s+"))

        val hasLaunchVerb = words.any { word -> launchVerbs.contains(word) } ||
                launchVerbs.any { norm.endsWith(" $it") || norm.endsWith(it) }

        if (!hasLaunchVerb) return null

        for (app in KNOWN_APPS) {
            if (norm.contains(app)) {
                return when (app) {
                    "wp" -> "WhatsApp"
                    "yt" -> "YouTube"
                    "insta" -> "Instagram"
                    "kisiler" -> "Kişiler"
                    "muzik" -> "Müzik"
                    else -> app.replaceFirstChar { it.uppercase() }
                }
            }
        }
        return null
    }

    /**
     * Checks for hardware, volume, navigation gestures, or screen reading triggers.
     */
    private fun isExplicitDeviceControl(norm: String): Boolean {
        // Volume controls
        if (norm.contains("ses") && (
                    norm.contains("ac") || norm.contains("artir") || norm.contains("yukselt") ||
                    norm.contains("kis") || norm.contains("azalt") || norm.contains("dusur") ||
                    norm.contains("kapat") || norm.contains("fulle") || norm.contains("%")
                )
        ) {
            return true
        }

        // Navigation & Gestures
        val navGestures = listOf(
            "kaydir", "asagi kaydir", "yukari kaydir", "saga kaydir", "sola kaydir",
            "ana sayfa", "ana ekrana git", "ana ekrana don", "home git", "home ekrani",
            "geri git", "geri don", "geri tusuna bas", "bildirimleri ac", "bildirim paneli",
            "hizli ayarlar", "kontrol paneli"
        )
        if (navGestures.any { norm.contains(it) }) {
            return true
        }

        // Screen Reading
        val screenReading = listOf(
            "ekrani oku", "ekranda ne var", "ekrani incele",
            "ekrana bak", "ekrani tara"
        )
        if (screenReading.any { norm.contains(it) }) {
            return true
        }

        // Visual click triggers
        if (norm.contains("tikla") || norm.contains("dokun") || norm.contains("butona bas")) {
            return true
        }

        return false
    }

    /**
     * Helper to extract duration in minutes from Turkish speech text.
     */
    fun extractDurationMinutes(text: String): Int? {
        val regex = Regex("(\\d+)\\s*(dakika|dk|min|saat|hour)", RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return null
        val num = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        return if (unit.startsWith("saat") || unit.startsWith("hour")) {
            num * 60
        } else {
            num
        }
    }

    /**
     * Helper to log routing decisions cleanly without leaking sensitive user data.
     */
    fun logRoutingDecision(command: String, result: IntentClassificationResult, agentStarted: Boolean) {
        val sanitizedCommand = if (command.length > 50) command.take(47) + "..." else command
        Log.i(
            TAG,
            "Intent Decision -> command='$sanitizedCommand', intent=${result.intent}, confidence=${result.confidence}, reason='${result.reason}', agentStarted=$agentStarted"
        )
    }
}
