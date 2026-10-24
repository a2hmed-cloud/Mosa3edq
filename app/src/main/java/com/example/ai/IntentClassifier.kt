package com.example.ai

import org.json.JSONObject

enum class IntentType {
    CONVERSATION,
    DEVICE_ACTION
}

data class ClassificationResult(
    val type: IntentType,
    val directReply: String? = null,
    val targetGoal: String? = null,
    val suggestedTools: List<String> = emptyList()
)

class IntentClassifier(private val geminiApiClient: GeminiApiClient) {

    private val systemPrompt = """
        أنت محرك تصنيف الأوامر والمحادثات للمساعد الذكي العربي المصري (يا مساعد / حمادة).
        مهمتك تصنيف طلب المستخدم إلى أحد نوعين:
        1. CONVERSATION: إذا كان الطلب محادثة عادية، إلقاء تحية، سؤال عام، طلب استفسار، دردشة، أو مزاح.
        2. DEVICE_ACTION: إذا كان الطلب يتطلب إجراء على الهاتف، مثل:
           - فتح تطبيق أو البحث داخله (سبوتيفاي، يوتيوب، الخرائط، الخ)
           - تشغيل موسيقى أو القناة الصوتية
           - ضبط منبه أو مؤقت
           - قراءة الشاشة الحالية أو النقر على عنصر
           - إضافة مهمة جديدة أو تذكير
           - الاستعلام عن الطقس أو الوقت الحالي
           - البحث الجغرافي أو فتح الخريطة
           - التمرير أو التنقل للوراء/الصفحة الرئيسية

        يجب أن يكون الرد بتنسيق JSON فقط بالشكل التالي:
        {
          "type": "CONVERSATION" أو "DEVICE_ACTION",
          "directReply": "ردك المصري اللطيف والمختصر إذا كان النوع CONVERSATION وإلا خله null",
          "targetGoal": "وصف واضح ومختصر للهدف المطلوب تنفيذه على الجهاز باللغة العربية إذا كان DEVICE_ACTION وإلا خله null",
          "suggestedTools": ["spotify", "alarm", "timer", "accessibility", "weather", "task", "maps"]
        }
    """.trimIndent()

    suspend fun classify(userInput: String): ClassificationResult {
        val trimmed = userInput.trim()
        if (trimmed.isEmpty()) {
            return ClassificationResult(
                type = IntentType.CONVERSATION,
                directReply = "أهلاً بيك! اتفضل أنا سامعك، قول لي أقدر أساعدك بإيه النهاردة؟"
            )
        }

        // Local fast heuristic check for offline / immediate responses
        val localClassification = fastHeuristicCheck(trimmed)
        if (localClassification != null) {
            return localClassification
        }

        val prompt = "صنف هذا الطلب: \"$trimmed\""
        val response = geminiApiClient.generateResponse(
            prompt = prompt,
            systemInstruction = systemPrompt,
            jsonSchemaResponse = true
        )

        return response.fold(
            onSuccess = { jsonStr ->
                try {
                    val cleanJson = jsonStr.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
                    val json = JSONObject(cleanJson)
                    val typeStr = json.optString("type", "CONVERSATION")
                    val type = if (typeStr.equals("DEVICE_ACTION", ignoreCase = true)) {
                        IntentType.DEVICE_ACTION
                    } else {
                        IntentType.CONVERSATION
                    }

                    fun optNullableString(key: String): String? {
                        return if (json.has(key) && !json.isNull(key)) {
                            val v = json.getString(key)
                            if (v.equals("null", ignoreCase = true)) null else v
                        } else null
                    }

                    val directReply = optNullableString("directReply")
                    val targetGoal = optNullableString("targetGoal") ?: trimmed
                    val toolsArray = json.optJSONArray("suggestedTools")
                    val toolsList = mutableListOf<String>()
                    if (toolsArray != null) {
                        for (i in 0 until toolsArray.length()) {
                            toolsList.add(toolsArray.getString(i))
                        }
                    }

                    ClassificationResult(
                        type = type,
                        directReply = directReply,
                        targetGoal = targetGoal,
                        suggestedTools = toolsList
                    )
                } catch (e: Exception) {
                    fallbackClassification(trimmed)
                }
            },
            onFailure = {
                fallbackClassification(trimmed)
            }
        )
    }

    private fun fastHeuristicCheck(input: String): ClassificationResult? {
        val lower = input.lowercase()
        val deviceKeywords = listOf(
            "شغل", "افتح", "اضبط", "منبه", "مؤقت", "طقس", "درجة الحرارة",
            "مهمة", "تذكير", "خرائط", "اقرأ الشاشة", "انزل", "اططلع", "ارسل", "سبوتيفاي",
            "spotify", "youtube", "تطبيق", "ابحث في", "مكان", "صيدلية", "مطعم"
        )

        val containsDeviceAction = deviceKeywords.any { lower.contains(it) }

        if (lower in listOf("ازيك", "ازيك يا مساعد", "أهلا", "مرحبا", "سلام عليكم", "صباح الخير", "مساء الخير", "حمادة", "يا مساعد")) {
            return ClassificationResult(
                type = IntentType.CONVERSATION,
                directReply = "أهلاً يا باشا! أنا يا مساعد، أمرني حابب نعمل إيه؟"
            )
        }

        if (containsDeviceAction) {
            return ClassificationResult(
                type = IntentType.DEVICE_ACTION,
                targetGoal = input
            )
        }

        return null
    }

    private fun fallbackClassification(input: String): ClassificationResult {
        val deviceKeywords = listOf("شغل", "افتح", "اضبط", "منبه", "مؤقت", "طقس", "مهمة", "خرائط", "اقرأ")
        val isDevice = deviceKeywords.any { input.contains(it) }

        return if (isDevice) {
            ClassificationResult(
                type = IntentType.DEVICE_ACTION,
                targetGoal = input
            )
        } else {
            ClassificationResult(
                type = IntentType.CONVERSATION,
                directReply = "تمام يا فندم! أنا معاك، قل لي تحب أعمل لك إيه؟"
            )
        }
    }
}
