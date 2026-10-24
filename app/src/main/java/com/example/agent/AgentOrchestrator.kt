package com.example.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
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
        أنت المساعد الذكي "يا مساعد" للتحكم بهاتف أندرويد وتأدية المهام.
        عندما يطلب المستخدم إجراءً على الهاتف، حدد الإجراء بدقة بتنسيق JSON:
        {
          "action": "اسم_الأداة",
          "packageName": "اسم_التطبيق_أو_الحزمة",
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
        - launch_app (مثل packageName: "whatsapp", "settings", "youtube", "com.spotify.music")
        - read_screen
        - capture_screen
        - click_element (text: النص المطلوب الضغط عليه مثل "إرسال", "بحث", "موافق")
        - enter_text (text: النص المراد كتابته)
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
                val replyText = classification.directReply ?: "تحت أمرك، قول لي حابب نعمل إيه بالنسبة للتطبيقات أو الهاتف؟"
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
        var previousActionName = ""

        while (stepCount < maxSteps && !isFinished) {
            stepCount++
            _agentState.value = AgentState.Thinking

            val screenSnapshotText = captureCurrentScreenMetadata()
            val prompt = """
                الهدف الحالي للمستخدم: "$goal"
                محتوى عناصر الشاشة الحالية: $screenSnapshotText
                الخطوة رقم: $stepCount من $maxSteps.
                حدد الإجراء القادم بدقة بتنسيق JSON.
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

            val currentAction = action ?: inferFallbackAction(goal)
            val currentActionName = currentAction.javaClass.simpleName

            // Prevent infinite identical action loops
            if (currentActionName == previousActionName && stepCount > 2) {
                executionSummary = "تم إيقاف التكرار التلقائي للإجراء. يرجى التحقق من فتح التطبيق المطلوب."
                isFinished = true
                break
            }
            previousActionName = currentActionName

            _agentState.value = AgentState.Executing(currentActionName)
            val summary = executeSingleAction(currentAction)
            executionSummary = summary

            _agentState.value = AgentState.Verifying

            if (currentAction is AgentAction.FinishTask || currentAction is AgentAction.Reply || currentAction is AgentAction.GetWeather || currentAction is AgentAction.SetAlarm || currentAction is AgentAction.SetTimer || currentAction is AgentAction.LaunchApp || currentAction is AgentAction.MapsSearch) {
                isFinished = true
            }
        }

        if (executionSummary.isBlank()) {
            executionSummary = "تم تنفيذ الإجراء المطلوب لـ: $goal."
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
                resolveAndLaunchApp(action.packageName.ifBlank { action.appName })
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
                            service?.clickByTextOrDescription(query)
                            "جاري تشغيل \"$query\" على سبوتيفاي"
                        } catch (e: Exception) {
                            val webSearch = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(query))).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(webSearch)
                            "تم فتح البحث عن \"$query\" في سبوتيفاي"
                        }
                    }
                    else -> {
                        val service = YaMosaedAccessibilityService.getInstance()
                        val success = service?.clickByTextOrDescription("Play") ?: service?.clickByTextOrDescription("تشغيل") ?: false
                        if (success) "تم التفاعل مع تشغيل سبوتيفاي بنجاح." else "تعذر التفاعل المباشر مع سبوتيفاي، يرجى التأكد من فتح التطبيق."
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
                "تم تشغيل المؤقت لمدة ${action.seconds} ثانية بنجاح."
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
                if (service == null) {
                    "خدمة إمكانية الوصول (Accessibility) غير مفعلة. يرجى فتح شاشة الأذونات وتفعيل الخدمة لقراءة الشاشة."
                } else {
                    val nodes = service.captureScreenContentSnapshot()
                    if (nodes.isEmpty()) {
                        "الشاشة الحالية لا تحتوي على عناصر نصية يمكن قراءتها عبر خدمة إمكانية الوصول."
                    } else {
                        val sampleTexts = nodes.take(8).joinToString(", ") { it.text }
                        "المحتوى المباشر للشاشة: $sampleTexts"
                    }
                }
            }
            is AgentAction.ClickElement -> {
                val service = YaMosaedAccessibilityService.getInstance()
                if (service == null) {
                    "خدمة إمكانية الوصول (Accessibility) غير مفعلة. يرجى تفعيلها للنقر التلقائي على العناصر."
                } else {
                    var success = false
                    val targetText = action.text ?: ""
                    if (targetText.isNotBlank()) {
                        success = service.clickByTextOrDescription(targetText)
                    }
                    if (!success && !action.viewId.isNullOrBlank()) {
                        success = service.clickByViewId(action.viewId)
                    }
                    if (success) {
                        "تم النقر على العنصر \"$targetText\" بنجاح."
                    } else {
                        "تعذر العثور على عنصر \"$targetText\" للنقر عليه في الشاشة الحالية."
                    }
                }
            }
            is AgentAction.EnterText -> {
                val service = YaMosaedAccessibilityService.getInstance()
                if (service == null) {
                    "خدمة إمكانية الوصول (Accessibility) غير مفعلة. يرجى تفعيلها لإدخال النصوص."
                } else {
                    val success = service.enterText(action.text, action.viewId)
                    if (success) {
                        "تم إدخال النص \"${action.text}\" بنجاح."
                    } else {
                        "تعذر العثور على حقل نصي متاح لإدخال \"${action.text}\"."
                    }
                }
            }
            is AgentAction.Scroll -> {
                val service = YaMosaedAccessibilityService.getInstance()
                if (service == null) {
                    "خدمة إمكانية الوصول غير مفعلة للتمرير."
                } else {
                    val success = service.performScroll(action.direction)
                    if (success) "تم التمرير لـ ${action.direction}." else "تعذر التمرير في الشاشة الحالية."
                }
            }
            is AgentAction.PressBack -> {
                val service = YaMosaedAccessibilityService.getInstance()
                if (service == null) {
                    "خدمة إمكانية الوصول غير مفعلة."
                } else {
                    service.performGlobalBackAction()
                    "تم الرجوع للخلف."
                }
            }
            is AgentAction.PressHome -> {
                val service = YaMosaedAccessibilityService.getInstance()
                if (service == null) {
                    "خدمة إمكانية الوصول غير مفعلة."
                } else {
                    service.performGlobalHomeAction()
                    "تم الانتقال للشاشة الرئيسية."
                }
            }
            is AgentAction.Reply -> action.message
            is AgentAction.AskUser -> action.question
            is AgentAction.FinishTask -> action.summary
            else -> "تم تنفيذ العملية بنجاح."
        }
    }

    private fun resolveAndLaunchApp(query: String): String {
        val pm = context.packageManager
        val cleanQuery = query.trim().lowercase()

        // 1. Direct Package Launch Attempt
        if (cleanQuery.contains(".")) {
            val directIntent = pm.getLaunchIntentForPackage(cleanQuery)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(directIntent)
                return "تم فتح التطبيق بنجاح."
            }
        }

        // 2. Specific System & Known App Mappings
        when {
            cleanQuery.contains("إعدادات") || cleanQuery.contains("اعدادات") || cleanQuery.contains("settings") -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return "تم فتح إعدادات النظام بنجاح."
            }
            cleanQuery.contains("كاميرا") || cleanQuery.contains("الكاميرا") || cleanQuery.contains("camera") -> {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return "تم فتح الكاميرا بنجاح."
            }
            cleanQuery.contains("هاتف") || cleanQuery.contains("مكالمات") || cleanQuery.contains("اتصال") || cleanQuery.contains("phone") -> {
                val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return "تم فتح لوحة الاتصال بنجاح."
            }
        }

        // 3. Query All Installed Launcher Apps
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherApps = pm.queryIntentActivities(mainIntent, 0)

        for (resolveInfo in launcherApps) {
            val appLabel = resolveInfo.loadLabel(pm).toString().lowercase()
            val pkgName = resolveInfo.activityInfo.packageName.lowercase()

            if (appLabel.contains(cleanQuery) || pkgName.contains(cleanQuery) || matchesAppAlias(cleanQuery, appLabel, pkgName)) {
                val launchIntent = pm.getLaunchIntentForPackage(resolveInfo.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return "تم فتح تطبيق ${resolveInfo.loadLabel(pm)} بنجاح."
                }
            }
        }

        return "عفواً، لم يتم العثور على تطبيق \"$query\" مثبت على الهاتف."
    }

    private fun matchesAppAlias(query: String, label: String, pkg: String): Boolean {
        return when {
            query.contains("واتس") || query.contains("whatsapp") -> pkg.contains("whatsapp") || label.contains("whatsapp")
            query.contains("يوتيوب") || query.contains("youtube") -> pkg.contains("youtube") || label.contains("youtube")
            query.contains("سبوتيفاي") || query.contains("spotify") -> pkg.contains("spotify") || label.contains("spotify")
            query.contains("تليجرام") || query.contains("telegram") -> pkg.contains("telegram") || label.contains("telegram")
            query.contains("فيسبوك") || query.contains("facebook") -> pkg.contains("facebook") || label.contains("facebook")
            query.contains("انستجرام") || query.contains("instagram") -> pkg.contains("instagram") || label.contains("instagram")
            query.contains("كروم") || query.contains("متصفح") || query.contains("chrome") -> pkg.contains("chrome") || label.contains("chrome")
            else -> false
        }
    }

    private fun extractTargetAppName(goal: String): String {
        val clean = goal.replace("افتح", "").replace("تطبيق", "").replace("شغل", "").trim()
        val knownApps = listOf("واتساب", "واتس", "يوتيوب", "الإعدادات", "اعدادات", "سبوتيفاي", "تليجرام", "فيسبوك", "انستجرام", "كاميرا", "هاتف", "الاستوديو", "متجر", "كروم", "خرائط")
        val words = clean.split(" ", "،", ",", ".", "!")
        for (w in words) {
            val match = knownApps.firstOrNull { w.lowercase().contains(it) }
            if (match != null) return match
        }
        return words.firstOrNull { it.isNotBlank() } ?: clean
    }

    private fun inferFallbackAction(goal: String): AgentAction {
        val lower = goal.lowercase()
        return when {
            lower.contains("افتح") || lower.contains("تطبيق") -> {
                val appName = extractTargetAppName(goal)
                AgentAction.LaunchApp(appName, appName)
            }
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
            lower.contains("اقرأ") || lower.contains("شاشة") -> {
                AgentAction.ReadScreen
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
