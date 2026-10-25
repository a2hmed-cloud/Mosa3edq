package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiInferenceMode
import com.example.ai.local.*
import com.example.data.SettingsRepository
import com.example.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiScreen(
    settingsRepository: SettingsRepository,
    modelManager: ModelManager,
    localLlmClient: LocalLlmClient,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val currentModeStr by settingsRepository.aiInferenceModeFlow.collectAsState(initial = "automatic")
    val selectedMode = AiInferenceMode.fromId(currentModeStr)

    val downloadState by modelManager.downloadState.collectAsState()
    val engineState by localLlmClient.engineState.collectAsState()
    val metrics by localLlmClient.metrics.collectAsState()

    var showDownloadConfirmation by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // Test prompt states
    var testPromptInput by remember { mutableStateOf("مرحباً، من أنت وماذا تستطيع أن تفعل للهاتف؟") }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var isRunningTest by remember { mutableStateOf(false) }

    val isModelInstalled = modelManager.isModelInstalled()
    val availableStorageStr = remember(downloadState) { modelManager.getFormattedAvailableStorage() }

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "الذكاء الاصطناعي المحلي (On-Device LLM)",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Section 1: AI Inference Mode Selector
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("نمط تشغيل الذكاء الاصطناعي (AI Mode)", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        "حدد كيف يتعامل حمادة مع أوامرك ومحادثاتك. يمكنك تشغيل المعالجة محلياً بالكامل لحماية الخصوصية أو دمجها مع السحابة.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiInferenceMode.values().forEach { mode ->
                            val isSelected = selectedMode == mode
                            Surface(
                                onClick = {
                                    scope.launch {
                                        settingsRepository.saveAiInferenceMode(mode.id)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) CyanPrimary.copy(alpha = 0.15f) else NavyBackground.copy(alpha = 0.5f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) CyanPrimary else NavyCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            scope.launch {
                                                settingsRepository.saveAiInferenceMode(mode.id)
                                            }
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = CyanPrimary)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = mode.titleArabic,
                                            color = if (isSelected) CyanPrimary else TextWhite,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = mode.descriptionArabic,
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Model Information & Download Manager
            val modelInfo = LocalModelInfo.DEFAULT_MODEL
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("النموذج المحلي المدمج", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        // Installation Status Badge
                        Surface(
                            color = when (downloadState) {
                                is ModelDownloadState.Ready -> SuccessGreen.copy(alpha = 0.2f)
                                is ModelDownloadState.Downloading, is ModelDownloadState.Verifying -> WarningAmber.copy(alpha = 0.2f)
                                is ModelDownloadState.Error -> ErrorRed.copy(alpha = 0.2f)
                                is ModelDownloadState.Idle -> if (isModelInstalled) SuccessGreen.copy(alpha = 0.2f) else NavyCard
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = when (downloadState) {
                                    is ModelDownloadState.Ready -> "جاهز ومثبت ✓"
                                    is ModelDownloadState.Downloading -> "جاري التحميل..."
                                    is ModelDownloadState.Verifying -> "جاري التحقق..."
                                    is ModelDownloadState.Error -> "فشل التحميل"
                                    is ModelDownloadState.Idle -> if (isModelInstalled) "جاهز ومثبت ✓" else "غير محمل"
                                },
                                color = when (downloadState) {
                                    is ModelDownloadState.Ready -> SuccessGreen
                                    is ModelDownloadState.Downloading, is ModelDownloadState.Verifying -> WarningAmber
                                    is ModelDownloadState.Error -> ErrorRed
                                    is ModelDownloadState.Idle -> if (isModelInstalled) SuccessGreen else TextMuted
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "${modelInfo.displayName} — معمارية Google LiteRT-LM",
                        color = TextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = modelInfo.description,
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    // Specs Grid
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الكمية والحجم:", color = TextMuted, fontSize = 12.sp)
                            Text("${modelInfo.quantization} (${modelInfo.formattedSize})", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("المصدر والترخيص:", color = TextMuted, fontSize = 12.sp)
                            Text("${modelInfo.source} (${modelInfo.license})", color = TextWhite, fontSize = 12.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الذاكرة الموصى بها (RAM):", color = TextMuted, fontSize = 12.sp)
                            Text(modelInfo.recommendedRam, color = TextWhite, fontSize = 12.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("المساحة الحرة على الهاتف:", color = TextMuted, fontSize = 12.sp)
                            Text(availableStorageStr, color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = NavyCard)

                    // Download Progress View
                    if (downloadState is ModelDownloadState.Downloading) {
                        val dl = downloadState as ModelDownloadState.Downloading
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("جاري تنزيل أوزان النموذج...", color = CyanPrimary, fontSize = 12.sp)
                                Text("${dl.progressPercent}%", color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            LinearProgressIndicator(
                                progress = { dl.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = CyanPrimary,
                                trackColor = NavyCard
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val downloadedMb = dl.downloadedBytes / (1024 * 1024)
                                val totalMb = dl.totalBytes / (1024 * 1024)
                                Text("$downloadedMb ميجابايت من $totalMb ميجابايت", color = TextMuted, fontSize = 11.sp)
                                Text(String.format("%.1f ميجابايت/ثانية", dl.speedMbPerSec), color = TextMuted, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    downloadJob?.cancel()
                                    downloadJob = null
                                    modelManager.refreshStatus()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = TextWhite),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("إلغاء التحميل", fontSize = 12.sp)
                            }
                        }
                    } else if (downloadState is ModelDownloadState.Verifying) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري التحقق من سلامة ملف النموذج...", color = CyanPrimary, fontSize = 12.sp)
                        }
                    } else if (isModelInstalled) {
                        // Model is installed -> Options to re-check or delete
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        localLlmClient.initialize()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("تهيئة المحرك", color = CyanPrimary, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { showDeleteConfirmation = true },
                                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.2f), contentColor = ErrorRed),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("حذف النموذج", fontSize = 12.sp)
                            }
                        }
                    } else {
                        // Model not downloaded -> Download Button
                        Button(
                            onClick = { showDownloadConfirmation = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground),
                            modifier = Modifier.fillMaxWidth().testTag("download_model_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تحميل النموذج الآن (${modelInfo.formattedSize})", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (downloadState is ModelDownloadState.Error) {
                        val error = (downloadState as ModelDownloadState.Error).message
                        Text(
                            text = error,
                            color = ErrorRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Section 3: Engine State & Hardware Benchmark Metrics
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("حالة المحرك ومؤشرات الأداء", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("حالة المحرك في الذاكرة:", color = TextMuted, fontSize = 12.sp)
                        Text(
                            text = when (engineState) {
                                is LocalLlmState.Ready -> "جاهز في الذاكرة ✓"
                                is LocalLlmState.Loading -> "قيد التحميل..."
                                is LocalLlmState.Generating -> "يقوم بالتوليد الآن..."
                                is LocalLlmState.Error -> "خطأ في التهيئة"
                                is LocalLlmState.Uninitialized -> if (isModelInstalled) "في وضع الاستعداد" else "غير محمل"
                            },
                            color = when (engineState) {
                                is LocalLlmState.Ready -> SuccessGreen
                                is LocalLlmState.Loading, is LocalLlmState.Generating -> CyanPrimary
                                is LocalLlmState.Error -> ErrorRed
                                is LocalLlmState.Uninitialized -> TextMuted
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (metrics.lastLoadTimeMs > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("وقت تحميل النموذج بالذاكرة:", color = TextMuted, fontSize = 12.sp)
                            Text("${metrics.lastLoadTimeMs} مللي ثانية", color = TextWhite, fontSize = 12.sp)
                        }
                    }

                    if (metrics.lastInferenceTimeMs > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("مدة آخر توليد:", color = TextMuted, fontSize = 12.sp)
                            Text("${metrics.lastInferenceTimeMs} مللي ثانية", color = TextWhite, fontSize = 12.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("سرعة التوليد التقريبية:", color = TextMuted, fontSize = 12.sp)
                            Text(String.format("%.1f كلمة/ثانية", metrics.tokensPerSecond), color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (metrics.approximateMemoryUsageMb > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("استهلاك الذاكرة التقريبي (RAM):", color = TextMuted, fontSize = 12.sp)
                            Text("~${metrics.approximateMemoryUsageMb} ميجابايت", color = TextWhite, fontSize = 12.sp)
                        }
                    }

                    // Button to clear memory
                    if (engineState is LocalLlmState.Ready) {
                        OutlinedButton(
                            onClick = {
                                localLlmClient.clearMemory()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تفريغ الذاكرة (Clear RAM)", color = CyanPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Section 4: Offline Prompt Live Test
            Card(
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("اختبار الذكاء الاصطناعي دون إنترنت", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        "جرب كتابة سؤال لاختبار سرعة وجودة رد النموذج المحلي مباشرة من هاتفك:",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = testPromptInput,
                        onValueChange = { testPromptInput = it },
                        modifier = Modifier.fillMaxWidth().testTag("local_test_prompt_input"),
                        label = { Text("نص السؤال أو الأمر") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = TextMuted,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        ),
                        singleLine = false,
                        maxLines = 3
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                isRunningTest = true
                                testResultText = null
                                val result = localLlmClient.generateResponse(testPromptInput)
                                isRunningTest = false
                                testResultText = result.fold(
                                    onSuccess = { it },
                                    onFailure = { "فشل الاختبار: ${it.message}" }
                                )
                            }
                        },
                        enabled = isModelInstalled && !isRunningTest,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground),
                        modifier = Modifier.fillMaxWidth().testTag("run_local_test_button")
                    ) {
                        if (isRunningTest) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NavyBackground, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري المعالجة محلياً بالمعالج...")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تشغيل الاختبار بدون إنترنت")
                        }
                    }

                    testResultText?.let { output ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = NavyBackground,
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("رد النموذج المحلي (On-Device Output):", color = CyanPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(output, color = TextWhite, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Section 5: Offline Privacy & Limitations Banner
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CyanPrimary.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "أمان تام وخصوصية مطلقة",
                            color = CyanPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "في الوضع المحلي، تتم جميع عمليات تحليل النصوص والمحادثات داخل رقاقة المعالج بهاتفك دون إرسال كلمة واحدة لأي خادم خارجي.\nملاحظة: الإجراءات التي تتطلب إنترنت بطبيعتها (مثل البحث في الخرائط أو تشغيل الموسيقى عبر سبوتيفاي) ستظل بحاجة لاتصال شبكة.",
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

        }
    }

    // Confirmation Dialog for Downloading 1.26 GB Model
    if (showDownloadConfirmation) {
        AlertDialog(
            onDismissRequest = { showDownloadConfirmation = false },
            containerColor = NavySurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = CyanPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تأكيد تحميل النموذج المحلي", color = TextWhite, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "سيتم تنزيل نموذج Gemma 2B INT4 بحجم ${LocalModelInfo.DEFAULT_MODEL.formattedSize}.\n\nيُوصى بالاتصال بشبكة Wi-Fi قبل البدء لضمان استقرار التحميل وتوفير باقة الهاتف.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDownloadConfirmation = false
                        downloadJob = scope.launch {
                            modelManager.downloadModel()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground)
                ) {
                    Text("بدء التحميل", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDownloadConfirmation = false },
                    border = androidx.compose.foundation.BorderStroke(1.dp, TextMuted)
                ) {
                    Text("إلغاء", color = TextWhite)
                }
            }
        )
    }

    // Confirmation Dialog for Deleting Model
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = NavySurface,
            title = {
                Text("تأكيد حذف النموذج", color = TextWhite, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "هل أنت متأكد من رغبتك في حذف ملف النموذج من الهاتف؟ سيؤدي ذلك لتحرير ${LocalModelInfo.DEFAULT_MODEL.formattedSize} من مساحة التخزين، ويمكنك إعادة تحميله في أي وقت.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        localLlmClient.close()
                        modelManager.deleteModel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = TextWhite)
                ) {
                    Text("حذف وتفريغ المساحة", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteConfirmation = false },
                    border = androidx.compose.foundation.BorderStroke(1.dp, TextMuted)
                ) {
                    Text("تراجع", color = TextWhite)
                }
            }
        )
    }
}
