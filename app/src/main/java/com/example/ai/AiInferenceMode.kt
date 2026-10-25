package com.example.ai

enum class AiInferenceMode(val id: String, val titleArabic: String, val descriptionArabic: String) {
    LOCAL(
        id = "local",
        titleArabic = "محلي فقط (On-Device)",
        descriptionArabic = "يعمل بالكامل على معالج الهاتف دون إنترنت ولا يرسل أي بيانات للسحابة."
    ),
    AUTOMATIC(
        id = "automatic",
        titleArabic = "تلقائي ذكي (هجين)",
        descriptionArabic = "يستخدم النموذج المحلي عند توفره، ويلجأ للسحابة فقط للمهام المعقدة أو عند غياب النموذج المحلي."
    ),
    CLOUD(
        id = "cloud",
        titleArabic = "سحابي فقط (Cloud)",
        descriptionArabic = "يتصل بمزوّدات السحابة (Google Gemini أو النماذج المتوافقة) للحصول على أقصى قدرة تفكير."
    );

    companion object {
        fun fromId(id: String): AiInferenceMode {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTOMATIC
        }
    }
}

data class UnifiedAiResponse(
    val text: String,
    val providerUsed: String,
    val isLocal: Boolean,
    val executionTimeMs: Long,
    val wasFallback: Boolean = false,
    val fallbackReason: String? = null
)
