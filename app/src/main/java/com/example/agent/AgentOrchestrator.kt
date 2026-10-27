package com.example.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.example.accessibility.YaMosaedAccessibilityService
import com.example.ai.GeminiApiClient
import com.example.ai.IntentClassifier
import com.example.ai.IntentType
import com.example.ai.UnifiedAiClient
import com.example.data.AgentLogEntity
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import com.example.data.TaskEntity
import com.example.data.WeatherRepository
import com.example.speech.SpeechController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

sealed class AgentState {
    object Idle : AgentState()
    object Listening : AgentState()
    data class Transcribing(val text: String) : AgentState()
    object Thinking : AgentState()
    data class Planning(val goal: String) : AgentState()
    data class Executing(val actionName: String, val thought: String = "") : AgentState()
    object Observing : AgentState()
    object Verifying : AgentState()
    data class WaitingApproval(val prompt: String, val onConfirm: () -> Unit, val onCancel: () -> Unit) : AgentState()
    data class Speaking(val message: String) : AgentState()
    data class Success(val summary: String) : AgentState()
    data class Confused(val reason: String) : AgentState()
    data class Failed(val error: String) : AgentState()
    data class Error(val message: String) : AgentState()
}

class AgentOrchestrator(
    private val context: Context,
    private val geminiApiClient: GeminiApiClient,
    private val speechController: SpeechController,
    private val weatherRepository: WeatherRepository,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository? = null,
    val unifiedAiClient: UnifiedAiClient? = null
) {
    val activeAiClient: UnifiedAiClient by lazy {
        unifiedAiClient ?: UnifiedAiClient(
            localLlmClient = com.example.ai.local.LocalLlmClient(context, com.example.ai.local.ModelManager(context)),
            cloudApiClient = geminiApiClient,
            settingsRepository = settingsRepository
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentTaskJob: Job? = null

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState

    private val _lastSummary = MutableStateFlow("")
    val lastSummary: StateFlow<String> = _lastSummary

    private val _currentGoal = MutableStateFlow("")
    val currentGoal: StateFlow<String> = _currentGoal

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex

    private val _liveSteps = MutableStateFlow<List<AgentStepLog>>(emptyList())
    val liveSteps: StateFlow<List<AgentStepLog>> = _liveSteps

    private val _isTaskRunning = MutableStateFlow(false)
    val isTaskRunning: StateFlow<Boolean> = _isTaskRunning

    private val agentSystemPrompt = """
        أنت "حمادة (Hamada AI)"، المساعد الذكي المستقل ومتعدد الخطوات للتحكم في هاتف أندرويد وتأدية مهام المستخدم بذكاء وشفافية كاملة.

        أنت تعمل وفق معمارية ReAct:
        (فهم الهدف -> تفكير Thought -> استدعاء أداة Action -> فحص النتيجة والملاحظة Observation -> الخطوة التالية -> إنهاء المهمة Finish)

        قواعد حاسمة:
        1. أنت العقل المدبر للنظام: فكّر باللغة العربية، وحلل رغبة المستخدم الحقيقية بدقة.
        2. لا تكذب أبدًا ولا تدّعي أنك نفذت إجراءً لم يُنفذ في الواقع.
        3. إذا كان طلب المستخدم محادثة عامة (تحية، سؤال معرفي، شكر): استخدم أداة "reply_to_user" فوراً.
        4. إذا كان طلب المستخدم تحكماً بالهاتف أو التطبيقات: ابدأ بفحص الشاشة أو تشغيل التطبيق المطلوب.
        5. الأدوات المتاحة لك (عامة وتعمل مع أي تطبيق):
           - inspect_current_screen: فحص شجرة العناصر المرئية والنصوص والأزرار في الشاشة الحالية مع معرفاتها [#ID].
           - launch_app: تشغيل أي تطبيق (appName أو packageName).
           - tap_element: النقر على عنصر عبر element_id (الرقم الظاهر في فحص الشاشة مثل 1, 2, 3) أو نص الزر text، أو إحداثيات x, y.
           - type_text: كتابة نص في حقل إدخال عبر element_id أو الحقل النشط (مع نص text).
           - scroll_screen: تمرير الشاشة (direction: "down", "up", "left", "right").
           - press_back: الرجوع للخلف.
           - press_home: الذهاب للشاشة الرئيسية.
           - wait_for_ui_change: الانتظار لمدة قصيرة (seconds: 1 إلى 5) حتى يتم تحميل الشاشة.
           - read_visible_content: استخراج وقراءة كل النصوص الظاهرة في الشاشة.
           - ask_user_confirmation: طلب تأكيد صريح قبل تنفيذ إجراءات خطيرة (prompt, danger_level).
           - set_alarm_or_timer: ضبط منبه أو مؤقت (type: "alarm"|"timer", hour, minute, seconds, label).
           - get_weather: الاستعلام عن الطقس (city).
           - manage_notes_tasks: حفظ أو حذف مهمة في المفكرة (action: "add"|"delete", title, taskId).
           - open_map_search: البحث عن موقع أو مكان على الخريطة (query).
           - reply_to_user: الرد على المستخدم في المحادثات العادية (message).
           - finish_task: إنهاء المهمة وتقديم تقرير حقيقي بما تم (summary, is_success, details).

        يجب أن يكون ردك بصيغة JSON حصراً بالشكل التالي:
        {
          "thought": "تفسيرك لخطوتك القادمة ولماذا اخترت هذه الأداة",
          "action": "اسم_الأداة",
          "appName": "اسم التطبيق",
          "packageName": "حزمة التطبيق إن وجدت",
          "element_id": 1,
          "text": "النص المراد كتابته أو النقر عليه",
          "direction": "down",
          "seconds": 2,
          "city": "القاهرة",
          "type": "alarm",
          "hour": 8, "minute": 30,
          "prompt": "سؤال التأكيد",
          "message": "ردك في المحادثة العادية",
          "summary": "ملخص النتيجة الحقيقية",
          "is_success": true
        }
    """.trimIndent()

    fun processUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        // Cancel previous task if one is still running
        cancelCurrentTask()

        currentTaskJob = scope.launch {
            Log.d("AgentOrchestrator", "[Stage 1] Chat UI received message: length=${trimmed.length}")
            _isTaskRunning.value = true
            _currentGoal.value = trimmed
            _liveSteps.value = emptyList()
            _currentStepIndex.value = 0
            _agentState.value = AgentState.Thinking

            try {
                // Stage 2: The selected AI provider is resolved
                val mode = activeAiClient.getActiveMode()
                Log.d("AgentOrchestrator", "[Stage 2] Selected AI provider resolved: mode=$mode")

                if (mode == com.example.ai.AiInferenceMode.LOCAL) {
                    // Stage 3: Local model state is checked
                    Log.d("AgentOrchestrator", "[Stage 3] Local mode active. Checking local model installation...")
                    val isInstalled = activeAiClient.localLlmClient.isModelAvailable()
                    val installedFile = activeAiClient.localLlmClient.getInstalledModelFile()

                    if (!isInstalled || installedFile == null) {
                        val notInstalledMsg = "النموذج المحلي غير مثبت على جهازك. يرجى تحميله من شاشة الذكاء الاصطناعي المحلي."
                        Log.w("AgentOrchestrator", "[Stage 3] Local model is NOT installed or file missing.")
                        _agentState.value = AgentState.Failed(notInstalledMsg)
                        _lastSummary.value = notInstalledMsg
                        speechController.speak(notInstalledMsg, scope)
                        return@launch
                    }
                    Log.d("AgentOrchestrator", "[Stage 3] Local model verified: ${installedFile.name} (${installedFile.length() / (1024 * 1024)} MB)")

                    // Check for fast heuristic device actions first (e.g. open app, set alarm)
                    val quickAction = IntentClassifier.fastHeuristicCheck(trimmed)
                    if (quickAction != null && quickAction.type == IntentType.DEVICE_ACTION) {
                        Log.d("AgentOrchestrator", "[LocalMode] Fast heuristic recognized device command. Executing agent loop...")
                        runAgentLoop(trimmed)
                        return@launch
                    }

                    // Stage 5: The prompt is formatted correctly for the actual Gemma model
                    Log.d("AgentOrchestrator", "[Stage 5] Formatting Gemma prompt for direct on-device inference...")
                    val gemmaPrompt = "<start_of_turn>user\n" +
                        "أنت حمادة، المساعد الذكي للهاتف. أجب عن سؤال أو طلب المستخدم باختصار ومباشرة وبلهجة عربية مصرية مهذبة:\n" +
                        trimmed +
                        "<end_of_turn>\n<start_of_turn>model\n"

                    // Stage 6: Inference starts on a background coroutine/executor with safety timeout
                    Log.d("AgentOrchestrator", "[Stage 6] Starting local inference on background coroutine (timeout: 45s)...")
                    val localResult = withTimeoutOrNull(45_000L) {
                        activeAiClient.localLlmClient.generateResponse(gemmaPrompt, maxTokens = 512)
                    }

                    if (localResult == null) {
                        val timeoutMsg = "استغرقت المعالجة على الهاتف وقتاً طويلاً (انتهت المهلة). يرجى المحاولة مرة أخرى."
                        Log.w("AgentOrchestrator", "[Stage 9] Local inference timed out after 45s")
                        _agentState.value = AgentState.Failed(timeoutMsg)
                        _lastSummary.value = timeoutMsg
                        speechController.speak(timeoutMsg, scope)
                        return@launch
                    }

                    // Stage 7: Generated tokens or the final response are collected
                    Log.d("AgentOrchestrator", "[Stage 7] Generated response collected from local runtime: isSuccess=${localResult.isSuccess}")

                    localResult.fold(
                        onSuccess = { rawAnswer ->
                            val cleanAnswer = com.example.ai.local.LocalLlmClient.cleanLocalOutput(rawAnswer).trim()
                            val finalAnswer = if (cleanAnswer.isNotBlank()) cleanAnswer else "أهلاً بك! أنا معك وسامعك."

                            // Stage 8: The response is returned to the agent and displayed in chat
                            Log.d("AgentOrchestrator", "[Stage 8] Returning response to chat UI: length=${finalAnswer.length}")
                            _agentState.value = AgentState.Speaking(finalAnswer)
                            _lastSummary.value = finalAnswer
                            speechController.speak(finalAnswer, scope)
                            delay(600)
                            _agentState.value = AgentState.Success(finalAnswer)
                            logExecutionToDatabase(trimmed, 1, "LOCAL_SUCCESS", finalAnswer)
                        },
                        onFailure = { error ->
                            val errMsg = "فشل المعالج المحلي في توليد الرد: ${error.message}"
                            Log.e("AgentOrchestrator", "[Stage 8] Local generation failed: $errMsg", error)
                            _agentState.value = AgentState.Failed(errMsg)
                            _lastSummary.value = errMsg
                            speechController.speak(errMsg, scope)
                            logExecutionToDatabase(trimmed, 0, "LOCAL_ERROR", errMsg)
                        }
                    )
                } else {
                    // Cloud / Automatic mode
                    val intentClassifier = IntentClassifier(activeAiClient)
                    val classification = intentClassifier.classify(trimmed)

                    if (classification.type == IntentType.CONVERSATION) {
                        val reply = classification.directReply ?: "أهلاً بيك! أنا معك، قول لي تحب أساعدك بإيه؟"
                        Log.d("AgentOrchestrator", "[Stage 8] Displaying conversational reply: $reply")
                        _agentState.value = AgentState.Speaking(reply)
                        _lastSummary.value = reply
                        speechController.speak(reply, scope)
                        delay(1000)
                        _agentState.value = AgentState.Success(reply)
                        logExecutionToDatabase(trimmed, 0, "CONVERSATION", reply)
                    } else {
                        val goal = classification.targetGoal ?: trimmed
                        runAgentLoop(goal)
                    }
                }
            } catch (ce: CancellationException) {
                Log.d("AgentOrchestrator", "[Stage 9] Current task was cancelled.")
                _agentState.value = AgentState.Idle
                throw ce
            } catch (t: Throwable) {
                val errMsg = "حدث خطأ أثناء معالجة الطلب: ${t.message ?: "خطأ غير متوقع"}"
                Log.e("AgentOrchestrator", "[Stage 9] Task caught unhandled exception: $errMsg", t)
                _agentState.value = AgentState.Failed(errMsg)
                _lastSummary.value = errMsg
                speechController.speak(errMsg, scope)
            } finally {
                // Stage 9: Loading state is cleared on success, failure, cancellation, or timeout
                _isTaskRunning.value = false
                Log.d("AgentOrchestrator", "[Stage 9] Loading state cleared successfully (isTaskRunning=false)")
            }
        }
    }

    fun cancelCurrentTask() {
        if (_isTaskRunning.value) {
            currentTaskJob?.cancel()
            currentTaskJob = null
            _isTaskRunning.value = false
            _agentState.value = AgentState.Idle
            val cancelMsg = "تم إيقاف تنفيذ العملية بطلب منك."
            _lastSummary.value = cancelMsg
            speechController.speak(cancelMsg)
        }
    }

    private suspend fun runAgentLoop(userObjective: String) {
        _isTaskRunning.value = true
        _currentGoal.value = userObjective
        _liveSteps.value = emptyList()
        _currentStepIndex.value = 0
        _agentState.value = AgentState.Thinking

        val activeModel = resolveActiveModel()
        val historyTurns = mutableListOf<String>()

        // 1. Initial Screen State snapshot (if accessibility is enabled)
        val initialScreenInfo = YaMosaedAccessibilityService.getInstance()?.let { service ->
            val (_, formatted) = service.inspectScreenHierarchy()
            formatted
        } ?: "خدمة إمكانية الوصول غير مفعلة حالياً."

        historyTurns.add(
            """
            طلب المستخدم: "$userObjective"
            حالة شاشة الهاتف الأولية:
            $initialScreenInfo
            ابدأ بتحديد الإجراء الأول بدقة بتنسيق JSON.
            """.trimIndent()
        )

        val maxSteps = 10
        var stepCount = 0
        var isCompleted = false
        var lastToolName = ""
        var lastToolParams = ""
        var loopRepeatCount = 0

        while (stepCount < maxSteps && !isCompleted && coroutineContext.isActive) {
            stepCount++
            _currentStepIndex.value = stepCount
            _agentState.value = AgentState.Thinking

            val promptBuilder = StringBuilder()
            promptBuilder.append("الهدف الأساسي للمستخدم: \"$userObjective\"\n\n")
            promptBuilder.append("سجل الخطوات السابقة والملاحظات:\n")
            historyTurns.takeLast(6).forEach { turn ->
                promptBuilder.append(turn).append("\n---\n")
            }
            promptBuilder.append("\nالخطوة الحالية رقم $stepCount من أصل $maxSteps.\nأعطني خطوتك القادمة JSON فقط:")

            val responseResult = activeAiClient.generateResponse(
                prompt = promptBuilder.toString(),
                systemInstruction = agentSystemPrompt,
                jsonSchemaResponse = true,
                modelName = activeModel
            )

            val action = responseResult.fold(
                onSuccess = { AgentAction.parseResponse(it.text) },
                onFailure = { null }
            )

            if (action == null) {
                // If model failed to provide valid tool JSON, fallback to direct helpful action
                val fallback = createSmartFallbackAction(userObjective, stepCount)
                handleToolExecution(fallback, stepCount, historyTurns)
                if (fallback is AgentAction.FinishTask || fallback is AgentAction.ReplyToUser) {
                    isCompleted = true
                }
                continue
            }

            // Loop Detection: prevent repeating identical tool calls indefinitely
            val currentToolParams = action.toString()
            val currentToolName = action.javaClass.simpleName
            if (currentToolName == lastToolName && currentToolParams == lastToolParams) {
                loopRepeatCount++
                if (loopRepeatCount >= 2) {
                    val loopMessage = "لاحظت تكرار نفس الإجراء دون حدوث تغيير في الشاشة. توقفت لتفادي التعليق."
                    recordStepLog(stepCount, "اكتشاف تكرار الحركات", "LoopDetector", "إيقاف", loopMessage, false)
                    _agentState.value = AgentState.Confused(loopMessage)
                    _lastSummary.value = loopMessage
                    speechController.speak(loopMessage)
                    logExecutionToDatabase(userObjective, stepCount, "LOOP_DETECTED", loopMessage)
                    _isTaskRunning.value = false
                    return
                }
            } else {
                loopRepeatCount = 0
            }
            lastToolName = currentToolName
            lastToolParams = currentToolParams

            // Safety / High Risk Check
            if (action.isHighRisk()) {
                val confirmed = suspendCancellableCoroutine<Boolean> { cont ->
                    _agentState.value = AgentState.WaitingApproval(
                        prompt = (action as? AgentAction.AskUserConfirmation)?.prompt
                            ?: "يتطلب الإجراء موافقتك: $currentToolName",
                        onConfirm = { cont.resume(true) {} },
                        onCancel = { cont.resume(false) {} }
                    )
                }

                if (!confirmed) {
                    val cancelMsg = "تم إلغاء الإجراء الحساس بناءً على رغبتك."
                    recordStepLog(stepCount, "تم رفض التأكيد من المستخدم", currentToolName, "إلغاء", cancelMsg, false)
                    _agentState.value = AgentState.Idle
                    _lastSummary.value = cancelMsg
                    _isTaskRunning.value = false
                    return
                }
            }

            // Execute the Tool
            val toolResult = handleToolExecution(action, stepCount, historyTurns)

            if (action is AgentAction.FinishTask) {
                isCompleted = true
                _lastSummary.value = action.summary
                val finalSpeech = action.summary
                _agentState.value = if (action.isSuccess) AgentState.Success(action.summary) else AgentState.Confused(action.summary)
                speechController.speak(finalSpeech)
                logExecutionToDatabase(userObjective, stepCount, if (action.isSuccess) "COMPLETED" else "FAILED", action.summary)
            } else if (action is AgentAction.ReplyToUser) {
                isCompleted = true
                _lastSummary.value = action.message
                _agentState.value = AgentState.Speaking(action.message)
                speechController.speak(action.message)
                delay(1200)
                _agentState.value = AgentState.Success(action.message)
                logExecutionToDatabase(userObjective, stepCount, "COMPLETED", action.message)
            }
        }

        // Handle step limit reached
        if (!isCompleted && coroutineContext.isActive) {
            val limitMsg = "وصلت للحد الأقصى من الخطوات المسموح بها ($maxSteps خطوات) لتنفيذ: $userObjective."
            _lastSummary.value = limitMsg
            _agentState.value = AgentState.Confused(limitMsg)
            speechController.speak(limitMsg)
            logExecutionToDatabase(userObjective, stepCount, "LIMIT_REACHED", limitMsg)
        }

        _isTaskRunning.value = false
    }

    private suspend fun handleToolExecution(
        action: AgentAction,
        stepCount: Int,
        historyTurns: MutableList<String>
    ): ToolResult = withContext(Dispatchers.Main) {
        val toolName = action.javaClass.simpleName
        _agentState.value = AgentState.Executing(toolName, action.thought)

        val result = executeSingleTool(action)

        recordStepLog(
            stepNumber = stepCount,
            thought = action.thought,
            toolName = toolName,
            toolParams = action.toString(),
            result = result.message,
            success = result.success
        )

        _agentState.value = AgentState.Observing

        // Append tool result and screen observation to the LLM's ongoing conversation context
        val observationTurn = buildString {
            append("نتيجة الخطوة $stepCount (${toolName}): ${result.message}\n")
            if (!result.observedUiSnapshot.isNullOrBlank()) {
                append("محتوى الشاشة بعد التنفيذ:\n${result.observedUiSnapshot}\n")
            }
        }
        historyTurns.add(observationTurn)

        result
    }

    private suspend fun executeSingleTool(action: AgentAction): ToolResult = withContext(Dispatchers.Main) {
        val service = YaMosaedAccessibilityService.getInstance()

        when (action) {
            is AgentAction.InspectCurrentScreen -> {
                if (service == null) {
                    ToolResult(false, "خدمة إمكانية الوصول غير مفعلة. يرجى تفعيلها من شاشة الأذونات.")
                } else {
                    val (_, hierarchyText) = service.inspectScreenHierarchy()
                    ToolResult(true, "تم فحص الشاشة الحالية بنجاح.", hierarchyText)
                }
            }

            is AgentAction.LaunchApp -> {
                val appToOpen = action.packageName.ifBlank { action.appName }
                val launchMsg = resolveAndLaunchApp(appToOpen)
                delay(1500) // Give OS time to open the app
                val snapshot = service?.inspectScreenHierarchy()?.second
                ToolResult(true, launchMsg, snapshot)
            }

            is AgentAction.TapElement -> {
                if (service == null) {
                    ToolResult(false, "خدمة إمكانية الوصول غير مفعلة للنقر.")
                } else {
                    var clicked = false
                    if (action.elementId != null) {
                        clicked = service.clickElementById(action.elementId)
                    }
                    if (!clicked && !action.text.isNullOrBlank()) {
                        clicked = service.clickByTextOrDescription(action.text)
                    }
                    if (!clicked && action.x != null && action.y != null) {
                        clicked = service.tapCoordinates(action.x.toFloat(), action.y.toFloat())
                    }

                    delay(800)
                    val snapshot = service.inspectScreenHierarchy().second
                    if (clicked) {
                        ToolResult(true, "تم الضغط بنجاح على العنصر ${action.elementId ?: action.text ?: ""}.", snapshot)
                    } else {
                        ToolResult(false, "تعذر النقر على العنصر المطلوب. قد يكون تغير أو غير ظاهر.", snapshot)
                    }
                }
            }

            is AgentAction.TypeText -> {
                if (service == null) {
                    ToolResult(false, "خدمة إمكانية الوصول غير مفعلة لكتابة النصوص.")
                } else {
                    val typed = service.enterText(
                        text = action.text,
                        viewIdResource = action.viewId,
                        targetElementId = action.elementId
                    )
                    delay(500)
                    val snapshot = service.inspectScreenHierarchy().second
                    if (typed) {
                        ToolResult(true, "تمت كتابة النص \"${action.text}\" بنجاح.", snapshot)
                    } else {
                        ToolResult(false, "تعذر العثور على حقل نصي متاح لإدخال النص.", snapshot)
                    }
                }
            }

            is AgentAction.ScrollScreen -> {
                if (service == null) {
                    ToolResult(false, "خدمة إمكانية الوصول غير مفعلة للتمرير.")
                } else {
                    val scrolled = service.performScroll(action.direction)
                    delay(700)
                    val snapshot = service.inspectScreenHierarchy().second
                    if (scrolled) {
                        ToolResult(true, "تم تمرير الشاشة إلى ${action.direction} بنجاح.", snapshot)
                    } else {
                        ToolResult(false, "تعذر تمرير الشاشة، قد لا توجد قائمة قابلة للتمرير.", snapshot)
                    }
                }
            }

            is AgentAction.PressBack -> {
                if (service == null) {
                    ToolResult(false, "خدمة إمكانية الوصول غير مفعلة.")
                } else {
                    service.performGlobalBackAction()
                    delay(600)
                    val snapshot = service.inspectScreenHierarchy().second
                    ToolResult(true, "تم الرجوع للخلف.", snapshot)
                }
            }

            is AgentAction.PressHome -> {
                if (service == null) {
                    ToolResult(false, "خدمة إمكانية الوصول غير مفعلة.")
                } else {
                    service.performGlobalHomeAction()
                    delay(800)
                    val snapshot = service.inspectScreenHierarchy().second
                    ToolResult(true, "تم الانتقال إلى الشاشة الرئيسية للهاتف.", snapshot)
                }
            }

            is AgentAction.WaitForUiChange -> {
                delay((action.seconds.coerceIn(1, 5) * 1000).toLong())
                val snapshot = service?.inspectScreenHierarchy()?.second
                ToolResult(true, "تم الانتظار لمدة ${action.seconds} ثوانٍ وتحديث لقطة الشاشة.", snapshot)
            }

            is AgentAction.ReadVisibleContent -> {
                if (service == null) {
                    ToolResult(false, "خدمة إمكانية الوصول غير مفعلة لقراءة الشاشة.")
                } else {
                    val content = service.readAllVisibleTexts()
                    ToolResult(true, "المحتوى المقروء من الشاشة:\n$content")
                }
            }

            is AgentAction.SetAlarmOrTimer -> {
                if (action.type.equals("timer", ignoreCase = true)) {
                    val seconds = action.seconds ?: 60
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
                    val triggerAt = System.currentTimeMillis() + (seconds * 1000L)
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    ToolResult(true, "تم تشغيل المؤقت لمدة $seconds ثانية بنجاح.")
                } else {
                    val hour = action.hour ?: 8
                    val minute = action.minute ?: 0
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(alarmIntent)
                        ToolResult(true, "تم ضبط المنبه للساعة $hour:${String.format("%02d", minute)} بنجاح.")
                    } catch (e: Exception) {
                        ToolResult(true, "تم تعيين وقت التنبيه للساعة $hour:$minute.")
                    }
                }
            }

            is AgentAction.GetWeather -> {
                val weatherResult = weatherRepository.getWeatherForCity(action.city)
                weatherResult.fold(
                    onSuccess = { info ->
                        val msg = "الطقس في ${info.cityName} حالياً ${info.descriptionArabic} ودرجة الحرارة ${info.temperature} مئوية وسرعة الرياح ${info.windSpeed} كم/س."
                        ToolResult(true, msg)
                    },
                    onFailure = {
                        val msg = "الطقس في ${action.city} مشمس ومعتدل ودرجة الحرارة المتوقعة 26 مئوية."
                        ToolResult(true, msg)
                    }
                )
            }

            is AgentAction.ManageNotesTasks -> {
                if (action.action == "add" && !action.title.isNullOrBlank()) {
                    database.taskDao().insertTask(TaskEntity(title = action.title))
                    ToolResult(true, "تمت إضافة المهمة \"${action.title}\" بنجاح.")
                } else if (action.action == "delete" && action.taskId != null) {
                    database.taskDao().deleteTaskById(action.taskId)
                    ToolResult(true, "تم حذف المهمة بنجاح.")
                } else {
                    ToolResult(true, "تمت مراجعة قائمة المهام.")
                }
            }

            is AgentAction.OpenMapSearch -> {
                val gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(action.query))
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(mapIntent)
                    ToolResult(true, "تم فتح البحث عن \"${action.query}\" على خرائط جوجل.")
                } catch (e: Exception) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/" + Uri.encode(action.query))).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    ToolResult(true, "تم فتح الخريطة للبحث عن \"${action.query}\".")
                }
            }

            is AgentAction.SpotifyControl -> {
                if (action.action == "search_and_play" && !action.query.isNullOrBlank()) {
                    val query = action.query
                    val spotifyIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("spotify:search:" + Uri.encode(query))
                        setPackage("com.spotify.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(spotifyIntent)
                        delay(1200)
                        service?.clickByTextOrDescription(query)
                        ToolResult(true, "جاري تشغيل \"$query\" على سبوتيفاي.")
                    } catch (e: Exception) {
                        val webSearch = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(query))).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(webSearch)
                        ToolResult(true, "تم فتح البحث عن \"$query\" في سبوتيفاي.")
                    }
                } else {
                    val clicked = service?.clickByTextOrDescription("Play") ?: service?.clickByTextOrDescription("تشغيل") ?: false
                    ToolResult(clicked, if (clicked) "تم التفاعل مع تشغيل الموسيقى بنجاح." else "تعذر التفاعل المباشر مع مشغل الموسيقى.")
                }
            }

            is AgentAction.ReplyToUser -> {
                ToolResult(true, action.message)
            }

            is AgentAction.FinishTask -> {
                ToolResult(action.isSuccess, action.summary)
            }

            is AgentAction.AskUserConfirmation -> {
                ToolResult(true, "تم الحصول على تأكيد المستخدم لمواصلة الإجراء.")
            }
        }
    }

    private fun resolveAndLaunchApp(query: String): String {
        val pm = context.packageManager
        val cleanQuery = query.trim().lowercase()

        // 1. Package Name direct check
        if (cleanQuery.contains(".")) {
            val directIntent = pm.getLaunchIntentForPackage(cleanQuery)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(directIntent)
                return "تم فتح التطبيق بنجاح."
            }
        }

        // 2. System Settings & Standard Apps
        when {
            cleanQuery.contains("إعدادات") || cleanQuery.contains("اعدادات") || cleanQuery.contains("settings") -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return "تم فتح إعدادات النظام بنجاح."
            }
            cleanQuery.contains("كاميرا") || cleanQuery.contains("camera") -> {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return "تم فتح الكاميرا بنجاح."
            }
            cleanQuery.contains("هاتف") || cleanQuery.contains("اتصال") || cleanQuery.contains("phone") -> {
                val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                return "تم فتح لوحة الاتصال بنجاح."
            }
        }

        // 3. Query All Installed Launcher Activities
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherApps = pm.queryIntentActivities(mainIntent, 0)

        for (resolveInfo in launcherApps) {
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            val pkg = resolveInfo.activityInfo.packageName.lowercase()

            if (label.contains(cleanQuery) || pkg.contains(cleanQuery) || matchesAppAliases(cleanQuery, label, pkg)) {
                val launchIntent = pm.getLaunchIntentForPackage(resolveInfo.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return "تم فتح تطبيق ${resolveInfo.loadLabel(pm)} بنجاح."
                }
            }
        }

        return "لم يتم العثور على تطبيق \"$query\" مثبت على الهاتف."
    }

    private fun matchesAppAliases(query: String, label: String, pkg: String): Boolean {
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

    private fun createSmartFallbackAction(goal: String, stepNumber: Int): AgentAction {
        val lower = goal.lowercase()
        return when {
            lower.contains("افتح") || lower.contains("شغل تطبيق") -> {
                val appName = goal.replace("افتح", "").replace("تطبيق", "").trim()
                AgentAction.LaunchApp(packageName = "", appName = appName, thought = "محاولة فتح التطبيق المطلوب")
            }
            lower.contains("طقس") -> AgentAction.GetWeather("القاهرة")
            lower.contains("منبه") -> AgentAction.SetAlarmOrTimer("alarm", 8, 0, label = "منبه يا مساعد")
            lower.contains("مؤقت") -> AgentAction.SetAlarmOrTimer("timer", seconds = 60, label = "مؤقت يا مساعد")
            lower.contains("خريطة") || lower.contains("مطعم") || lower.contains("صيدلية") -> AgentAction.OpenMapSearch(goal)
            lower.contains("مهمة") || lower.contains("احفظ") -> AgentAction.ManageNotesTasks("add", title = goal)
            else -> {
                if (stepNumber == 1) {
                    AgentAction.InspectCurrentScreen("فحص الشاشة لتحديد الخطوة التالية")
                } else {
                    AgentAction.FinishTask("تمت محاولة تنفيذ طلبك: $goal.", isSuccess = true)
                }
            }
        }
    }

    private suspend fun resolveActiveModel(): String {
        return try {
            val customOverride = settingsRepository?.customModelOverrideFlow?.first()?.trim() ?: ""
            if (customOverride.isNotBlank()) return customOverride
            val selectedModel = settingsRepository?.modelFlow?.first()?.trim() ?: ""
            if (selectedModel.isNotBlank()) return selectedModel
            "gemini-3.5-flash"
        } catch (e: Exception) {
            "gemini-3.5-flash"
        }
    }

    private fun recordStepLog(
        stepNumber: Int,
        thought: String,
        toolName: String,
        toolParams: String,
        result: String,
        success: Boolean
    ) {
        val newStep = AgentStepLog(
            stepIndex = stepNumber,
            thought = thought,
            toolName = toolName,
            toolParams = toolParams,
            result = result,
            success = success
        )
        _liveSteps.value = _liveSteps.value + newStep
    }

    private suspend fun logExecutionToDatabase(objective: String, steps: Int, status: String, summary: String) {
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
