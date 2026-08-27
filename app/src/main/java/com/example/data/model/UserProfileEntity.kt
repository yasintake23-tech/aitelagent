package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val userName: String = "",
    val aiName: String = "Nova",
    val personalityTone: String = PersonalityTone.SAMIMI.name,
    val primaryExpectation: String = "Genel Yardım & Günlük Planlama",
    val isAwakened: Boolean = false,
    val preferredAiProvider: String = "Gemini",
    val customApiKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
