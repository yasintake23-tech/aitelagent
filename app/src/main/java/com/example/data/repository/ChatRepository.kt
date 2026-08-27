package com.example.data.repository

import com.example.data.local.ChatMessageDao
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MessageRole
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val chatMessageDao: ChatMessageDao
) {
    val allMessages: Flow<List<ChatMessageEntity>> = chatMessageDao.getAllMessages()

    suspend fun getRecentMessages(limit: Int = 20): List<ChatMessageEntity> {
        return chatMessageDao.getRecentMessages(limit).reversed()
    }

    suspend fun sendUserMessage(content: String): Long {
        val message = ChatMessageEntity(
            role = MessageRole.USER.name,
            content = content,
            timestamp = System.currentTimeMillis()
        )
        return chatMessageDao.insertMessage(message)
    }

    suspend fun saveAssistantMessage(content: String): Long {
        val message = ChatMessageEntity(
            role = MessageRole.ASSISTANT.name,
            content = content,
            timestamp = System.currentTimeMillis()
        )
        return chatMessageDao.insertMessage(message)
    }

    suspend fun clearHistory() {
        chatMessageDao.clearChat()
    }
}
