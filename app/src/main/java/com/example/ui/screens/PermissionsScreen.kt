package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.accessibility.YaMosaedAccessibilityService
import com.example.accessibility.YaMosaedNotificationListener
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val isAccessibilityConnected by YaMosaedAccessibilityService.isConnected.collectAsState()
    val isNotifListenerConnected by YaMosaedNotificationListener.isNotificationListenerConnected.collectAsState()

    var isMicGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = { Text("تشخيص الأذونات والخدمات", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = CyanPrimary)
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

            Text(
                "لكي يتمكن \"يا مساعد\" من قراءة الشاشة والتفاعل مع سبوتيفاي والتطبيقات والأوامر الصوتية، يجب التأكد من تفعيل الصلاحيات التالية من إعدادات الهاتف:",
                color = TextMuted,
                fontSize = 14.sp
            )

            // Permission Card 1: Accessibility Service
            PermissionCard(
                title = "خدمة إمكانية الوصول (Accessibility)",
                description = "تتيح للمساعد قراءة عناصر الشاشة والنقر التلقائي على نتائج سبوتيفاي والتنقل داخل التطبيقات.",
                isEnabled = isAccessibilityConnected,
                onEnableClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )

            // Permission Card 2: Notification Listener
            PermissionCard(
                title = "الوصول للإشعارات (Notification Listener)",
                description = "تتيح للمساعد التعرف على المشغل الصوتي الحالي وأغاني سبوتيفاي والتحكم بالتشغيل من الإشعارات.",
                isEnabled = isNotifListenerConnected,
                onEnableClick = {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )

            // Permission Card 3: Microphone
            PermissionCard(
                title = "إذن الميكروفون (Microphone)",
                description = "مطلوب للاستماع للأوامر الصوتية والتعرف على كلامك باللغة العربية المصرية.",
                isEnabled = isMicGranted,
                onEnableClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )

        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isEnabled: Boolean,
    onEnableClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NavySurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)

                Icon(
                    imageVector = if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isEnabled) SuccessGreen else WarningAmber
                )
            }

            Text(description, color = TextMuted, fontSize = 13.sp)

            Text(
                text = if (isEnabled) "الحالة: مفعلة وتعمل ✅" else "الحالة: متوقفة أو غير مسموحة ⚠️",
                color = if (isEnabled) SuccessGreen else WarningAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            if (!isEnabled) {
                Button(
                    onClick = onEnableClick,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = NavyBackground),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("فتح إعدادات الهاتف للتفعيل")
                }
            }
        }
    }
}
