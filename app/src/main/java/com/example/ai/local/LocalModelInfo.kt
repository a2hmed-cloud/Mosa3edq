package com.example.ai.local

data class LocalModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val quantization: String,
    val license: String,
    val source: String,
    val recommendedRam: String,
    val description: String,
    val sha256Checksum: String? = null,
    val requiresAuth: Boolean = false
) {
    companion object {
        /**
         * Qwen 2.5 0.5B Instruct: Fully public, un-gated Apache 2.0 model packaged in official
         * LiteRT/MediaPipe task format by litert-community. Superb Arabic and English instruction quality,
         * fast on mobile CPU/GPU, ~521 MB download.
         */
        val QWEN_2_5_0_5B = LocalModelInfo(
            id = "qwen2.5-0.5b-instruct-litert",
            displayName = "Qwen 2.5 0.5B Instruct (موصى به)",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeBytes = 546_660_344L,
            formattedSize = "521 ميجابايت",
            quantization = "INT8 LiteRT Task",
            license = "Apache 2.0 (مفتوح بالكامل)",
            source = "litert-community / Qwen",
            recommendedRam = "1.5+ جيجابايت",
            description = "النموذج الافتراضي الموصى به: متاح للعامة دون أي تسجيل أو قيود ترخيص. يقدم دعماً متميزاً وسريعاً للغة العربية والعامية المصرية، ويناسب كافة هواتف أندرويد.",
            sha256Checksum = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
            requiresAuth = false
        )

        /**
         * Qwen 2.5 1.5B Instruct: Public, un-gated Apache 2.0 model for devices with 4GB+ RAM.
         */
        val QWEN_2_5_1_5B = LocalModelInfo(
            id = "qwen2.5-1.5b-instruct-litert",
            displayName = "Qwen 2.5 1.5B Instruct (عالي الدقة)",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeBytes = 1_597_913_616L,
            formattedSize = "1.48 جيجابايت",
            quantization = "INT8 LiteRT Task",
            license = "Apache 2.0 (مفتوح بالكامل)",
            source = "litert-community / Qwen",
            recommendedRam = "3.5+ جيجابايت",
            description = "نموذج متقدم وعالي الذكاء للأجهزة الحديثة، متاح مجاناً للعامة بدون مصادقة وبتنسيق LiteRT الرسمي.",
            sha256Checksum = "8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0",
            requiresAuth = false
        )

        /**
         * SmolLM 135M Instruct: Ultra-lightweight ~166 MB public model.
         */
        val SMOL_LM_135M = LocalModelInfo(
            id = "smollm-135m-instruct-litert",
            displayName = "SmolLM 135M (فائق الصغر والسرعة)",
            fileName = "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
            downloadUrl = "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeBytes = 166_754_726L,
            formattedSize = "166 ميجابايت",
            quantization = "INT8 LiteRT Task",
            license = "Apache 2.0 (مفتوح بالكامل)",
            source = "litert-community / HuggingFaceTB",
            recommendedRam = "1.0+ جيجابايت",
            description = "نموذج خفيف وسريع جداً، متاح للعامة بحجم 166 ميجابايت فقط، مناسب للهواتف الاقتصادية.",
            sha256Checksum = "6987dce5ac4f71032b070cf13412a5de0e49c04d271a053fc7d9d59a0dc104e9",
            requiresAuth = false
        )

        /**
         * Google Gemma 3 1B IT: Gated model from Google that requires HF user login and license acceptance.
         */
        val GEMMA_3_1B = LocalModelInfo(
            id = "gemma3-1b-it-litert",
            displayName = "Google Gemma 3 1B IT (مقيّد - يتطلب توكن)",
            fileName = "gemma3-1b-it-int4.task",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            sizeBytes = 1_180_000_000L,
            formattedSize = "1.10 جيجابايت",
            quantization = "INT4 LiteRT Task",
            license = "Gemma Terms of Use (Gated)",
            source = "Google AI / litert-community",
            recommendedRam = "3.0+ جيجابايت",
            description = "نموذج جوجل الرسمي المقيّد. يتطلب الموافقة على ترخيص Gemma في صفحة النموذج على Hugging Face وإدخال رمز الوصول HF Token الخاص بك في التطبيق.",
            requiresAuth = true
        )

        val AVAILABLE_MODELS = listOf(QWEN_2_5_0_5B, QWEN_2_5_1_5B, SMOL_LM_135M, GEMMA_3_1B)

        val DEFAULT_MODEL = QWEN_2_5_0_5B

        fun findById(id: String): LocalModelInfo {
            return AVAILABLE_MODELS.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT_MODEL
        }
    }
}
