package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.AgentState
import com.example.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun EyeView(
    agentState: AgentState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "eye_animations")

    // Rotation angle for outer rings
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Pulse scale for listening/thinking
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Pupil offset tracking
    val pupilOffsetX by infiniteTransition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pupil_x"
    )

    // Dynamic color depending on agent state
    val mainColor = when (agentState) {
        is AgentState.Listening -> CyanPrimary
        is AgentState.Thinking, is AgentState.Planning -> BlueAccent
        is AgentState.Executing -> CyanSecondary
        is AgentState.Success -> SuccessGreen
        is AgentState.Confused -> WarningAmber
        is AgentState.Failed, is AgentState.Error -> ErrorRed
        else -> CyanPrimary
    }

    Box(
        modifier = modifier
            .size(240.dp)
            .testTag("eye_view_container"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val centerOffset = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f * if (agentState is AgentState.Listening) pulseScale else 1f

            // Outer Orbit 1
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(mainColor.copy(alpha = 0.25f), Color.Transparent),
                    center = centerOffset,
                    radius = radius * 1.2f
                ),
                center = centerOffset,
                radius = radius * 0.95f
            )

            drawArc(
                color = mainColor.copy(alpha = 0.6f),
                startAngle = rotationAngle,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(centerOffset.x - radius * 0.85f, centerOffset.y - radius * 0.85f),
                size = Size(radius * 1.7f, radius * 1.7f),
                style = Stroke(width = 4f)
            )

            drawArc(
                color = mainColor.copy(alpha = 0.4f),
                startAngle = rotationAngle + 180f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(centerOffset.x - radius * 0.75f, centerOffset.y - radius * 0.75f),
                size = Size(radius * 1.5f, radius * 1.5f),
                style = Stroke(width = 3f)
            )

            // Sclera / Eye Shape Outline
            val eyePath = Path().apply {
                moveTo(centerOffset.x - radius * 0.8f, centerOffset.y)
                quadraticTo(
                    centerOffset.x, centerOffset.y - radius * 0.55f,
                    centerOffset.x + radius * 0.8f, centerOffset.y
                )
                quadraticTo(
                    centerOffset.x, centerOffset.y + radius * 0.55f,
                    centerOffset.x - radius * 0.8f, centerOffset.y
                )
                close()
            }

            drawPath(
                path = eyePath,
                color = mainColor.copy(alpha = 0.2f)
            )
            drawPath(
                path = eyePath,
                color = mainColor,
                style = Stroke(width = 4f)
            )

            // Iris Circle
            val pupilOffset = if (agentState is AgentState.Executing) {
                Offset(centerOffset.x + pupilOffsetX, centerOffset.y)
            } else {
                centerOffset
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(mainColor, mainColor.copy(alpha = 0.4f), Color.Transparent),
                    center = pupilOffset,
                    radius = radius * 0.35f
                ),
                center = pupilOffset,
                radius = radius * 0.3f
            )

            // Pupil Center Core
            drawCircle(
                color = Color.White,
                center = pupilOffset,
                radius = radius * 0.1f
            )

            // Dynamic Particle Orbits
            if (agentState is AgentState.Listening || agentState is AgentState.Thinking) {
                for (i in 0..5) {
                    val angle = (rotationAngle + i * 60) * (Math.PI / 180f)
                    val particleX = centerOffset.x + (radius * 0.7f) * cos(angle).toFloat()
                    val particleY = centerOffset.y + (radius * 0.7f) * sin(angle).toFloat()
                    drawCircle(
                        color = mainColor,
                        center = Offset(particleX, particleY),
                        radius = 6f
                    )
                }
            }
        }
    }
}
