package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MemoryCategory(val displayName: String) {
    USER_IDENTITY("Kimlik & İsim"),
    PREFERENCE("Tercih & Alışkanlık"),
    INTEREST("İlgi Alanı"),
    IMPORTANT_FACT("Önemli Bilgi"),
    SYSTEM("Sistem Notu")
}

@Entity(tableName = "memories")
data class MemoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String = MemoryCategory.PREFERENCE.name,
    val key: String,
    val value: String,
    val importance: Int = 1, // 1: Normal, 2: High, 3: Core
    val timestamp: Long = System.currentTimeMillis()
)
