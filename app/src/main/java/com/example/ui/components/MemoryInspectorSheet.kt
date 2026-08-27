package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SubtleBorderGray
import com.example.ui.theme.SubtleGrayBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryInspectorSheet(
    sheetState: SheetState,
    memories: List<MemoryEntryEntity>,
    aiName: String,
    selectedProviderId: String = "gemini",
    selectedModel: String = "",
    availableModels: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onUpdateSelectedModel: (String) -> Unit = {},
    onAddMemory: (category: MemoryCategory, key: String, value: String) -> Unit,
    onDeleteMemory: (id: Long) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showAdvancedModelSettings by remember { mutableStateOf(false) }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    val selectedCategory = MemoryCategory.IMPORTANT_FACT

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PureWhite,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(SubtleGrayBg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "Hafıza",
                            tint = ObsidianBlack,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "$aiName Kalıcı Hafızası",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${memories.size} adet kalıcı veri kaydı",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons: Add Memory & Advanced Model Settings Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showAddDialog = !showAddDialog },
                    colors = ButtonDefaults.buttonColors(containerColor = SubtleGrayBg),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(
                        imageVector = if (showAddDialog) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showAddDialog) "Vazgeç" else "Yeni Bilgi Ekle",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (availableModels.isNotEmpty()) {
                    Button(
                        onClick = { showAdvancedModelSettings = !showAdvancedModelSettings },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showAdvancedModelSettings) ObsidianBlack else SubtleGrayBg
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = if (showAdvancedModelSettings) PureWhite else TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Model",
                            color = if (showAdvancedModelSettings) PureWhite else TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Optional Advanced Model Override Section
            AnimatedVisibility(visible = showAdvancedModelSettings && availableModels.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SubtleGrayBg),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SubtleBorderGray, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = ObsidianBlack,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Gelişmiş Model Ayarı (${selectedProviderId.uppercase()})",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "İsteğe Bağlı",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            Box(modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                    color = PureWhite,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, SubtleBorderGray, RoundedCornerShape(10.dp))
                                        .clickable { isModelDropdownExpanded = true }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = formatModelDisplayName(selectedModel.ifBlank { availableModels.first() }),
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = getModelBadge(selectedModel.ifBlank { availableModels.first() }),
                                                color = TextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Model Listesi",
                                            tint = ObsidianBlack
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = isModelDropdownExpanded,
                                    onDismissRequest = { isModelDropdownExpanded = false },
                                    modifier = Modifier
                                        .background(PureWhite)
                                        .border(1.dp, SubtleBorderGray, RoundedCornerShape(8.dp))
                                ) {
                                    availableModels.forEach { modelName ->
                                        val isModelSelected = modelName.equals(selectedModel, ignoreCase = true)
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = formatModelDisplayName(modelName),
                                                        color = TextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isModelSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    Text(
                                                        text = getModelBadge(modelName),
                                                        color = TextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            },
                                            onClick = {
                                                onUpdateSelectedModel(modelName)
                                                isModelDropdownExpanded = false
                                            },
                                            trailingIcon = {
                                                if (isModelSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = ObsidianBlack,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showAddDialog) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = PureWhite),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SubtleBorderGray, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Hafızaya Bilgi Ekle",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            placeholder = { Text("Başlık (Örn: Sevdiğim Şeyler)", color = TextMuted, fontSize = 13.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ObsidianBlack,
                                unfocusedBorderColor = SubtleBorderGray,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newValue,
                            onValueChange = { newValue = it },
                            placeholder = { Text("İçerik (Örn: Minimalist tasarım severim)", color = TextMuted, fontSize = 13.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ObsidianBlack,
                                unfocusedBorderColor = SubtleBorderGray,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (newKey.isNotBlank() && newValue.isNotBlank()) {
                                    onAddMemory(selectedCategory, newKey.trim(), newValue.trim())
                                    newKey = ""
                                    newValue = ""
                                    showAddDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianBlack),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Kaydet", color = PureWhite, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Memory list
            if (memories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Henüz kayıtlı hafıza verisi bulunmuyor.\nSohbet ettikçe kalıcı bilgiler burada toplanır.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(memories, key = { it.id }) { memory ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = PureWhite),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SubtleBorderGray, RoundedCornerShape(14.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = memory.key,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = memory.value,
                                        color = TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                                IconButton(onClick = { onDeleteMemory(memory.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Sil",
                                        tint = TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatModelDisplayName(model: String): String {
    return when (model) {
        "openai/gpt-oss-120b" -> "GPT OSS 120B (Varsayılan)"
        "groq/compound" -> "Groq Compound (Hızlı)"
        "qwen/qwen3.8-27b" -> "Qwen 3.8 27B"
        "gemini-2.5-flash" -> "Gemini 2.5 Flash"
        "gemini-2.5-pro" -> "Gemini 2.5 Pro"
        "gemini-1.5-flash" -> "Gemini 1.5 Flash"
        "gemini-1.5-pro" -> "Gemini 1.5 Pro"
        "mistralai/Mistral-7B-Instruct-v0.3" -> "Mistral 7B Instruct"
        "Qwen/Qwen2.5-7B-Instruct" -> "Qwen 2.5 7B Instruct"
        "microsoft/Phi-3-mini-4k-instruct" -> "Phi-3 Mini 4K"
        else -> model
    }
}

private fun getModelBadge(model: String): String {
    return when (model) {
        "openai/gpt-oss-120b" -> "Varsayılan • Ultra Akıllı & Yüksek Başarım"
        "groq/compound" -> "Groq Compound (Hızlı)"
        "qwen/qwen3.8-27b" -> "Qwen 3.8 27B"
        "mistralai/Mistral-7B-Instruct-v0.3" -> "Varsayılan • Açık Kaynak Model"
        "gemini-2.5-flash" -> "Varsayılan • Yeni Nesil Hızlı Model"
        "gemini-2.5-pro" -> "En Yüksek Muhakeme & Kodlama"
        else -> "Seçilebilir Model"
    }
}
