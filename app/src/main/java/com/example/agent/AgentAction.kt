package com.example.agent

import com.example.ai.GeminiApiClient
import org.json.JSONObject

data class ToolResult(
    val success: Boolean,
    val message: String,
    val observedUiSnapshot: String? = null
)

data class AgentStepLog(
    val stepIndex: Int,
    val thought: String,
    val toolName: String,
    val toolParams: String,
    val result: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class AgentAction {
    abstract val thought: String

    data class InspectCurrentScreen(
        override val thought: String = "فحص شاشة الهاتف وقراءة عناصرها"
    ) : AgentAction()

    data class LaunchApp(
        val packageName: String,
        val appName: String,
        override val thought: String = "فتح التطبيق المطلوب"
    ) : AgentAction()

    data class TapElement(
        val elementId: Int? = null,
        val text: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        override val thought: String = "الضغط على العنصر في الشاشة"
    ) : AgentAction()

    data class TypeText(
        val text: String,
        val elementId: Int? = null,
        val viewId: String? = null,
        val clearFirst: Boolean = false,
        override val thought: String = "كتابة النص المطلوب"
    ) : AgentAction()

    data class ScrollScreen(
        val direction: String = "down",
        override val thought: String = "تمرير الشاشة"
    ) : AgentAction()

    data class PressBack(
        override val thought: String = "الرجوع للشاشة السابقة"
    ) : AgentAction()

    data class PressHome(
        override val thought: String = "الرجوع للشاشة الرئيسية"
    ) : AgentAction()

    data class WaitForUiChange(
        val seconds: Int = 2,
        override val thought: String = "انتظار تحديث واجهة المستخدم"
    ) : AgentAction()

    data class ReadVisibleContent(
        val target: String? = null,
        override val thought: String = "قراءة النصوص الظاهرة على الشاشة"
    ) : AgentAction()

    data class AskUserConfirmation(
        val prompt: String,
        val dangerLevel: String = "medium",
        override val thought: String = "طلب موافقة المستخدم الصريحة"
    ) : AgentAction()

    data class SetAlarmOrTimer(
        val type: String, // "alarm" or "timer"
        val hour: Int? = null,
        val minute: Int? = null,
        val seconds: Int? = null,
        val label: String = "يا مساعد",
        override val thought: String = "ضبط المنبه أو المؤقت"
    ) : AgentAction()

    data class GetWeather(
        val city: String,
        override val thought: String = "الاستعلام عن حالة الطقس"
    ) : AgentAction()

    data class ManageNotesTasks(
        val action: String, // "add", "delete", "list"
        val title: String? = null,
        val taskId: Long? = null,
        override val thought: String = "إدارة المهام والملاحظات"
    ) : AgentAction()

    data class OpenMapSearch(
        val query: String,
        override val thought: String = "البحث في الخرائط"
    ) : AgentAction()

    data class ReplyToUser(
        val message: String,
        override val thought: String = "الرد المباشر على المستخدم"
    ) : AgentAction()

    data class FinishTask(
        val summary: String,
        val isSuccess: Boolean = true,
        val details: String = "",
        override val thought: String = "إنهاء المهمة وتقديم التقرير الصادق"
    ) : AgentAction()

    // Backward compatibility aliases
    data class SpotifyControl(
        val action: String,
        val query: String? = null,
        override val thought: String = "التحكم في تشغيل الموسيقى"
    ) : AgentAction()

    fun isHighRisk(): Boolean {
        return when (this) {
            is AskUserConfirmation -> true
            is TypeText -> text.contains("كلمة سر") || text.contains("رقم سري") || text.contains("دفع") || text.contains("تحويل")
            is ManageNotesTasks -> action == "delete"
            else -> false
        }
    }

    companion object {
        fun fromJson(jsonStr: String): AgentAction? {
            return try {
                val cleanedInput = GeminiApiClient.cleanModelOutput(jsonStr)
                val clean = if (cleanedInput.startsWith("{") && cleanedInput.endsWith("}")) {
                    cleanedInput
                } else {
                    val firstBrace = cleanedInput.indexOf('{')
                    val lastBrace = cleanedInput.lastIndexOf('}')
                    if (firstBrace != -1 && lastBrace > firstBrace) {
                        cleanedInput.substring(firstBrace, lastBrace + 1)
                    } else {
                        cleanedInput
                    }
                }
                val json = JSONObject(clean)
                val toolName = json.optString("action", json.optString("tool", json.optString("tool_name", "")))
                val thought = json.optString("thought", json.optString("reasoning", "تنفيذ الإجراء المطلوب"))

                fun optNullableString(key: String): String? {
                    return if (json.has(key) && !json.isNull(key)) {
                        val valStr = json.getString(key)
                        if (valStr.equals("null", ignoreCase = true) || valStr.isBlank()) null else valStr
                    } else null
                }

                fun optNullableInt(key: String): Int? {
                    return if (json.has(key) && !json.isNull(key)) {
                        try { json.getInt(key) } catch (e: Exception) { null }
                    } else null
                }

                when (toolName.lowercase().replace("-", "_")) {
                    "inspect_current_screen", "inspectscreen", "read_screen", "readscreen" -> {
                        InspectCurrentScreen(thought)
                    }
                    "launch_app", "launchapp" -> {
                        val pkg = json.optString("packageName", json.optString("package_name", ""))
                        val appName = json.optString("appName", json.optString("app_name", pkg))
                        LaunchApp(packageName = pkg, appName = appName, thought = thought)
                    }
                    "tap_element", "tapelement", "click_element", "clickelement", "click" -> {
                        val elementId = optNullableInt("element_id") ?: optNullableInt("elementId") ?: optNullableInt("id")
                        val text = optNullableString("text")
                        val x = optNullableInt("x")
                        val y = optNullableInt("y")
                        TapElement(elementId = elementId, text = text, x = x, y = y, thought = thought)
                    }
                    "type_text", "typetext", "enter_text", "entertext", "type" -> {
                        val text = json.optString("text", "")
                        val elementId = optNullableInt("element_id") ?: optNullableInt("elementId")
                        val viewId = optNullableString("viewId") ?: optNullableString("view_id")
                        val clear = json.optBoolean("clear_first", json.optBoolean("clearFirst", false))
                        TypeText(text = text, elementId = elementId, viewId = viewId, clearFirst = clear, thought = thought)
                    }
                    "scroll_screen", "scrollscreen", "scroll" -> {
                        val dir = json.optString("direction", "down")
                        ScrollScreen(direction = dir, thought = thought)
                    }
                    "press_back", "pressback", "back" -> {
                        PressBack(thought)
                    }
                    "press_home", "presshome", "home" -> {
                        PressHome(thought)
                    }
                    "wait_for_ui_change", "wait" -> {
                        val sec = json.optInt("seconds", json.optInt("duration", 2))
                        WaitForUiChange(seconds = sec, thought = thought)
                    }
                    "read_visible_content", "read_content" -> {
                        val target = optNullableString("target")
                        ReadVisibleContent(target = target, thought = thought)
                    }
                    "ask_user_confirmation", "ask_confirmation", "confirm" -> {
                        val prompt = json.optString("prompt", json.optString("question", "هل توافق على تنفيذ هذا الإجراء؟"))
                        val danger = json.optString("danger_level", json.optString("dangerLevel", "medium"))
                        AskUserConfirmation(prompt = prompt, dangerLevel = danger, thought = thought)
                    }
                    "set_alarm_or_timer", "set_alarm", "setalarm", "alarm" -> {
                        val type = json.optString("type", if (json.has("seconds")) "timer" else "alarm")
                        val hour = optNullableInt("hour") ?: 8
                        val min = optNullableInt("minute") ?: 0
                        val sec = optNullableInt("seconds")
                        val label = json.optString("label", "منبه يا مساعد")
                        SetAlarmOrTimer(type = type, hour = hour, minute = min, seconds = sec, label = label, thought = thought)
                    }
                    "set_timer", "settimer", "timer" -> {
                        val sec = json.optInt("seconds", 60)
                        val label = json.optString("label", "مؤقت يا مساعد")
                        SetAlarmOrTimer(type = "timer", seconds = sec, label = label, thought = thought)
                    }
                    "get_weather", "getweather", "weather" -> {
                        val city = json.optString("city", "القاهرة")
                        GetWeather(city = city, thought = thought)
                    }
                    "manage_notes_tasks", "manage_tasks", "managetasks", "task" -> {
                        val act = json.optString("taskAction", json.optString("action", "add"))
                        val title = optNullableString("title")
                        val id = if (json.has("taskId")) json.optLong("taskId") else if (json.has("task_id")) json.optLong("task_id") else null
                        ManageNotesTasks(action = act, title = title, taskId = id, thought = thought)
                    }
                    "open_map_search", "maps_search", "mapssearch", "map" -> {
                        val query = json.optString("query", "")
                        OpenMapSearch(query = query, thought = thought)
                    }
                    "spotify_control", "spotify" -> {
                        val act = json.optString("spotifyAction", json.optString("action", "play"))
                        val query = optNullableString("query")
                        SpotifyControl(action = act, query = query, thought = thought)
                    }
                    "reply_to_user", "reply", "chat" -> {
                        val msg = json.optString("message", json.optString("text", "أهلاً بك"))
                        ReplyToUser(message = msg, thought = thought)
                    }
                    "finish_task", "finishtask", "finish", "done" -> {
                        val summary = json.optString("summary", "تم إنجاز المهمة بنجاح.")
                        val success = json.optBoolean("is_success", json.optBoolean("success", true))
                        val details = json.optString("details", "")
                        FinishTask(summary = summary, isSuccess = success, details = details, thought = thought)
                    }
                    else -> {
                        // Fallback check: if there's direct reply or finish in json
                        if (json.has("reply") || json.has("message")) {
                            ReplyToUser(json.optString("reply", json.optString("message", "")), thought)
                        } else if (json.has("summary")) {
                            FinishTask(json.optString("summary", ""), true, thought = thought)
                        } else null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
