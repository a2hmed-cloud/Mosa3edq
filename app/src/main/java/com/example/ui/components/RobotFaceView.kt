package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.AgentState
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun RobotFaceView(
    agentState: AgentState,
    isSpeaking: Boolean,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    // Blinking State Animation
    var isBlinking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(3000, 6000))
            isBlinking = true
            delay(150)
            isBlinking = false

            // Occasional double blink
            if (Random.nextFloat() < 0.35f) {
                delay(120)
                isBlinking = true
                delay(150)
                isBlinking = false
            }
        }
    }

    val blinkScaleY by animateFloatAsState(
        targetValue = if (isBlinking) 0.08f else 1.0f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "blinkScale"
    )

    // Continuous Infinite Animations
    val infiniteTransition = rememberInfiniteTransition(label = "robot_face_animations")

    val idleBreathScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleBreath"
    )

    val eyeShiftX by infiniteTransition.animateFloat(
        initialValue = -18f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eyeShiftX"
    )

    val mouthMouthWave by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 180, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mouthWave"
    )

    val listeningPulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listeningPulse"
    )

    // Determine state-dependent primary color
    val eyeColor = when (agentState) {
        is AgentState.Listening -> CyanPrimary
        is AgentState.Thinking, is AgentState.Planning -> BlueAccent
        is AgentState.Executing, is AgentState.Observing -> CyanSecondary
        is AgentState.Verifying -> CyanPrimary
        is AgentState.WaitingApproval -> WarningAmber
        is AgentState.Success -> SuccessGreen
        is AgentState.Confused -> WarningAmber
        is AgentState.Failed, is AgentState.Error -> ErrorRed
        else -> CyanPrimary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("robot_face_container"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Robot Eyes Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val centerX = canvasWidth / 2f
            val centerY = canvasHeight / 2f

            val eyeWidth = 100.dp.toPx()
            val eyeBaseHeight = 110.dp.toPx() * blinkScaleY * (if (agentState is AgentState.Idle) idleBreathScale else 1f)
            val eyeGap = 42.dp.toPx()

            val leftEyeCenterX = centerX - (eyeWidth / 2f) - (eyeGap / 2f)
            val rightEyeCenterX = centerX + (eyeWidth / 2f) + (eyeGap / 2f)

            // Calculate eye position shifts depending on state
            val currentShiftX = when (agentState) {
                is AgentState.Thinking -> eyeShiftX
                is AgentState.Executing -> eyeShiftX * 0.7f
                else -> 0f
            }

            val currentShiftY = when (agentState) {
                is AgentState.Thinking -> -16f
                is AgentState.Confused -> 12f
                else -> 0f
            }

            // Draw Left and Right Robot Eyes (Solid filled organic geometric shape)
            val eyeCenters = listOf(leftEyeCenterX + currentShiftX, rightEyeCenterX + currentShiftX)
            for (i in eyeCenters.indices) {
                val eyeX = eyeCenters[i]
                val eyeRect = Rect(
                    left = eyeX - eyeWidth / 2f,
                    top = centerY - eyeBaseHeight / 2f + currentShiftY,
                    right = eyeX + eyeWidth / 2f,
                    bottom = centerY + eyeBaseHeight / 2f + currentShiftY
                )

                // Confused or Puzzled slanted top edges
                val eyePath = Path().apply {
                    if (agentState is AgentState.Confused) {
                        val isLeft = (i == 0)
                        val topLeftY = if (isLeft) eyeRect.top + 28f else eyeRect.top
                        val topRightY = if (isLeft) eyeRect.top else eyeRect.top + 28f

                        moveTo(eyeRect.left, topLeftY)
                        lineTo(eyeRect.right, topRightY)
                        lineTo(eyeRect.right, eyeRect.bottom)
                        lineTo(eyeRect.left, eyeRect.bottom)
                        close()
                    } else if (agentState is AgentState.Success) {
                        addRoundRect(
                            RoundRect(
                                rect = eyeRect,
                                topLeft = CornerRadius(eyeWidth / 2f, eyeWidth / 2f),
                                topRight = CornerRadius(eyeWidth / 2f, eyeWidth / 2f),
                                bottomLeft = CornerRadius(16f, 16f),
                                bottomRight = CornerRadius(16f, 16f)
                            )
                        )
                    } else {
                        addRoundRect(
                            RoundRect(
                                rect = eyeRect,
                                cornerRadius = CornerRadius(32.dp.toPx(), 32.dp.toPx())
                            )
                        )
                    }
                }

                // Outer ambient glow
                drawPath(
                    path = eyePath,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            eyeColor.copy(alpha = if (isListening) 0.6f * listeningPulseGlow else 0.35f),
                            Color.Transparent
                        ),
                        center = Offset(eyeX, centerY + currentShiftY),
                        radius = eyeWidth * 1.3f
                    )
                )

                // Solid Filled Robot Eye Body
                drawPath(
                    path = eyePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            eyeColor,
                            eyeColor.copy(alpha = 0.85f)
                        ),
                        startY = eyeRect.top,
                        endY = eyeRect.bottom
                    )
                )

                // Inner specular highlight reflection
                if (!isBlinking) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.55f),
                        radius = 10.dp.toPx(),
                        center = Offset(eyeX - eyeWidth * 0.22f, centerY - eyeBaseHeight * 0.22f + currentShiftY)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Digital Mouth Canvas
        Canvas(
            modifier = Modifier
                .width(160.dp)
                .height(48.dp)
        ) {
            val w = size.width
            val h = size.height
            val centerMouthX = w / 2f
            val centerMouthY = h / 2f

            val mouthPath = Path()

            when {
                isSpeaking -> {
                    // Dynamic animated mouth opening during actual speaking
                    val mouthOpenHeight = 22.dp.toPx() * mouthMouthWave
                    val mouthWidth = 90.dp.toPx()

                    val mouthRect = Rect(
                        left = centerMouthX - mouthWidth / 2f,
                        top = centerMouthY - mouthOpenHeight / 2f,
                        right = centerMouthX + mouthWidth / 2f,
                        bottom = centerMouthY + mouthOpenHeight / 2f
                    )

                    mouthPath.addRoundRect(
                        RoundRect(
                            rect = mouthRect,
                            cornerRadius = CornerRadius(mouthOpenHeight / 2f, mouthOpenHeight / 2f)
                        )
                    )

                    drawPath(
                        path = mouthPath,
                        color = eyeColor
                    )
                }

                agentState is AgentState.Confused -> {
                    // Downward disgruntled/puzzled line
                    mouthPath.moveTo(centerMouthX - 45.dp.toPx(), centerMouthY + 10.dp.toPx())
                    mouthPath.quadraticTo(
                        centerMouthX, centerMouthY - 8.dp.toPx(),
                        centerMouthX + 45.dp.toPx(), centerMouthY + 10.dp.toPx()
                    )

                    drawPath(
                        path = mouthPath,
                        color = eyeColor,
                        style = Stroke(width = 6.dp.toPx())
                    )
                }

                agentState is AgentState.Success -> {
                    // Happy upward curved smile line
                    mouthPath.moveTo(centerMouthX - 50.dp.toPx(), centerMouthY - 6.dp.toPx())
                    mouthPath.quadraticTo(
                        centerMouthX, centerMouthY + 18.dp.toPx(),
                        centerMouthX + 50.dp.toPx(), centerMouthY - 6.dp.toPx()
                    )

                    drawPath(
                        path = mouthPath,
                        color = eyeColor,
                        style = Stroke(width = 6.dp.toPx())
                    )
                }

                agentState is AgentState.Thinking -> {
                    // Small focused pill mouth
                    drawCircle(
                        color = eyeColor,
                        radius = 8.dp.toPx(),
                        center = Offset(centerMouthX, centerMouthY)
                    )
                }

                else -> {
                    // Neutral gentle line
                    mouthPath.moveTo(centerMouthX - 40.dp.toPx(), centerMouthY)
                    mouthPath.quadraticTo(
                        centerMouthX, centerMouthY + 4.dp.toPx(),
                        centerMouthX + 40.dp.toPx(), centerMouthY
                    )

                    drawPath(
                        path = mouthPath,
                        color = eyeColor.copy(alpha = 0.8f),
                        style = Stroke(width = 5.dp.toPx())
                    )
                }
            }
        }
    }
}
