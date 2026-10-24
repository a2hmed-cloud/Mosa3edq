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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AgentOrchestrator
import com.example.agent.AgentState
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

    var textInput by remember { mutableStateOf("") }
    var isKeyboardExpanded by remember { mutableStateOf(false) }

    val quickActions = listOf(
        "شغل أغنية عمرو دياب في سبوتيفاي" to "🎵",
        "الطقس في القاهرة النهاردة" to "☀️",
        "اضبط منبه الساعة 8 الصبح" to "⏰",
        "اضبط مؤقت 60 ثانية" to "⏱️",
        "احفظ مهمة شراء مستلزمات" to "📝",
        "البحث عن أقرب صيدلية" to "🗺️",
        "اقرأ الشاشة الحالية" to "👁️"
    )

    // Compute dynamic bubble text
    val bubbleText = when {
        liveText.isNotBlank() -> liveText
        agentState is AgentState.Thinking -> "ثانية واحدة، بفكّر..."
        agentState is AgentState.Planning -> "جاري تخطيط خطوات العملية..."
        agentState is AgentState.Executing -> "بنفّذ الخطوة دي..."
        agentState is AgentState.Observing -> "بشوف محتوى الشاشة..."
        agentState is AgentState.Verifying -> "بتأكد إن العملية تمت بنجاح..."
        agentState is AgentState.Speaking -> (agentState as AgentState.Speaking).message
        agentState is AgentState.Success -> (agentState as AgentState.Success).summary
        agentState is AgentState.Annoyed -> "توقف الإجراء أو تعذر الوصول للنصر..."
        agentState is AgentState.Failed -> (agentState as AgentState.Failed).error
        lastSummary.isNotBlank() -> lastSummary
        isListening -> "سامعك كويس، اتكلم..."
        else -> "أهلاً بيك! أنا حمادة، اضغط عليا أو اتكلم علشان أساعدك."
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
        // Minimal Floating Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = NavySurface.copy(alpha = 0.8f),
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
                                if (isListening) ErrorRed else if (isSpeaking) SuccessGreen else CyanPrimary
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hamada AI",
                        color = TextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Minimal Action Icon Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onNavigateToPermissions,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NavySurface.copy(alpha = 0.8f))
                ) {
                    Icon(Icons.Default.Security, contentDescription = "الأذونات", tint = CyanPrimary, modifier = Modifier.size(20.dp))
                }

                IconButton(
                    onClick = onNavigateToTasks,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NavySurface.copy(alpha = 0.8f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "المهام", tint = CyanPrimary, modifier = Modifier.size(20.dp))
                }

                IconButton(
                    onClick = onNavigateToLogs,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NavySurface.copy(alpha = 0.8f))
                ) {
                    Icon(Icons.Default.History, contentDescription = "السجل", tint = CyanPrimary, modifier = Modifier.size(20.dp))
                }

                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NavySurface.copy(alpha = 0.8f))
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "الإعدادات", tint = CyanPrimary, modifier = Modifier.size(20.dp))
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
            // Speech Bubble above the Robot Face
            SpeechBubbleView(
                text = bubbleText,
                visible = true,
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .fillMaxWidth(0.9f)
            )

            // Full Living Robot Face (Eyes + Mouth)
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
        }

        // Bottom Controls Overlay (Mic FAB, Quick Chips, Text Input Bar)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Collapsible Quick Prompts
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickActions) { (actionText, icon) ->
                    SuggestionChip(
                        onClick = { orchestrator.processUserInput(actionText) },
                        label = { Text("$icon $actionText", color = TextWhite, fontSize = 12.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = NavySurface.copy(alpha = 0.85f)),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = CyanPrimary.copy(alpha = 0.35f)
                        )
                    )
                }
            }

            // Central Voice Interaction Button & Keyboard Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(
                    onClick = { isKeyboardExpanded = !isKeyboardExpanded },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(NavySurface)
                ) {
                    Icon(
                        imageVector = if (isKeyboardExpanded) Icons.Default.Close else Icons.AutoMirrored.Filled.Send,
                        contentDescription = "كتابة",
                        tint = CyanPrimary
                    )
                }

                // Main Microphone Glowing Button
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
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(if (isListening) ErrorRed else CyanPrimary)
                        .testTag("mic_button")
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = "استماع صوتي",
                        tint = NavyBackground,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            // Text Input Field Drawer when Expanded
            AnimatedVisibility(visible = isKeyboardExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("user_text_input"),
                        placeholder = { Text("اكتب رسالتك لحمادة...", color = TextMuted) },
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
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "إرسال"
                        )
                    }
                }
            }
        }

        // High Risk Action Approval Dialog Overlay
        if (agentState is AgentState.WaitingApproval) {
            val approvalState = agentState as AgentState.WaitingApproval
            AlertDialog(
                onDismissRequest = {},
                containerColor = NavySurface,
                title = { Text("تأكيد إجراء هام", color = TextWhite, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "حمادة يطلب موافقتك الصريحة لتنفيذ الإجراء التالي:\n${approvalState.action.javaClass.simpleName}",
                        color = TextMuted
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { approvalState.onConfirm() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("موافق وتنفيذ")
                        }
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { /* cancel action */ },
                        border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed)
                    ) {
                        Text("إلغاء", color = ErrorRed)
                    }
                }
            )
        }
    }
}
