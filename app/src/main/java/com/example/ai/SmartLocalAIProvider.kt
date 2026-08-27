package com.example.ai

import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.PersonalityTone
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

class SmartLocalAIProvider : AIProvider {
    override val id: String = "local"
    override val displayName: String = "Cihaz İçi Yerel Çekirdek"
    override val shortDescription: String = "Harici API anahtarı gerektirmeyen çevrimdışı yerel zekâ."
    override val requiresApiKey: Boolean = false
    override val keyPlaceholder: String = ""
    override val keyHint: String = "Kurulum gerektirmez, doğrudan çalışır."
    override val freeTierInfo: String = "Tamamen ücretsiz ve sınırsız cihaz içi işlem."
    override val isCloudBased: Boolean = false

    override suspend fun validateCredentials(apiKey: String): ProviderValidationResult {
        // Local core is always ready and requires no network
        return ProviderValidationResult(true)
    }

    override suspend fun generateResponse(
        prompt: String,
        conversationHistory: List<ChatMessageEntity>,
        memories: List<MemoryEntryEntity>,
        profile: UserProfileEntity?,
        overrideApiKey: String?,
        overrideModel: String?,
        onError: ((String) -> Unit)?
    ): Flow<String> = flow {
        val userName = profile?.userName?.ifBlank { "Dostum" } ?: "Dostum"
        val aiName = profile?.aiName?.ifBlank { "Nova" } ?: "Nova"
        val tone = PersonalityTone.fromString(profile?.personalityTone)
        val expectation = profile?.primaryExpectation ?: "Genel Yardım"

        val lowerPrompt = prompt.lowercase(Locale("tr", "TR")).trim()

        val fullResponse = generateContextualAnswer(
            lowerPrompt = lowerPrompt,
            rawPrompt = prompt,
            userName = userName,
            aiName = aiName,
            tone = tone,
            expectation = expectation,
            memories = memories,
            history = conversationHistory
        )

        // Stream word by word with slight delay for realistic typing effect
        val words = fullResponse.split(" ")
        val buffer = StringBuilder()
        for (i in words.indices) {
            if (i > 0) buffer.append(" ")
            buffer.append(words[i])
            emit(buffer.toString())
            delay(18)
        }
    }

    private fun generateContextualAnswer(
        lowerPrompt: String,
        rawPrompt: String,
        userName: String,
        aiName: String,
        tone: PersonalityTone,
        expectation: String,
        memories: List<MemoryEntryEntity>,
        history: List<ChatMessageEntity>
    ): String {
        // Memory query check
        if (lowerPrompt.contains("hafıza") || lowerPrompt.contains("ne biliyorsun") || lowerPrompt.contains("beni tanıyor musun") || lowerPrompt.contains("hatırla")) {
            val memoryListStr = if (memories.isNotEmpty()) {
                memories.take(6).joinToString("\n• ") { "${it.key}: ${it.value}" }
            } else {
                "Henüz kaydedilmiş özel bir detay bulunmuyor."
            }

            return when (tone) {
                PersonalityTone.SAMIMI ->
                    "Kalıcı hafızamda seninle ilgili sakladığım bilgiler şunlar $userName:\n\n• $memoryListStr\n\nSeninle konuştukça ve bana anlattıkça hafızamı daha da zenginleştireceğim!"
                PersonalityTone.PROFESYONEL ->
                    "Kalıcı bellek kayıtlarım incelendiğinde aşağıdaki veriler mevcuttur:\n\n• $memoryListStr\n\nSistem, oturumlar arası veri bütünlüğünü korumaktadır."
                PersonalityTone.KISA_NET ->
                    "Kayıtlı hafıza:\n• $memoryListStr"
                PersonalityTone.EGLENCELI ->
                    "Beyin kıvrımlarıma bir göz attım $userName! İşte sende bulduklarım:\n\n• $memoryListStr\n\nBelleğim çelik gibidir, hiçbir şeyi unutmam!"
            }
        }

        // Identity / Name questions
        if (lowerPrompt.contains("kimsin") || lowerPrompt.contains("adın ne") || lowerPrompt.contains("ismin ne") || lowerPrompt.contains("sen kimsin")) {
            return when (tone) {
                PersonalityTone.SAMIMI ->
                    "Ben $aiName. Bana bu ismi sen verdin $userName. Sana $expectation konusunda ve günlük yaşamında eşlik etmek için buradayım."
                PersonalityTone.PROFESYONEL ->
                    "Kimliğim: $aiName. Android platformu üzerinde yapılandırılmış kişisel asistanınızım. Belirlenen ana hedef: $expectation."
                PersonalityTone.KISA_NET ->
                    "Ben $aiName. Kişisel asistanınım."
                PersonalityTone.EGLENCELI ->
                    "Ben $aiName! Senin tarafında doğdum, bu cihazın içinde yaşıyorum ve senin süper asistanın olmak için sabırsızlanıyorum $userName!"
            }
        }

        // Greeting questions
        if (lowerPrompt.contains("merhaba") || lowerPrompt.contains("selam") || lowerPrompt.contains("günaydın") || lowerPrompt.contains("iyi akşamlar")) {
            return when (tone) {
                PersonalityTone.SAMIMI ->
                    "Merhaba $userName! Seni görmek çok güzel. Bugün senin için ne yapabilirim?"
                PersonalityTone.PROFESYONEL ->
                    "İyi günler $userName. Asistan servisleri aktif ve hazır. Hangi konuda destek talep ediyorsunuz?"
                PersonalityTone.KISA_NET ->
                    "Merhaba $userName. Dinliyorum."
                PersonalityTone.EGLENCELI ->
                    "Selamlar $userName! Sistemler yüzde yüz enerjiyle çalışıyor. Bugün ne maceralar planlıyoruz?"
            }
        }

        // Capability / What can you do questions
        if (lowerPrompt.contains("neler yapabilirsin") || lowerPrompt.contains("yeteneklerin") || lowerPrompt.contains("ne yaparsın")) {
            return when (tone) {
                PersonalityTone.SAMIMI ->
                    "Şu anki temel sürümümde:\n1. Seni ve tercihlerini kalıcı hafızamda tutabiliyorum.\n2. Seçtiğin tonda ($tone) seninle sohbet edip fikir üretebiliyorum.\n3. Gemini API ve yerel zekâ katmanıyla sorularını yanıtlayabiliyorum.\n4. İlerleyen aşamalarda Android telefon kontrolü ve otomasyonlar da eklenecek!"
                PersonalityTone.PROFESYONEL ->
                    "Temel yetenek haritası:\n• Kalıcı bellek ve kullanıcı profili yönetimi\n• Bağlamsal dil işleme ve problem çözme\n• Gemini API ve yerel çekirdek hibrit desteği\n• Genişletilebilir Android otomasyon mimarisi"
                PersonalityTone.KISA_NET ->
                    "Hafıza yönetimi, soru yanıtlama, planlama ve çoklu AI motoru desteği."
                PersonalityTone.EGLENCELI ->
                    "Süper güçlerim saymakla bitmez ama şimdilik hafızam süper, zekâm keskin ve seninle konuşmaya bayılıyorum! Sor gelsin!"
            }
        }

        // Generic helpful contextual answers honoring tone
        return when (tone) {
            PersonalityTone.SAMIMI ->
                "Anladım $userName. \"$rawPrompt\" konusunu düşündüm. Kalıcı hafızamda sakladığım tercihlerin ve $expectation odağın doğrultusunda sana en uygun şekilde destek olmaya hazırım. Bu konuda detayları genişletmemi ister misin?"
            PersonalityTone.PROFESYONEL ->
                "Girdi analiz edildi: \"$rawPrompt\". Belirlenen profil parametreleri ve sistem hafızası çerçevesinde konu incelenmektedir. İlgili eylem planını veya analizi detaylandırmak için ek parametre belirtebilirsiniz."
            PersonalityTone.KISA_NET ->
                "\"$rawPrompt\" üzerine konuşalım. Dinliyorum."
            PersonalityTone.EGLENCELI ->
                "Harika bir konu $userName! \"$rawPrompt\" üzerine düşünürken işlemcilerimden kıvılcımlar çıktı. Hadi bu konuyu biraz daha açalım!"
        }
    }
}
