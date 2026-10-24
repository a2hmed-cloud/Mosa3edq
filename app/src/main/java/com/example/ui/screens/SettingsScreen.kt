package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.GeminiApiClient
import com.example.data.SettingsRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    geminiApiClient: GeminiApiClient,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val apiKey by settingsRepository.apiKeyFlow.collectAsState(initial = "")
    val selectedModel by settingsRepository.modelFlow.collectAsState(initial = "gemini-3.5-flash")
    val selectedProvider by settingsRepository.providerFlow.collectAsState(initial = "gemini")
    val baseUrl by settingsRepository.baseUrlFlow.collectAsState(initial = "https://generativelanguage.googleapis.com")
    val customModelOverride by settingsRepository.customModelOverrideFlow.collectAsState(initial = "")

    val voiceEnabled by settingsRepository.voiceEnabledFlow.collectAsState(initial = true)
    val wakeWordEnabled by settingsRepository.wakeWordEnabledFlow.collectAsState(initial = false)
    val defaultCity by settingsRepository.defaultCityFlow.collectAsState(initial = "القاهرة")

    var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var providerInput by remember(selectedProvider) { mutableStateOf(selectedProvider) }
    var baseUrlInput by remember(baseUrl) { mutableStateOf(baseUrl) }
    var customModelInput by remember(customModelOverride) { mutableStateOf(customModelOverride) }
    var cityInput by remember(defaultCity) { mutableStateOf(defaultCity) }

    var keyVisible by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchStatusMessage by remember { mutableStateOf<String?>(null) }

    var testStatusMessage by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = { Text("إعدادات يا مساعد والذكاء الاصطناعي", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = CyanPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavyBackground)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Section 1: AI Provider & Credentials
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("مزوّد خدمة الذكاء الاصطناعي (AI Provider)", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    Text(
                        "اختر المزوّد المطلوب وأدخل بيانات الاتصال. يدعم التطبيق Google Gemini والمزوّدين المتوافقين مع OpenAI (مثل BluesMinds, Groq, OpenRouter, LocalAI).",
                        color = TextMuted,
                        fontSize = 13.sp
                    )

                    // Provider Options Radio Group
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = providerInput == "gemini",
                                onClick = {
                                    providerInput = "gemini"
                                    baseUrlInput = "https://generativelanguage.googleapis.com"
                                    scope.launch {
                                        settingsRepository.saveProvider("gemini")
                                        settingsRepository.saveBaseUrl("https://generativelanguage.googleapis.com")
                                    }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = CyanPrimary)
                            )
                            Text("Google Gemini (المزوّد الافتراضي)", color = TextWhite, fontSize = 14.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = providerInput == "openai_compatible",
                                onClick = {
                                    providerInput = "openai_compatible"
                                    if (baseUrlInput.contains("generativelanguage")) {
                                        baseUrlInput = "https://api.bluesminds.com/v1"
                                    }
                                    scope.launch {
                                        settingsRepository.saveProvider("openai_compatible")
                                        settingsRepository.saveBaseUrl(baseUrlInput)
                                    }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = CyanPrimary)
                            )
                            Text("OpenAI Compatible (BluesMinds / Groq / OpenRouter / Custom)", color = TextWhite, fontSize = 14.sp)
                        }
                    }

                    // Base URL Input (for OpenAI Compatible)
                    if (providerInput == "openai_compatible") {
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = {
                                baseUrlInput = it
                                scope.launch { settingsRepository.saveBaseUrl(it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("عنوان API الأساسي (Base URL)") },
                            placeholder = { Text("https://api.bluesminds.com/v1") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = TextMuted,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            singleLine = true
                        )
                    }

                    // API Key Field
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            scope.launch { settingsRepository.saveApiKey(it) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api_key_input"),
                        label = { Text(if (providerInput == "gemini") "Gemini API Key" else "API Key / Bearer Token") },
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "عرض المفتاح",
                                    tint = TextMuted
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = TextMuted,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        ),
                        singleLine = true
                    )

                    // Fetch Models & Save Row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isFetchingModels = true
                                    fetchStatusMessage = "جاري جلب النماذج المتاحة من المزوّد..."
                                    val result = geminiApiClient.fetchAvailableModels(
                                        provider = providerInput,
                                        baseUrl = baseUrlInput,
                                        apiKey = apiKeyInput
                                    )
                                    isFetchingModels = false
                                    result.fold(
                                        onSuccess = { models ->
                                            fetchedModels = models
                                            fetchStatusMessage = "تم جلب ${models.size} نموذجاً بنجاح!"
                                        },
                                        onFailure = { err ->
                                            fetchStatusMessage = "عفواً، تعذر جلب النماذج: ${err.message}"
                                        }
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isFetchingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NavyBackground, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("جلب النماذج المتاحة")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isTesting = true
                                    testStatusMessage = "جاري اختبار الاتصال..."
                                    val effectiveModel = customModelInput.ifBlank { selectedModel }
                                    val result = geminiApiClient.generateResponse(
                                        prompt = "قل مرحباً بكلمة واحدة فقط بالعربية",
                                        modelName = effectiveModel
                                    )
                                    isTesting = false
                                    testStatusMessage = result.fold(
                                        onSuccess = { "نجح الاتصال بالمزوّد والنموذج: $it" },
                                        onFailure = { "فشل الاتصال: ${it.message}" }
                                    )
                                }
                            },
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("اختبار الاتصال", color = CyanPrimary)
                        }
                    }

                    fetchStatusMessage?.let { status ->
                        Text(
                            text = status,
                            color = if (status.contains("بنجاح")) SuccessGreen else WarningAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    testStatusMessage?.let { status ->
                        Text(
                            text = status,
                            color = if (status.contains("نجح")) SuccessGreen else ErrorRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(color = NavyCard)

                    // Fetched Models Selection List
                    Text("النموذج المختار للتعامل مع المساعد:", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                    if (fetchedModels.isNotEmpty()) {
                        Text("النماذج المتاحة من المزوّد:", color = TextMuted, fontSize = 12.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            fetchedModels.take(15).forEach { mName ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            customModelInput = mName
                                            scope.launch {
                                                settingsRepository.saveModel(mName)
                                                settingsRepository.saveCustomModelOverride(mName)
                                            }
                                        }
                                ) {
                                    RadioButton(
                                        selected = selectedModel == mName || customModelInput == mName,
                                        onClick = {
                                            customModelInput = mName
                                            scope.launch {
                                                settingsRepository.saveModel(mName)
                                                settingsRepository.saveCustomModelOverride(mName)
                                            }
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = CyanPrimary)
                                    )
                                    Text(mName, color = TextWhite, fontSize = 13.sp)
                                }
                            }
                        }
                    } else if (providerInput == "gemini") {
                        // Standard Gemini presets
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedModel == "gemini-3.5-flash",
                                onClick = {
                                    customModelInput = "gemini-3.5-flash"
                                    scope.launch { settingsRepository.saveModel("gemini-3.5-flash") }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = CyanPrimary)
                            )
                            Text("gemini-3.5-flash (سريع وموصى به)", color = TextWhite, fontSize = 14.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedModel == "gemini-3.1-pro-preview",
                                onClick = {
                                    customModelInput = "gemini-3.1-pro-preview"
                                    scope.launch { settingsRepository.saveModel("gemini-3.1-pro-preview") }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = CyanPrimary)
                            )
                            Text("gemini-3.1-pro-preview (تفكير عميق)", color = TextWhite, fontSize = 14.sp)
                        }
                    }

                    // Manual Model Override Field
                    OutlinedTextField(
                        value = customModelInput,
                        onValueChange = {
                            customModelInput = it
                            scope.launch {
                                settingsRepository.saveModel(it)
                                settingsRepository.saveCustomModelOverride(it)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("اسم أو معرّف النموذج يدويًا (Model Identifier)") },
                        placeholder = { Text("nvidia/ising-calibration-1.5-31b") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = TextMuted,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        ),
                        singleLine = true
                    )
                }
            }

            // Section 2: Speech & Voice Settings
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("الصوت والاستجابة الكلامية", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الردود الصوتية (Text-to-Speech)", color = TextWhite, fontSize = 14.sp)
                            Text("نطق الردود باللغة العربية المصرية تلقائياً", color = TextMuted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = voiceEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsRepository.setVoiceResponseEnabled(enabled) }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary)
                        )
                    }

                    HorizontalDivider(color = NavyCard)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الاستماع بعبارة التنبيه \"يا مساعد\"", color = TextWhite, fontSize = 14.sp)
                            Text("تفعيل خدمة الاستماع المستمر في الخلفية (تتطلب إشعاراً دائماً)", color = TextMuted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = wakeWordEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsRepository.setWakeWordEnabled(enabled) }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary)
                        )
                    }
                }
            }

            // Section 3: Weather Default City
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("إعدادات الطقس والمدينة الافتراضية", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = cityInput,
                        onValueChange = { cityInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("المدينة الافتراضية (مثال: القاهرة، الإسكندرية)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = TextMuted,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        ),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            scope.launch { settingsRepository.setDefaultCity(cityInput) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground)
                    ) {
                        Text("حفظ المدينة")
                    }
                }
            }

        }
    }
}
