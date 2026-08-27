package com.example.data.model

enum class PersonalityTone(val displayName: String, val description: String, val iconName: String) {
    SAMIMI(
        displayName = "Samimi",
        description = "Sıcak, empatik ve içten bir yol arkadaşı.",
        iconName = "Favorite"
    ),
    PROFESYONEL(
        displayName = "Profesyonel",
        description = "Net, yapılandırılmış, analitik ve çözüm odaklı.",
        iconName = "Business"
    ),
    KISA_NET(
        displayName = "Kısa ve Net",
        description = "Gereksiz sözcüklerden arınmış, doğrudan ve hızlı.",
        iconName = "FlashOn"
    ),
    EGLENCELI(
        displayName = "Eğlenceli",
        description = "Esprili, enerjik ve yaratıcı bir yaklaşım.",
        iconName = "Mood"
    );

    companion object {
        fun fromString(value: String?): PersonalityTone {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: SAMIMI
        }
    }
}
