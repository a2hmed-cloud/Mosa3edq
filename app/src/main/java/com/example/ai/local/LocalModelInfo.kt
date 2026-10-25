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
    val sha256Checksum: String? = null
) {
    companion object {
        val GEMMA_2B_INT4 = LocalModelInfo(
            id = "gemma-2b-it-cpu-int4",
            displayName = "Gemma 2B Instruction (INT4)",
            fileName = "gemma-2b-it-cpu-int4.bin",
            downloadUrl = "https://huggingface.co/google/gemma-2b-it/resolve/main/gemma-2b-it-cpu-int4.bin",
            sizeBytes = 1_350_000_000L,
            formattedSize = "1.26 جيجابايت",
            quantization = "4-bit (INT4)",
            license = "Gemma Terms of Use",
            source = "Google AI / Hugging Face",
            recommendedRam = "3.5+ جيجابايت",
            description = "النموذج الأساسي الموصى به من جوجل والمخصص للهواتف، يقدم فهماً ممتازاً للأسئلة باللغة العربية والإنجليزية ويعمل محلياً بالكامل عبر معالج الهاتف دون إنترنت."
        )

        val SMOL_LM2_1B = LocalModelInfo(
            id = "smollm2-1.7b-cpu-int4",
            displayName = "SmolLM2 1.7B (INT4 خفيف)",
            fileName = "smollm2-1.7b-instruct-int4.bin",
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct/resolve/main/model.bin",
            sizeBytes = 980_000_000L,
            formattedSize = "935 ميجابايت",
            quantization = "4-bit (INT4)",
            license = "Apache 2.0",
            source = "HuggingFaceTB",
            recommendedRam = "2.5+ جيجابايت",
            description = "نموذج خفيف ومناسب للأجهزة ذات السعة المحدودة، سريع الاستجابة ويوفر استهلاك الذاكرة."
        )

        val AVAILABLE_MODELS = listOf(GEMMA_2B_INT4, SMOL_LM2_1B)

        val DEFAULT_MODEL = GEMMA_2B_INT4
    }
}
