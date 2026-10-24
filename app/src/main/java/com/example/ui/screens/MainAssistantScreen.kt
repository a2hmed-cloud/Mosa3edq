package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AgentOrchestrator
import com.example.agent.AgentState
import com.example.speech.SpeechController
import com.example.ui.components.EyeView
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
    val liveText by speechController.liveTranscription.collectAsState()
    val lastSummary by orchestrator.lastSummary.collectAsState()

    var textInput by remember { mutableStateOf("") }

    val quickActions = listOf(
        "شغل أغنية عمرو دياب في سبوتيفاي" to "🎵",
        "الطقس في القاهرة النهاردة" to "☀️",
        "اضبط منبه الساعة 8 الصبح" to "⏰",
        "اضبط مؤقت 60 ثانية" to "⏱️",
        "احفظ مهمة شراء مستلزمات المنزل" to "📝",
        "البحث عن أقرب صيدلية" to "🗺️",
        "اقرأ عناصر الشاشة الحالية" to "👁️"
    )

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "يا مساعد (YaMosaed)",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavyBackground),
                actions = {
                    TextButton(onClick = onNavigateToPermissions) {
                        Text("الأذونات", color = CyanPrimary, fontSize = 13.sp)
                    }
                    TextButton(onClick = onNavigateToTasks) {
                        Text("المهام", color = CyanPrimary, fontSize = 13.sp)
                    }
                    TextButton(onClick = onNavigateToLogs) {
                        Text("السجل", color = CyanPrimary, fontSize = 13.sp)
                    }
                    TextButton(onClick = onNavigateToSettings) {
                        Text("الإعدادات", color = CyanPrimary, fontSize = 13.sp)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Top Status Badge
            Surface(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(20.dp)),
                color = NavyCard
            ) {
                val stateText = when (agentState) {
                    is AgentState.Idle -> "جاهز للاستماع والخدمة"
                    is AgentState.Listening -> "جاري الاستماع الآن..."
                    is AgentState.Thinking -> "جاري التفكير والفهم..."
                    is AgentState.Planning -> "جاري تخطيط الإجراءات..."
                    is AgentState.Executing -> "جاري تنفيذ الإجراء على أندرويد..."
                    is AgentState.Speaking -> "يتحدث..."
                    is AgentState.Completed -> "تمت العملية بنجاح"
                    is AgentState.Failed -> "حدث خطأ في التنفيذ"
                    else -> "يا مساعد بالخدمة"
                }

                Text(
                    text = stateText,
                    color = CyanPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Central Animated Eye
            EyeView(
                agentState = agentState,
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .clickable {
                        if (isListening) {
                            speechController.stopListening()
                        } else {
                            speechController.startListening()
                        }
                    }
            )

            // Transcription or Response Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = NavySurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val displayContent = when {
                        liveText.isNotBlank() -> liveText
                        lastSummary.isNotBlank() -> lastSummary
                        else -> "أهلاً بك! اضغط على الميكروفون أو اكتب طلبك لتشغيل الأغاني، ضبط المنبه، الطقس، أو التحكم بالهاتف."
                    }

                    Text(
                        text = displayContent,
                        color = TextWhite,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }

            // Quick Actions Scrollable List
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickActions) { (actionText, icon) ->
                    SuggestionChip(
                        onClick = {
                            orchestrator.processUserInput(actionText)
                        },
                        label = { Text("$icon $actionText", color = TextWhite, fontSize = 12.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = NavyCard),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = CyanPrimary.copy(alpha = 0.4f)
                        )
                    )
                }
            }

            // Microphone FAB and Text Input Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                        .size(72.dp)
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

                Spacer(modifier = Modifier.height(12.dp))

                // Text Field Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("user_text_input"),
                        placeholder = { Text("اكتب طلبك هنا...", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = TextMuted.copy(alpha = 0.5f),
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
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "إرسال"
                        )
                    }
                }
            }

        }
    }
}
