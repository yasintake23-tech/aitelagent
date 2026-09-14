package com.example.agent.multibrain

import android.graphics.Bitmap
import com.example.service.ScreenSnapshot

data class ScreenContext(
    val snapshot: ScreenSnapshot,
    val screenshot: Bitmap? = null,
    val packageName: String,
    val activityName: String,
    val timestamp: Long = System.currentTimeMillis()
)
