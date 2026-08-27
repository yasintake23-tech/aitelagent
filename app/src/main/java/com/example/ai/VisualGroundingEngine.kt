package com.example.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class GroundingAction {
    CLICK,
    CLICK_FOLDER,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    SWIPE_UP,
    SWIPE_DOWN,
    TYPE,
    PRESS_BACK,
    PRESS_HOME,
    WAIT,
    NOT_FOUND
}

data class VisualGroundingResult(
    val found: Boolean,
    val action: GroundingAction,
    val targetX: Float = -1f,
    val targetY: Float = -1f,
    val targetName: String = "",
    val textToType: String = "",
    val confidence: Float = 0f,
    val isFolder: Boolean = false,
    val thought: String = "",
    val speechStatus: String = "",
    val targetNodeIndex: Int = -1,
    val targetNode: ScreenNodeData? = null
)

object VisualGroundingEngine {

    private const val TAG = "VisualGroundingEngine"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Compares two screenshots to verify if a tap produced a visual screen change.
     * Returns a similarity ratio between 0.0 (completely different) and 1.0 (100% identical).
     */
    fun computeScreenSimilarity(before: Bitmap?, after: Bitmap?): Float {
        if (before == null || after == null) return 0f
        if (before == after) return 1f

        try {
            val sampleSize = 48
            val scaledBefore = Bitmap.createScaledBitmap(before, sampleSize, sampleSize, false)
            val scaledAfter = Bitmap.createScaledBitmap(after, sampleSize, sampleSize, false)

            var diffAccumulator = 0.0
            val totalPixels = sampleSize * sampleSize

            for (x in 0 until sampleSize) {
                for (y in 0 until sampleSize) {
                    val p1 = scaledBefore.getPixel(x, y)
                    val p2 = scaledAfter.getPixel(x, y)

                    val rDiff = abs(Color.red(p1) - Color.red(p2)) / 255.0
                    val gDiff = abs(Color.green(p1) - Color.green(p2)) / 255.0
                    val bDiff = abs(Color.blue(p1) - Color.blue(p2)) / 255.0

                    diffAccumulator += (rDiff + gDiff + bDiff) / 3.0
                }
            }

            val avgDiff = (diffAccumulator / totalPixels).toFloat()
            return (1f - avgDiff).coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating bitmap similarity", e)
            return 0f
        }
    }

    /**
     * Sends screenshot + clickable candidates to Gemini Vision API with Visual Grounding prompt.
     * Forces the AI to find exact (X, Y) pixel coordinates of the requested target or decide to swipe.
     */
    suspend fun locateTargetOnScreen(
        apiKey: String,
        bitmap: Bitmap?,
        targetDescription: String,
        candidateNodes: List<ScreenNodeData>,
        currentPackage: String,
        stepNumber: Int = 1,
        searchContext: String = ""
    ): VisualGroundingResult = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.ifBlank {
            BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() && it != "MY_GEMINI_API_KEY" } ?: ""
        }

        // 1. Try Gemini Vision API if key and bitmap exist
        if (cleanApiKey.isNotBlank() && bitmap != null) {
            try {
                val visionResult = queryGeminiVisionGrounding(
                    apiKey = cleanApiKey,
                    bitmap = bitmap,
                    targetDescription = targetDescription,
                    candidateNodes = candidateNodes,
                    currentPackage = currentPackage,
                    stepNumber = stepNumber,
                    searchContext = searchContext
                )
                if (visionResult != null) {
                    return@withContext visionResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Vision grounding API failed, falling back to heuristic vision scanner", e)
            }
        }

        // 2. Fallback: Heuristic node matching and spatial layout reasoning
        return@withContext heuristicScreenLocate(
            targetDescription = targetDescription,
            candidateNodes = candidateNodes,
            bitmap = bitmap,
            stepNumber = stepNumber
        )
    }

    private fun queryGeminiVisionGrounding(
        apiKey: String,
        bitmap: Bitmap,
        targetDescription: String,
        candidateNodes: List<ScreenNodeData>,
        currentPackage: String,
        stepNumber: Int,
        searchContext: String
    ): VisualGroundingResult? {
        val width = bitmap.width
        val height = bitmap.height

        // Downscale image to optimal vision resolution (e.g. 960px max dim)
        val scaled = scaleBitmapDown(bitmap, 960)
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        // Compile candidate nodes with ultra-minified format (up to 30 visible nodes)
        val candidateSummary = candidateNodes.take(30).mapIndexed { i, node ->
            val cx = node.bounds.centerX()
            val cy = node.bounds.centerY()
            val textPart = node.text.takeIf { it.isNotBlank() }?.let { "txt:\"${it.replace("\"", "'").take(25)}\"" }
            val descPart = node.contentDescription.takeIf { it.isNotBlank() && it != node.text }?.let { "desc:\"${it.replace("\"", "'").take(25)}\"" }
            val idPart = node.viewId.takeIf { it.isNotBlank() }?.let { "viewId:\"${it.replace("\"", "'").take(20)}\"" }
            val details = listOfNotNull(textPart, descPart, idPart).joinToString(", ")
            if (details.isNotBlank()) {
                "[id:$i, $details, x:$cx, y:$cy]"
            } else {
                "[id:$i, x:$cx, y:$cy]"
            }
        }.joinToString("\n")

        val systemInstruction = """
            Sen Android ReAct (Reason + Act) Görsel Ekran Ajanısın ($width x $height).
            Kullanıcının hedefi: "$targetDescription"
            
            KURAL & RECT FORMATI:
            1. Doğrudan kör eylem yapmak YASAKTIR. Önce <thought>...</thought> içinde durumu ve adayları incele, adım adım planla.
            2. Ardından <action>...</action> etiketiyle tek bir eylem üret.
            
            EYLEM TÜRLERİ:
            - click(id:X) : Listedeki id:X numaralı hedefe tıkla. (Örn: <action>click(id:5)</action>)
            - click(x, y) : Görsel koordinata tıkla. (Örn: <action>click(540, 960)</action>)
            - click_folder(id:X) : Klasörü aç.
            - swipe(left|right|up|down) : Ekranı kaydır.
            
            KATI KURALLAR:
            1. Aranan "$targetDescription" ekranda veya adaylar arasında KESİNLİKLE YOKSA, ASLA başka bir uygulamaya (Gmail, Galeri, Ayarlar vb.) tıklama!
               Bunun yerine: <thought>Eleman ekranda görünmüyor, sayfayı/ekranı kaydırmam lazım.</thought> <action>swipe(left)</action> veya <action>swipe(down)</action> dön.
            2. Rastgele veya alakasız koordinat uydurmak KESİNLİKLE YASAKTIR.
            3. Konumsal yönergeler:
               - "sağdaki" / "sağ": x > ${width / 2}
               - "soldaki" / "sol": x < ${width / 2}
               - "üstteki" / "üst": y < ${height / 2}
               - "alttaki" / "alt": y > ${height / 2}
            
            ÖRNEK:
            <thought>Kullanıcı WhatsApp'ı açmamı istedi. Ekranda 'WhatsApp' etiketli id:5 var, oraya tıklamalıyım.</thought>
            <action>click(id:5)</action>
        """.trimIndent()

        val userPrompt = """
            Hedef: "$targetDescription" (Adım: $stepNumber)
            Ekrandaki Görünür Elemanlar:
            $candidateSummary
        """.trimIndent()

        val partsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Image)
                })
            })
            put(JSONObject().apply {
                put("text", userPrompt)
            })
        }

        val requestBodyJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            }))
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.1)
            })
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Gemini Grounding API returned HTTP ${response.code}")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        val jsonRoot = JSONObject(responseBody)
        val candidates = jsonRoot.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null

        val responseText = parts.getJSONObject(0).optString("text", "")
        return parseGroundingJson(responseText, width, height, targetDescription, candidateNodes)
    }

    private fun parseGroundingJson(
        rawJson: String,
        screenWidth: Int,
        screenHeight: Int,
        targetDescription: String,
        candidateNodes: List<ScreenNodeData>
    ): VisualGroundingResult? {
        try {
            val raw = rawJson.trim()
            var thought = "İncelendi."
            var actionStr = ""
            var x = -1f
            var y = -1f
            var found = false
            var isFolder = false
            var targetName = targetDescription
            var textToType = ""
            var confidence = 0.85f

            var targetNodeIndex = -1
            var targetNode: ScreenNodeData? = null

            // 1. Check for ReAct XML tags: <thought>...</thought> and <action>...</action>
            val thoughtMatch = Regex("<thought>(.*?)</thought>", RegexOption.DOT_MATCHES_ALL).find(raw)
            val actionMatch = Regex("<action>(.*?)</action>", RegexOption.DOT_MATCHES_ALL).find(raw)

            if (actionMatch != null) {
                if (thoughtMatch != null) {
                    thought = thoughtMatch.groupValues[1].trim()
                }
                val rawAction = actionMatch.groupValues[1].trim()
                Log.d(TAG, "ReAct Action parsed: $rawAction (Thought: $thought)")

                val clickIdMatch = Regex("click\\s*\\(\\s*id\\s*:\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)
                val clickCoordMatch = Regex("click\\s*\\(\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)
                val clickFolderMatch = Regex("click_folder\\s*\\(\\s*id\\s*:\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)
                val swipeMatch = Regex("swipe\\s*\\(\\s*(\\w+)\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)

                when {
                    clickIdMatch != null -> {
                        val id = clickIdMatch.groupValues[1].toIntOrNull() ?: -1
                        if (id in candidateNodes.indices) {
                            val node = candidateNodes[id]
                            targetNodeIndex = id
                            targetNode = node
                            x = node.bounds.centerX().toFloat()
                            y = node.bounds.centerY().toFloat()
                            targetName = node.text.ifBlank { node.contentDescription.ifBlank { targetDescription } }
                            actionStr = "CLICK"
                            found = true
                        }
                    }
                    clickFolderMatch != null -> {
                        val id = clickFolderMatch.groupValues[1].toIntOrNull() ?: -1
                        if (id in candidateNodes.indices) {
                            val node = candidateNodes[id]
                            targetNodeIndex = id
                            targetNode = node
                            x = node.bounds.centerX().toFloat()
                            y = node.bounds.centerY().toFloat()
                            targetName = node.text.ifBlank { node.contentDescription.ifBlank { "Klasör" } }
                            actionStr = "CLICK_FOLDER"
                            isFolder = true
                            found = true
                        }
                    }
                    clickCoordMatch != null -> {
                        x = clickCoordMatch.groupValues[1].toFloatOrNull() ?: -1f
                        y = clickCoordMatch.groupValues[2].toFloatOrNull() ?: -1f
                        if (x > 0 && y > 0) {
                            actionStr = "CLICK"
                            found = true
                        }
                    }
                    swipeMatch != null -> {
                        val dir = swipeMatch.groupValues[1].uppercase(Locale.ROOT)
                        actionStr = when (dir) {
                            "LEFT" -> "SWIPE_LEFT"
                            "RIGHT" -> "SWIPE_RIGHT"
                            "UP" -> "SWIPE_UP"
                            "DOWN" -> "SWIPE_DOWN"
                            else -> "SWIPE_LEFT"
                        }
                        found = false
                    }
                    rawAction.contains("not_found", ignoreCase = true) -> {
                        actionStr = "SWIPE_LEFT"
                        found = false
                    }
                }
            }

            // 2. Fallback: Parse JSON if ReAct tags were not present
            if (actionStr.isBlank()) {
                val clean = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val jsonStart = clean.indexOf("{")
                val jsonEnd = clean.lastIndexOf("}")
                if (jsonStart != -1 && jsonEnd > jsonStart) {
                    val obj = JSONObject(clean.substring(jsonStart, jsonEnd + 1))
                    found = obj.optBoolean("found", false)
                    val tIdx = obj.optInt("targetIndex", -1)
                    if (tIdx in candidateNodes.indices) {
                        targetNodeIndex = tIdx
                        targetNode = candidateNodes[tIdx]
                    }
                    actionStr = obj.optString("action", if (found) "CLICK" else "SWIPE_LEFT").uppercase(Locale.ROOT)
                    x = obj.optDouble("x", -1.0).toFloat()
                    y = obj.optDouble("y", -1.0).toFloat()
                    targetName = obj.optString("targetName", targetDescription)
                    textToType = obj.optString("textToType", "")
                    isFolder = obj.optBoolean("isFolder", false)
                    confidence = obj.optDouble("confidence", 0.8).toFloat()
                    thought = obj.optString("thought", thought)
                }
            }

            // If coordinates were parsed but targetNode not set, match closest candidate if within proximity
            if (targetNode == null && found && x > 0 && y > 0) {
                val closest = candidateNodes.minByOrNull {
                    val dx = it.bounds.centerX() - x
                    val dy = it.bounds.centerY() - y
                    dx * dx + dy * dy
                }
                if (closest != null) {
                    val dist = kotlin.math.hypot(closest.bounds.centerX() - x, closest.bounds.centerY() - y)
                    if (dist <= 80f) {
                        targetNode = closest
                        targetNodeIndex = candidateNodes.indexOf(closest)
                    }
                }
            }

            val speechStatus = if (found) "Açılıyor" else "Aranıyor"

            // Anti-Hallucination Guard: If AI claimed found, verify that the coordinate or targetName actually matches targetDescription or a folder
            if (found && !isFolder && x > 0 && y > 0) {
                val qLower = targetDescription.lowercase(Locale("tr", "TR")).trim()
                val targetNameLower = targetName.lowercase(Locale("tr", "TR"))
                
                // Find closest node to (x,y)
                val closestNode = candidateNodes.minByOrNull {
                    val dx = it.bounds.centerX() - x
                    val dy = it.bounds.centerY() - y
                    dx * dx + dy * dy
                }

                if (closestNode != null) {
                    val nodeLabel = (closestNode.text + " " + closestNode.contentDescription).lowercase(Locale("tr", "TR")).trim()
                    val knownApps = listOf("whatsapp", "wp", "gmail", "youtube", "instagram", "galeri", "kamera", "ayarlar", "spotify", "chrome", "haritalar")
                    val queryIsKnown = knownApps.any { qLower.contains(it) }
                    
                    if (queryIsKnown && nodeLabel.isNotBlank()) {
                        val matchesQuery = qLower.split(" ").filter { it.length > 2 }.any { nodeLabel.contains(it) || targetNameLower.contains(it) }
                        if (!matchesQuery) {
                            Log.w(TAG, "Anti-Hallucination Guard: Model hallucinated click on '$nodeLabel' for target '$targetDescription'. Rejecting click!")
                            found = false
                            actionStr = "SWIPE_LEFT"
                            targetNode = null
                            targetNodeIndex = -1
                        }
                    }
                }
            }

            val action = when (actionStr) {
                "CLICK" -> if (found) GroundingAction.CLICK else GroundingAction.SWIPE_LEFT
                "CLICK_FOLDER" -> if (found) GroundingAction.CLICK_FOLDER else GroundingAction.SWIPE_LEFT
                "SWIPE_LEFT" -> GroundingAction.SWIPE_LEFT
                "SWIPE_RIGHT" -> GroundingAction.SWIPE_RIGHT
                "SWIPE_UP" -> GroundingAction.SWIPE_UP
                "SWIPE_DOWN" -> GroundingAction.SWIPE_DOWN
                "TYPE" -> GroundingAction.TYPE
                "WAIT" -> GroundingAction.WAIT
                "PRESS_BACK" -> GroundingAction.PRESS_BACK
                "PRESS_HOME" -> GroundingAction.PRESS_HOME
                else -> if (found) GroundingAction.CLICK else GroundingAction.SWIPE_LEFT
            }

            return VisualGroundingResult(
                found = found,
                action = action,
                targetX = if (x in 0f..screenWidth.toFloat()) x else -1f,
                targetY = if (y in 0f..screenHeight.toFloat()) y else -1f,
                targetName = targetName,
                textToType = textToType,
                confidence = confidence,
                isFolder = isFolder,
                thought = thought,
                speechStatus = speechStatus,
                targetNodeIndex = targetNodeIndex,
                targetNode = targetNode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing grounding JSON", e)
            return null
        }
    }

    private fun heuristicScreenLocate(
        targetDescription: String,
        candidateNodes: List<ScreenNodeData>,
        bitmap: Bitmap?,
        stepNumber: Int
    ): VisualGroundingResult {
        val q = targetDescription.lowercase(Locale("tr", "TR")).trim()
        val width = bitmap?.width ?: 1080
        val height = bitmap?.height ?: 2400

        // Spatial Positional Filter
        var filteredCandidates = candidateNodes
        if (q.contains("sağdaki") || q.contains("sağda") || q.contains("sağ ")) {
            filteredCandidates = filteredCandidates.filter { it.bounds.centerX() >= width * 0.45f }
        } else if (q.contains("soldaki") || q.contains("solda") || q.contains("sol ")) {
            filteredCandidates = filteredCandidates.filter { it.bounds.centerX() <= width * 0.55f }
        }
        if (q.contains("üstteki") || q.contains("üstte") || q.contains("yukarıdaki")) {
            filteredCandidates = filteredCandidates.filter { it.bounds.centerY() <= height * 0.55f }
        } else if (q.contains("alttaki") || q.contains("altta") || q.contains("aşağıdaki")) {
            filteredCandidates = filteredCandidates.filter { it.bounds.centerY() >= height * 0.45f }
        }

        // 1. Direct text/contentDescription match on filtered candidates
        val queryKeywords = q
            .replace("sağdaki", "").replace("soldaki", "").replace("üstteki", "").replace("alttaki", "")
            .replace("aç", "").replace("tıkla", "").replace("bas", "").replace("klasör", "").replace("uygulama", "")
            .trim()

        val matchedNode = if (queryKeywords.isNotBlank()) {
            filteredCandidates.find { node ->
                val label = (node.text + " " + node.contentDescription + " " + node.viewId).lowercase(Locale("tr", "TR"))
                label.contains(queryKeywords)
            } ?: filteredCandidates.find { node ->
                val words = queryKeywords.split(" ").filter { it.length > 2 }
                val label = (node.text + " " + node.contentDescription).lowercase(Locale("tr", "TR"))
                words.isNotEmpty() && words.any { label.contains(it) }
            }
        } else null

        if (matchedNode != null) {
            val cx = matchedNode.bounds.centerX().toFloat()
            val cy = matchedNode.bounds.centerY().toFloat()
            val label = matchedNode.text.ifBlank { matchedNode.contentDescription.ifBlank { targetDescription } }
            val index = candidateNodes.indexOf(matchedNode)
            return VisualGroundingResult(
                found = true,
                action = GroundingAction.CLICK,
                targetX = cx,
                targetY = cy,
                targetName = label,
                confidence = 0.9f,
                thought = "'$label' tespit edildi.",
                speechStatus = "Açılıyor",
                targetNodeIndex = index,
                targetNode = matchedNode
            )
        }

        // 2. Folder match (e.g. Social, Google, Tools) if looking for folder or app inside folder
        val folderKeywords = listOf("sosyal", "social", "google", "araçlar", "tools", "samsung", "uygulamalar", "klasör")
        val isFolderSearch = q.contains("klasör") || q.contains("folder")
        val folderNode = filteredCandidates.find { node ->
            val label = (node.text + " " + node.contentDescription).lowercase(Locale("tr", "TR"))
            folderKeywords.any { label.contains(it) }
        }

        if (folderNode != null && (isFolderSearch || q.contains("whatsapp") || q.contains("instagram") || q.contains("youtube") || q.contains("harita"))) {
            val cx = folderNode.bounds.centerX().toFloat()
            val cy = folderNode.bounds.centerY().toFloat()
            val label = folderNode.text.ifBlank { folderNode.contentDescription.ifBlank { "Klasör" } }
            val index = candidateNodes.indexOf(folderNode)
            return VisualGroundingResult(
                found = true,
                action = GroundingAction.CLICK_FOLDER,
                targetX = cx,
                targetY = cy,
                targetName = label,
                isFolder = true,
                confidence = 0.8f,
                thought = "'$label' klasörü açılıyor.",
                speechStatus = "Klasör açılıyor",
                targetNodeIndex = index,
                targetNode = folderNode
            )
        }

        // 3. Not found on current screen -> decide to swipe
        val swipeAction = when {
            stepNumber <= 2 -> GroundingAction.SWIPE_LEFT
            stepNumber == 3 -> GroundingAction.SWIPE_UP
            stepNumber == 4 -> GroundingAction.SWIPE_DOWN
            else -> GroundingAction.SWIPE_RIGHT
        }

        return VisualGroundingResult(
            found = false,
            action = swipeAction,
            thought = "'$targetDescription' mevcut ekranda bulunamadı.",
            speechStatus = "Aranıyor"
        )
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        var resizedWidth = maxDimension
        var resizedHeight = maxDimension

        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension
            resizedWidth = (resizedHeight * originalWidth.toFloat() / originalHeight.toFloat()).toInt()
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension
            resizedHeight = (resizedWidth * originalHeight.toFloat() / originalWidth.toFloat()).toInt()
        } else {
            resizedHeight = maxDimension
            resizedWidth = maxDimension
        }
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false)
    }
}
