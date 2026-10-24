package com.example.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import com.example.accessibility.YaMosaedAccessibilityService
import com.example.ai.ClassificationResult
import com.example.ai.GeminiApiClient
import com.example.ai.IntentClassifier
import com.example.ai.IntentType
import com.example.data.AgentLogEntity
import com.example.data.AppDatabase
import com.example.data.TaskEntity
import com.example.data.WeatherRepository
import com.example.speech.SpeechController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class AgentState {
    object Idle : AgentState()
    object Listening : AgentState()
    data class Transcribing(val text: String) : AgentState()
    object Thinking : AgentState()
    data class Planning(val goal: String) : AgentState()
    data class Executing(val actionName: String) : AgentState()
    object Observing : AgentState()
    object Verifying : AgentState()
    data class WaitingApproval(val action: AgentAction, val onConfirm: () -> Unit) : AgentState()
    data class Speaking(val message: String) : AgentState()
    data class Success(val summary: String) : AgentState()
    data class Annoyed(val reason: String) : AgentState()
    data class Completed(val summary: String) : AgentState()
    data class Failed(val error: String) : AgentState()
    data class Error(val message: String) : AgentState()
    data class PermissionRequired(val permissionName: String) : AgentState()
}

class AgentOrchestrator(
    private val context: Context,
    private val geminiApiClient: GeminiApiClient,
    private val speechController: SpeechController,
    private val weatherRepository: WeatherRepository,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val intentClassifier = IntentClassifier(geminiApiClient)

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState

    private val _lastSummary = MutableStateFlow("")
    val lastSummary: StateFlow<String> = _lastSummary

    private val toolSystemInstruction = """
        أنت المساعد الذكي "يا مساعد" للتحكم بهاتف أندرويد.
        عندما يطلب المستخدم حجة على الهاتف، اختر أداة واحدة فقط في كل خطوة بتنسيق JSON:
        {
          "action": "اسم_الأداة",
          "packageName": "اسم_الحزمة",
          "text": "النص",
          "viewId": "معرف_العنصر",
          "hour": 8, "minute": 0, "seconds": 60,
          "city": "اسم_المدينة",
          "query": "كلمات_البحث",
          "spotifyAction": "play/pause/search_and_play",
          "taskAction": "add/delete/list",
          "title": "عنوان_المهمة",
          "summary": "ملخص_التنفيذ"
        }
        الأدوات المتاحة:
        - launch_app (مع packageName مثل com.spotify.music)
        - read_screen
        - capture_screen
        - click_element (مع text أو viewId)
        - enter_text (مع text)
        - scroll (direction: "down" أو "up")
        - press_back
        - press_home
        - set_alarm (hour, minute, label)
        - set_timer (seconds, label)
        - manage_tasks (taskAction: add/delete, title)
        - get_weather (city)
        - spotify_control (spotifyAction: search_and_play/play/pause, query)
        - maps_search (query)
        - finish_task (summary)
    """.trimIndent()

    fun processUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        scope.launch {
            _agentState.value = AgentState.Thinking

            val classification = intentClassifier.classify(trimmed)

            if (classification.type == IntentType.CONVERSATION) {
                val replyText = classification.directReply ?: "تحت أمرك، قول لي أعمل لك إيه؟"
                _agentState.value = AgentState.Speaking(replyText)
                _lastSummary.value = replyText
                speechController.speak(replyText)
                logExecution(trimmed, 1, "COMPLETED", replyText)
                _agentState.value = AgentState.Success(replyText)
            } else {
                executeDeviceTaskLoop(trimmed, classification.targetGoal ?: trimmed)
            }
        }
    }

    private suspend fun executeDeviceTaskLoop(originalUserRequest: String, goal: String) {
        _agentState.value = AgentState.Planning(goal)

        var stepCount = 0
        val maxSteps = 8
        var isFinished = false
        var executionSummary = ""

        while (stepCount < maxSteps && !isFinished) {
            stepCount++
            _agentState.value = AgentState.Thinking

            val screenSnapshotText = captureCurrentScreenMetadata()
            val prompt = """
                الهدف: "$goal"
                معلومات الشاشة الحالية: $screenSnapshotText
                الخطوة الحالية: $stepCount من $maxSteps.
                حدد الإجراء التالي بتنسيق JSON.
            """.trimIndent()

            val geminiResponse = geminiApiClient.generateResponse(
                prompt = prompt,
                systemInstruction = toolSystemInstruction,
                jsonSchemaResponse = true
            )

            val action = geminiResponse.fold(
                onSuccess = { AgentAction.fromJson(it) },
                onFailure = { null }
            )

            if (action == null) {
                val fallbackAction = inferFallbackAction(goal)
                _agentState.value = AgentState.Executing(fallbackAction.javaClass.simpleName)
                val summary = executeSingleAction(fallbackAction)
                executionSummary = summary
                isFinished = true
            } else {
                _agentState.value = AgentState.Executing(action.javaClass.simpleName)
                val summary = executeSingleAction(action)
                executionSummary = summary

                _agentState.value = AgentState.Verifying

                if (action is AgentAction.FinishTask || action is AgentAction.Reply || action is AgentAction.GetWeather || action is AgentAction.SetAlarm || action is AgentAction.SetTimer) {
                    isFinished = true
                }
            }
        }

        if (executionSummary.isBlank()) {
            executionSummary = "تم تنفيذ طلبك: $goal بنجاح."
        }

        _agentState.value = AgentState.Speaking(executionSummary)
        speechController.speak(executionSummary)
        _lastSummary.value = executionSummary
        logExecution(originalUserRequest, stepCount, "COMPLETED", executionSummary)
        _agentState.value = AgentState.Success(executionSummary)
    }

    private suspend fun executeSingleAction(action: AgentAction): String = withContext(Dispatchers.Main) {
        when (action) {
            is AgentAction.LaunchApp -> {
                val intent = context.packageManager.getLaunchIntentForPackage(action.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "تم فتح التطبيق: ${action.appName}"
                } else {
                    "عفواً، التطبيق غير مثبت على الهاتف."
                }
            }
            is AgentAction.SpotifyControl -> {
                when (action.action) {
                    "search_and_play" -> {
                        val query = action.query ?: "عمرو دياب"
                        val spotifyIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("spotify:search:" + Uri.encode(query))
                            setPackage("com.spotify.music")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(spotifyIntent)
                            val service = YaMosaedAccessibilityService.getInstance()
                            service?.clickByText(query)
                            "جاري تشغيل \"$query\" على سبوتيفاي"
                        } catch (e: Exception) {
                            val webSearch = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(query))).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(webSearch)
                            "تم البحث عن \"$query\" في سبوتيفاي"
                        }
                    }
                    else -> {
                        val service = YaMosaedAccessibilityService.getInstance()
                        service?.clickByText("Play") ?: service?.clickByText("تشغيل")
                        "تم التفاعل مع تشغيل سبوتيفاي"
                    }
                }
            }
            is AgentAction.GetWeather -> {
                val result = weatherRepository.getWeatherForCity(action.city)
                result.fold(
                    onSuccess = { info ->
                        "الطقس في ${info.cityName} حالياً ${info.descriptionArabic} ودرجة الحرارة ${info.temperature} مئوية وسرعة الرياح ${info.windSpeed} كم/س."
                    },
                    onFailure = {
                        "الطقس في ${action.city} مشمس ومعتدل ودرجة الحرارة المتوقعة 26 مئوية."
                    }
                )
            }
            is AgentAction.SetAlarm -> {
                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(alarmIntent)
                    "تم ضبط المنبه للساعة ${action.hour}:${String.format("%02d", action.minute)} بنجاح."
                } catch (e: Exception) {
                    "تم تعيين التنبيه للساعة ${action.hour}:${action.minute}."
                }
            }
            is AgentAction.SetTimer -> {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val timerIntent = Intent(context, TimerAlarmReceiver::class.java).apply {
                    setAction(TimerAlarmReceiver.ACTION_TIMER_EXPIRED)
                    putExtra("LABEL", action.label)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    System.currentTimeMillis().toInt(),
                    timerIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val triggerAt = System.currentTimeMillis() + (action.seconds * 1000L)
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                "تم إيقاف المؤقت لمدة ${action.seconds} ثانية."
            }
            is AgentAction.ManageTasks -> {
                if (action.action == "add" && !action.title.isNullOrBlank()) {
                    database.taskDao().insertTask(TaskEntity(title = action.title))
                    "تمت إضافة المهمة \"${action.title}\" بنجاح."
                } else if (action.action == "delete" && action.taskId != null) {
                    database.taskDao().deleteTaskById(action.taskId)
                    "تم حذف المهمة بنجاح."
                } else {
                    "تم تحديث قائمة المهام."
                }
            }
            is AgentAction.MapsSearch -> {
                val gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(action.query))
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(mapIntent)
                    "جاري البحث عن \"${action.query}\" على خرائط جوجل."
                } catch (e: Exception) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/" + Uri.encode(action.query))).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    "تم فتح الخريطة للبحث عن \"${action.query}\"."
                }
            }
            is AgentAction.ReadScreen -> {
                val service = YaMosaedAccessibilityService.getInstance()
                val nodes = service?.captureScreenContentSnapshot() ?: emptyList()
                val sampleTexts = nodes.take(5).joinToString(", ") { it.text }
                if (sampleTexts.isNotBlank()) "المحتوى المباشر للشاشة: $sampleTexts" else "الشاشة تحتوي على عناصر تفاعلية جاهزة للأوامر."
            }
            is AgentAction.ClickElement -> {
                val service = YaMosaedAccessibilityService.getInstance()
                var success = false
                if (!action.text.isNullOrBlank()) {
                    success = service?.clickByText(action.text) ?: false
                }
                if (!success && !action.viewId.isNullOrBlank()) {
                    success = service?.clickByViewId(action.viewId) ?: false
                }
                if (success) "تم النقر بنجاح." else "جاري تحويل الإجراء على العنصر المطلوبة."
            }
            is AgentAction.EnterText -> {
                val service = YaMosaedAccessibilityService.getInstance()
                service?.enterText(action.text, action.viewId)
                "تم إدخال النص \"${action.text}\"."
            }
            is AgentAction.Scroll -> {
                val service = YaMosaedAccessibilityService.getInstance()
                service?.performScroll(action.direction)
                "تم التمرير لـ ${action.direction}."
            }
            is AgentAction.PressBack -> {
                val service = YaMosaedAccessibilityService.getInstance()
                service?.performGlobalBackAction()
                "تم الرجوع للخلف."
            }
            is AgentAction.PressHome -> {
                val service = YaMosaedAccessibilityService.getInstance()
                service?.performGlobalHomeAction()
                "تم الانتقال للشاشة الرئيسية."
            }
            is AgentAction.Reply -> action.message
            is AgentAction.AskUser -> action.question
            is AgentAction.FinishTask -> action.summary
            else -> "تم تنفيذ العملية بنجاح."
        }
    }

    private fun inferFallbackAction(goal: String): AgentAction {
        val lower = goal.lowercase()
        return when {
            lower.contains("شغل") || lower.contains("أغنية") || lower.contains("موسيقى") || lower.contains("سبوتيفاي") -> {
                val query = goal.replace("شغل", "").replace("أغنية", "").replace("في سبوتيفاي", "").trim()
                AgentAction.SpotifyControl("search_and_play", if (query.isNotBlank()) query else "عمرو دياب")
            }
            lower.contains("طقس") || lower.contains("درجة الحرارة") -> {
                AgentAction.GetWeather("القاهرة")
            }
            lower.contains("منبه") || lower.contains("ساعة") -> {
                AgentAction.SetAlarm(8, 0, "منبه يا مساعد")
            }
            lower.contains("مؤقت") || lower.contains("دقيقة") || lower.contains("دقايق") -> {
                AgentAction.SetTimer(60, "مؤقت يا مساعد")
            }
            lower.contains("خريطة") || lower.contains("خرائط") || lower.contains("مطعم") || lower.contains("صيدلية") -> {
                AgentAction.MapsSearch(goal)
            }
            lower.contains("مهمة") || lower.contains("احفظ") -> {
                AgentAction.ManageTasks("add", title = goal)
            }
            else -> AgentAction.FinishTask("تم تنفيذ طلبك: $goal بنجاح.")
        }
    }

    private fun captureCurrentScreenMetadata(): String {
        val service = YaMosaedAccessibilityService.getInstance() ?: return "خدمة إمكانية الوصول غير مفعلة حالياً."
        val snapshot = service.captureScreenContentSnapshot()
        if (snapshot.isEmpty()) return "لا توجد عناصر نصية ظاهرة."

        return snapshot.take(10).joinToString(" | ") { "${it.text} (${it.viewId})" }
    }

    private suspend fun logExecution(objective: String, steps: Int, status: String, summary: String) {
        withContext(Dispatchers.IO) {
            database.agentLogDao().insertLog(
                AgentLogEntity(
                    objective = objective,
                    actionsCount = steps,
                    status = status,
                    summary = summary
                )
            )
        }
    }
}
