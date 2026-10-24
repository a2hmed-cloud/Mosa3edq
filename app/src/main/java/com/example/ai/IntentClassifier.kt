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
        مهمتك تصنيف طلب المستخدم بصرامة إلى أحد نوعين:

        1. CONVERSATION: فقط إذا كان الطلب محادثة شفهية بحتة، مثل إلقاء تحية ("ازيك"، "صباح الخير"، "سلام عليكم")، أو سؤال معرفي عام ("مين كسب كأس العالم؟")، أو شكر ("شكراً").

        2. DEVICE_ACTION: إذا كان الطلب يتضمن أي أمر أو رغبة في التحكم بالهاتف أو التطبيقات، مثل:
           - فتح أي تطبيق ("افتح واتساب"، "افتح الإعدادات"، "افتح يوتيوب")
           - البحث داخل تطبيق أو في جوجل ("ابحث عن الفيزياء في يوتيوب"، "دَوّر على مطعم")
           - قراءة الشاشة الحالية أو النقر على أزرار ("اقرأ الشاشة"، "اضغط على زر الإرسال")
           - كتابة نص أو إرسال رسالة ("اكتب رسالة"، "ارسل السلام عليكم")
           - ضبط المنبه أو المؤقت ("اضبط منبه 8 الصبح"، "اضبط مؤقت 5 دقائق")
           - الاستعلام عن الطقس أو الوقت ("الطقس في القاهرة"، "الساعة كام؟")
           - إضافة مهمة أو تذكير ("احفظ مهمة شراء مستلزمات")
           - التنقل على الشاشة ("انزل تحت"، "اطلع فوق"، "ارجع ورا")

        قواعد صارمة:
        - إذا احتوى الطلب على أي فعل أمر أو اسم تطبيق أو رغبة في التفاعل مع الهاتف، يجب تصنيفه كـ DEVICE_ACTION فوراً.
        - لا تُرجع ردوداً عامة مثل "تمام يا فندم" عندما يكون الطلب تنفيذاً.

        تنسيق الرد JSON فقط:
        {
          "type": "CONVERSATION" أو "DEVICE_ACTION",
          "directReply": "ردك المختصر إذا كان CONVERSATION وإلا null",
          "targetGoal": "الهدف المباشر للتنفيذ باللغة العربية إذا كان DEVICE_ACTION وإلا null",
          "suggestedTools": ["app_launch", "accessibility", "alarm", "timer", "weather", "task", "spotify", "maps"]
        }
    """.trimIndent()

    suspend fun classify(userInput: String): ClassificationResult {
        val trimmed = userInput.trim()
        if (trimmed.isEmpty()) {
            return ClassificationResult(
                type = IntentType.CONVERSATION,
                directReply = "أهلاً بيك! اتفضل أنا سامعك، قول لي أقدر أساعدك بإيه؟"
            )
        }

        // Local fast heuristic check for immediate action detection
        val localClassification = fastHeuristicCheck(trimmed)
        if (localClassification != null) {
            return localClassification
        }

        val prompt = "صنف هذا الطلب بصرامة: \"$trimmed\""
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
                    val typeStr = json.optString("type", "DEVICE_ACTION")
                    val type = if (typeStr.equals("CONVERSATION", ignoreCase = true)) {
                        IntentType.CONVERSATION
                    } else {
                        IntentType.DEVICE_ACTION
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
                        directReply = if (type == IntentType.CONVERSATION) directReply else null,
                        targetGoal = if (type == IntentType.DEVICE_ACTION) targetGoal else null,
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

    private fun fallbackClassification(input: String): ClassificationResult {
        val lower = input.lowercase()
        val isGreeting = listOf("ازيك", "صباح الخير", "مساء الخير", "أهلا", "مرحبا").any { lower.startsWith(it) }

        return if (isGreeting) {
            ClassificationResult(
                type = IntentType.CONVERSATION,
                directReply = "أهلاً بيك! أنا يا مساعد معاك، طلبك إيه بالنسبة للهاتف والتطبيق؟"
            )
        } else {
            // Default to device action rather than dropping user request
            ClassificationResult(
                type = IntentType.DEVICE_ACTION,
                targetGoal = input
            )
        }
    }

        companion object {
        fun fastHeuristicCheck(input: String): ClassificationResult? {
            val lower = input.lowercase()

            val actionVerbs = listOf(
                "شغل", "افتح", "اضبط", "منبه", "مؤقت", "طقس", "درجة الحرارة", "الساعة",
                "مهمة", "تذكير", "خرائط", "اقرأ", "انزل", "اطلع", "ارسل", "ارسل رسالة", "اكتب",
                "سبوتيفاي", "واتساب", "تليجرام", "فيسبوك", "انستجرام", "يوتيوب", "الإعدادات",
                "الكاميرا", "الاستوديو", "الهاتف", "الرسائل", "المتجر", "كروم", "اضغط",
                "spotify", "whatsapp", "youtube", "settings", "camera", "chrome", "تطبيق", "ابحث"
            )

            val isDeviceAction = actionVerbs.any { lower.contains(it) }
            if (isDeviceAction) {
                return ClassificationResult(
                    type = IntentType.DEVICE_ACTION,
                    targetGoal = input
                )
            }

            val pureGreetings = listOf("ازيك", "أهلا", "أهلاً", "مرحبا", "سلام عليكم", "صباح الخير", "مساء الخير", "حمادة", "يا مساعد", "شكرا", "شكراً")
            if (pureGreetings.any { lower.contains(it) }) {
                return ClassificationResult(
                    type = IntentType.CONVERSATION,
                    directReply = "أهلاً بك! أنا يا مساعد، قول لي أحضر لك إيه أو تحب نفتح تطبيق إيه؟"
                )
            }

            return null
        }


        fun normalizeArabic(text: String): String {
            return text.lowercase()
                .replace(Regex("[أإآ]"), "ا")
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .replace(Regex("[\\u064B-\\u0652]"), "") // Remove tashkeel/diacritics
                .trim()
        }
    }
}

