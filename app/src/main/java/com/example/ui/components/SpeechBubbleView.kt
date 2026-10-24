package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.NavyCard
import com.example.ui.theme.NavySurface
import com.example.ui.theme.TextWhite

@Composable
fun SpeechBubbleView(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && text.isNotBlank(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .testTag("speech_bubble_view")
        ) {
            Surface(
                color = NavySurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, CyanPrimary.copy(alpha = 0.5f)),
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = text,
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
                }
            }

            // Downward pointing bubble tail
            Box(
                modifier = Modifier
                    .size(width = 20.dp, height = 12.dp)
                    .clip(
                        GenericShape { size, _ ->
                            moveTo(0f, 0f)
                            lineTo(size.width / 2f, size.height)
                            lineTo(size.width, 0f)
                            close()
                        }
                    )
                    .background(CyanPrimary.copy(alpha = 0.5f))
            )
        }
    }
}
