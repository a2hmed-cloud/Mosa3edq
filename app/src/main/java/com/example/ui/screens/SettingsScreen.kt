package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    val voiceEnabled by settingsRepository.voiceEnabledFlow.collectAsState(initial = true)
    val wakeWordEnabled by settingsRepository.wakeWordEnabledFlow.collectAsState(initial = false)
    val defaultCity by settingsRepository.defaultCityFlow.collectAsState(initial = "القاهرة")

    var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var cityInput by remember(defaultCity) { mutableStateOf(defaultCity) }

    var testStatusMessage by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = { Text("إعدادات يا مساعد", color = TextWhite, fontWeight = FontWeight.Bold) },
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

            // Section 1: Gemini API Key & Model
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("مفتاح Gemini API والنموذج", color = CyanPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    Text(
                        "أدخل مفتاح Gemini API الخاص بك لتأمين الاتصال المباشر واستخدام الذكاء الاصطناعي لفهم الأوامر.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api_key_input"),
                        label = { Text("Gemini API Key") },
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

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    settingsRepository.saveApiKey(apiKeyInput)
                                    testStatusMessage = "تم حفظ المفتاح بنجاح!"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("حفظ المفتاح")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isTesting = true
                                    testStatusMessage = "جاري اختبار الاتصال بالنموذج..."
                                    val result = geminiApiClient.generateResponse(
                                        prompt = "قل مرحباً بكلمة واحدة فقط بالعربية",
                                        modelName = selectedModel
                                    )
                                    isTesting = false
                                    testStatusMessage = result.fold(
                                        onSuccess = { "نجح الاتصال بـ Gemini API: $it" },
                                        onFailure = { "فشل الاتصال: ${it.message}" }
                                    )
                                }
                            },
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(CyanPrimary)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("اختبار الاتصال", color = CyanPrimary)
                        }
                    }

                    testStatusMessage?.let { status ->
                        Text(
                            text = status,
                            color = if (status.contains("نجح")) SuccessGreen else WarningAmber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(color = NavyCard)

                    Text("اختر نموذج Gemini:", color = TextWhite, fontSize = 14.sp)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedModel == "gemini-3.5-flash",
                            onClick = { scope.launch { settingsRepository.saveModel("gemini-3.5-flash") } },
                            colors = RadioButtonDefaults.colors(selectedColor = CyanPrimary)
                        )
                        Text("gemini-3.5-flash (سريع وموصى به)", color = TextWhite, fontSize = 14.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedModel == "gemini-3.1-pro-preview",
                            onClick = { scope.launch { settingsRepository.saveModel("gemini-3.1-pro-preview") } },
                            colors = RadioButtonDefaults.colors(selectedColor = CyanPrimary)
                        )
                        Text("gemini-3.1-pro-preview (تفكير عميق)", color = TextWhite, fontSize = 14.sp)
                    }
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
