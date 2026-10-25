package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accessibility.YaMosaedAccessibilityService
import com.example.agent.AgentOrchestrator
import com.example.agent.AgentState
import com.example.agent.AgentStepLog
import com.example.speech.SpeechController
import com.example.ui.components.RobotFaceView
import com.example.ui.components.SpeechBubbleView
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAssistantScreen(
    orchestrator: AgentOrchestrator,
    speechController: SpeechController,
    onNavigateToSettings: () -> Unit,
    onNavigateToTasks: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToPermissions: () -> Unit
) {
    val agentState by orchestrator.agentState.collectAsState()
    val isListening by speechController.isListening.collectAsState()
    val isSpeaking by speechController.isSpeaking.collectAsState()
    val liveText by speechController.liveTranscription.collectAsState()
    val lastSummary by orchestrator.lastSummary.collectAsState()
    val liveSteps by orchestrator.liveSteps.collectAsState()
    val isTaskRunning by orchestrator.isTaskRunning.collectAsState()
    val currentStepIndex by orchestrator.currentStepIndex.collectAsState()
    val currentGoal by orchestrator.currentGoal.collectAsState()
    val isAccessibilityConnected by YaMosaedAccessibilityService.isConnected.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var isKeyboardExpanded by remember { mutableStateOf(false) }
    var showStepsSheet by remember { mutableStateOf(false) }

    val quickActions = listOf(
        "شغل أغنية عمرو دياب في سبوتيفاي" to "🎵",
        "الطقس في القاهرة النهاردة" to "☀️",
        "اضبط منبه الساعة 8 الصبح" to "⏰",
        "اضبط مؤقت 60 ثانية" to "⏱️",
        "احفظ مهمة شراء مستلزمات" to "📝",
        "البحث عن أقرب صيدلية" to "🗺️",
        "افحص الشاشة الحالية واقرأ محتواها" to "👁️"
    )

    // Compute dynamic speech bubble text
    val bubbleText = when {
        liveText.isNotBlank() -> liveText
        agentState is AgentState.Thinking -> "بفكر وأحلل طلبك بدقة..."
        agentState is AgentState.Planning -> "بجهز خطة تنفيذ المهمة..."
        agentState is AgentState.Executing -> {
            val exec = agentState as AgentState.Executing
            if (exec.thought.isNotBlank()) exec.thought else "أنفذ خطوة: ${exec.actionName}"
        }
        agentState is AgentState.Observing -> "بفحص عناصر الشاشة بعد الإجراء..."
        agentState is AgentState.Verifying -> "أتأكد من استجابة النظام..."
        agentState is AgentState.WaitingApproval -> "أحتاج موافقتك الصريحة للمتابعة..."
        agentState is AgentState.Speaking -> (agentState as AgentState.Speaking).message
        agentState is AgentState.Success -> (agentState as AgentState.Success).summary
        agentState is AgentState.Confused -> (agentState as AgentState.Confused).reason
        agentState is AgentState.Failed -> (agentState as AgentState.Failed).error
        lastSummary.isNotBlank() -> lastSummary
        isListening -> "سامعك كويس، اتفضل اطلب اللي تحبه..."
        else -> "أهلاً بك! أنا حمادة، مساعدك الذكي الحقيقي للتحكم بالهاتف."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NavySurface,
                        NavyBackground
                    )
                )
            )
    ) {
        // Top Bar & Accessibility Warning
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = NavySurface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isListening) ErrorRed else if (isSpeaking) SuccessGreen else if (isTaskRunning) WarningAmber else CyanPrimary
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isTaskRunning) "حمادة ينفّذ (خطوة $currentStepIndex)" else "Hamada AI",
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Minimal Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onNavigateToPermissions,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(NavySurface.copy(alpha = 0.85f))
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = "الأذونات",
                            tint = if (isAccessibilityConnected) SuccessGreen else WarningAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onNavigateToTasks,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(NavySurface.copy(alpha = 0.85f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "المهام", tint = CyanPrimary, modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = onNavigateToLogs,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(NavySurface.copy(alpha = 0.85f))
                    ) {
                        Icon(Icons.Default.History, contentDescription = "السجل", tint = CyanPrimary, modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(NavySurface.copy(alpha = 0.85f))
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "الإعدادات", tint = CyanPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Accessibility Warning Alert Banner if disabled
            if (!isAccessibilityConnected) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    onClick = onNavigateToPermissions,
                    color = WarningAmber.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "خدمة إمكانية الوصول غير مفعلة (انقر هنا للتفعيل للتحكم بالشاشة)",
                            color = WarningAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Center Living Robot Face & Speech Bubble
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SpeechBubbleView(
                text = bubbleText,
                visible = true,
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .fillMaxWidth(0.92f)
            )

            RobotFaceView(
                agentState = agentState,
                isSpeaking = isSpeaking,
                isListening = isListening,
                modifier = Modifier
                    .clickable {
                        if (isListening) {
                            speechController.stopListening()
                        } else {
                            speechController.onSpeechRecognizedCallback = { text ->
                                orchestrator.processUserInput(text)
                            }
                            speechController.startListening()
                        }
                    }
            )

            // Live Task Status Tracker Pill (What Hamada Did)
            if (liveSteps.isNotEmpty() || isTaskRunning) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    onClick = { showStepsSheet = true },
                    color = NavySurface.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isTaskRunning) CyanPrimary.copy(alpha = 0.6f) else TextMuted.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            if (isTaskRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = CyanPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            Column {
                                Text(
                                    text = if (isTaskRunning) "ينفّذ الآن: الخطوة $currentStepIndex" else "اكتملت الخطوات (${liveSteps.size} خطوة)",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                val latestStep = liveSteps.lastOrNull()
                                if (latestStep != null) {
                                    Text(
                                        text = latestStep.thought.ifBlank { latestStep.toolName },
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Text(
                            text = "عرض التفاصيل 🔍",
                            color = CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Bottom Controls Overlay (Mic FAB, Cancel Button, Quick Chips, Text Input Bar)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Collapsible Quick Prompts
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickActions) { (actionText, icon) ->
                    SuggestionChip(
                        onClick = { orchestrator.processUserInput(actionText) },
                        label = { Text("$icon $actionText", color = TextWhite, fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = NavySurface.copy(alpha = 0.85f)),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = CyanPrimary.copy(alpha = 0.35f)
                        )
                    )
                }
            }

            // Central Interaction Controls (Cancel button + Mic button + Keyboard toggle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Keyboard toggle button
                IconButton(
                    onClick = { isKeyboardExpanded = !isKeyboardExpanded },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(NavySurface)
                ) {
                    Icon(
                        imageVector = if (isKeyboardExpanded) Icons.Default.Close else Icons.AutoMirrored.Filled.Send,
                        contentDescription = "كتابة",
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Main Central Microphone Button
                IconButton(
                    onClick = {
                        if (isListening) {
                            speechController.stopListening()
                        } else {
                            speechController.onSpeechRecognizedCallback = { text ->
                                orchestrator.processUserInput(text)
                            }
                            speechController.startListening()
                        }
                    },
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(if (isListening) ErrorRed else CyanPrimary)
                        .testTag("mic_button")
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = "استماع صوتي",
                        tint = NavyBackground,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Immediate Stop / Cancel button (active when task is running)
                IconButton(
                    onClick = {
                        if (isTaskRunning) {
                            orchestrator.cancelCurrentTask()
                        } else if (liveSteps.isNotEmpty()) {
                            showStepsSheet = true
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (isTaskRunning) ErrorRed.copy(alpha = 0.25f) else NavySurface)
                ) {
                    Icon(
                        imageVector = if (isTaskRunning) Icons.Default.Stop else Icons.Default.Info,
                        contentDescription = if (isTaskRunning) "إلغاء المهمة" else "سجل الخطوات",
                        tint = if (isTaskRunning) ErrorRed else CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expandable Text Input Field
            AnimatedVisibility(visible = isKeyboardExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("user_text_input"),
                        placeholder = { Text("اكتب طلبك لحمادة (مثال: افتح واتساب واكتب...)", color = TextMuted, fontSize = 13.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = TextMuted,
                            focusedContainerColor = NavySurface,
                            unfocusedContainerColor = NavySurface,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        ),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    FloatingActionButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                orchestrator.processUserInput(textInput)
                                textInput = ""
                            }
                        },
                        containerColor = CyanPrimary,
                        contentColor = NavyBackground,
                        shape = CircleShape,
                        modifier = Modifier.testTag("send_text_button")
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال")
                    }
                }
            }
        }

        // Transparent "What Hamada Did" Bottom Sheet Modal
        if (showStepsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showStepsSheet = false },
                containerColor = NavySurface,
                dragHandle = { BottomSheetDefaults.DragHandle(color = CyanPrimary) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "ماذا فعل حمادة خطوة بخطوة",
                                color = TextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (currentGoal.isNotBlank()) {
                                Text(
                                    text = "الهدف: $currentGoal",
                                    color = CyanPrimary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        if (isTaskRunning) {
                            Button(
                                onClick = {
                                    orchestrator.cancelCurrentTask()
                                    showStepsSheet = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = TextWhite)
                            ) {
                                Text("إلغاء الآن", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (liveSteps.isEmpty()) {
                        Text(
                            text = "لم يتم تنفيذ أي خطوات بعد.",
                            color = TextMuted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(liveSteps) { step ->
                                StepLogCard(step = step)
                            }
                        }
                    }
                }
            }
        }

        // High Risk Action Approval Dialog Overlay
        if (agentState is AgentState.WaitingApproval) {
            val approval = agentState as AgentState.WaitingApproval
            AlertDialog(
                onDismissRequest = { approval.onCancel() },
                containerColor = NavySurface,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = WarningAmber)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("تأكيد إجراء هام", color = TextWhite, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        text = approval.prompt,
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { approval.onConfirm() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground)
                    ) {
                        Text("موافق ومتابعة", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { approval.onCancel() },
                        border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed)
                    ) {
                        Text("إلغاء", color = ErrorRed)
                    }
                }
            )
        }
    }
}

@Composable
fun StepLogCard(step: AgentStepLog) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NavyBackground.copy(alpha = 0.7f)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (step.success) CyanPrimary.copy(alpha = 0.25f) else ErrorRed.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الخطوة #${step.stepIndex}: ${step.toolName}",
                    color = CyanPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    color = if (step.success) SuccessGreen.copy(alpha = 0.2f) else ErrorRed.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (step.success) "تم بنجاح" else "فشل/تنبيه",
                        color = if (step.success) SuccessGreen else ErrorRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (step.thought.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "🧠 تفكير حمادة: ${step.thought}",
                    color = TextWhite,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "📌 النتيجة: ${step.result}",
                color = TextMuted,
                fontSize = 11.sp
            )
        }
    }
}
