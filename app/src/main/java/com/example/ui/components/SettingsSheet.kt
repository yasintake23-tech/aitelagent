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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PersonalityTone
import com.example.data.model.UserProfileEntity
import com.example.ui.theme.GentleRose
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SubtleBorderGray
import com.example.ui.theme.SubtleGrayBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

data class ProviderOption(
    val id: String,
    val name: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val placeholder: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    sheetState: SheetState,
    profile: UserProfileEntity?,
    selectedProviderId: String = profile?.preferredAiProvider ?: "gemini",
    selectedModel: String = "",
    availableModels: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onUpdateProvider: (String) -> Unit = {},
    onUpdateSelectedModel: (String) -> Unit = {},
    onUpdateTone: (PersonalityTone) -> Unit,
    onUpdateAiName: (String) -> Unit,
    onUpdateUserName: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onReplayAwakening: () -> Unit,
    onClearChatHistory: () -> Unit
) {
    val currentTone = PersonalityTone.fromString(profile?.personalityTone)
    var editAiName by remember(profile?.aiName) { mutableStateOf(profile?.aiName ?: "Nova") }
    var editUserName by remember(profile?.userName) { mutableStateOf(profile?.userName ?: "") }
    var editApiKey by remember(profile?.customApiKey) { mutableStateOf(profile?.customApiKey ?: "") }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }

    val providers = remember {
        listOf(
            ProviderOption("gemini", "Google Gemini", "Google AI Studio", Icons.Default.AutoAwesome, "AIzaSy..."),
            ProviderOption("groq", "Groq Cloud LPU", "GPT OSS 120B (Varsayılan)", Icons.Default.FlashOn, "gsk_..."),
            ProviderOption("huggingface", "Hugging Face", "Açık Kaynak Modeller", Icons.Default.Hub, "hf_..."),
            ProviderOption("local", "Cihaz İçi Yerel", "Çevrimdışı Çekirdek", Icons.Default.Psychology, "")
        )
    }

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
                .verticalScroll(rememberScrollState())
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
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Ayarlar",
                            tint = ObsidianBlack,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Asistan Ayarları",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // AI Provider Selector
            Text(
                text = "Yapay Zekâ Motoru & Sağlayıcı",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { p ->
                    val isSelected = p.id.equals(selectedProviderId, ignoreCase = true)
                    Surface(
                        color = if (isSelected) SubtleGrayBg else PureWhite,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) ObsidianBlack else SubtleBorderGray,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onUpdateProvider(p.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(if (isSelected) ObsidianBlack else Color(0xFFF1F5F9), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = p.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) PureWhite else ObsidianBlack,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = p.name,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                    Text(
                                        text = p.description,
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Seçili",
                                    tint = ObsidianBlack,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Model Selection Dropdown (when provider has multiple models like Groq, Gemini, HF)
            if (availableModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Model Seçimi",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        color = SubtleGrayBg,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SubtleBorderGray, RoundedCornerShape(12.dp))
                            .clickable { isModelDropdownExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                                contentDescription = "Model Menüsü",
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

            Spacer(modifier = Modifier.height(20.dp))

            // Personality Tone Selector
            Text(
                text = "Kişilik & İletişim Tonu",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonalityTone.entries.forEach { tone ->
                    val isSelected = tone == currentTone
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) SubtleGrayBg else PureWhite
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) ObsidianBlack else SubtleBorderGray,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onUpdateTone(tone) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isSelected) ObsidianBlack else SubtleBorderGray,
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = tone.displayName,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = tone.description,
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Identity fields
            Text(
                text = "İsimler & Kimlik",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = editAiName,
                onValueChange = {
                    editAiName = it
                    onUpdateAiName(it)
                },
                label = { Text("AI Asistan Adı", color = TextSecondary, fontSize = 12.sp) },
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
                value = editUserName,
                onValueChange = {
                    editUserName = it
                    onUpdateUserName(it)
                },
                label = { Text("Kullanıcı Adı (Sana hitap)", color = TextSecondary, fontSize = 12.sp) },
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

            if (!selectedProviderId.equals("local", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(20.dp))

                // Custom API Key
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = ObsidianBlack, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (selectedProviderId.lowercase()) {
                            "groq" -> "Groq API Key (gsk_...)"
                            "huggingface" -> "Hugging Face Token (hf_...)"
                            else -> "Gemini API Key"
                        },
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = editApiKey,
                    onValueChange = {
                        editApiKey = it
                        onUpdateApiKey(it)
                    },
                    placeholder = {
                        Text(
                            when (selectedProviderId.lowercase()) {
                                "groq" -> "gsk_..."
                                "huggingface" -> "hf_..."
                                else -> "AIzaSy..."
                            },
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    },
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
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Reset / Replay Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onClearChatHistory,
                    colors = ButtonDefaults.buttonColors(containerColor = SubtleGrayBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sohbeti Temizle", color = TextPrimary, fontSize = 12.sp)
                }

                Button(
                    onClick = onReplayAwakening,
                    colors = ButtonDefaults.buttonColors(containerColor = GentleRose.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, tint = GentleRose, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Yeniden Başlat", color = GentleRose, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
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
