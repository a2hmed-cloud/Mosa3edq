package com.example.agent

import org.json.JSONObject

sealed class AgentAction {
    data class LaunchApp(val packageName: String, val appName: String) : AgentAction()
    object ReadScreen : AgentAction()
    object CaptureScreen : AgentAction()
    data class ClickElement(val text: String? = null, val viewId: String? = null, val x: Int? = null, val y: Int? = null) : AgentAction()
    data class EnterText(val text: String, val viewId: String? = null) : AgentAction()
    data class Scroll(val direction: String = "down") : AgentAction()
    object PressBack : AgentAction()
    object PressHome : AgentAction()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String = "منبه يا مساعد") : AgentAction()
    data class SetTimer(val seconds: Int, val label: String = "مؤقت يا مساعد") : AgentAction()
    data class ManageTasks(val action: String, val title: String? = null, val taskId: Long? = null) : AgentAction()
    data class GetWeather(val city: String) : AgentAction()
    data class SpotifyControl(val action: String, val query: String? = null) : AgentAction()
    data class MapsSearch(val query: String) : AgentAction()
    data class Reply(val message: String) : AgentAction()
    data class AskUser(val question: String) : AgentAction()
    data class FinishTask(val summary: String) : AgentAction()

    fun isHighRisk(): Boolean {
        return when (this) {
            is EnterText -> text.contains("كلمة سر") || text.contains("رقم سري") || text.contains("دفع")
            is ManageTasks -> action == "delete"
            else -> false
        }
    }

    companion object {
        fun fromJson(jsonStr: String): AgentAction? {
            return try {
                val cleanedInput = com.example.ai.GeminiApiClient.cleanModelOutput(jsonStr)
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
                val toolName = json.optString("action", json.optString("tool", ""))


                fun optNullableString(key: String): String? {
                    return if (json.has(key) && !json.isNull(key)) {
                        val valStr = json.getString(key)
                        if (valStr.equals("null", ignoreCase = true)) null else valStr
                    } else null
                }

                when (toolName.lowercase()) {
                    "launch_app", "launchapp" -> {
                        val pkg = json.optString("packageName", "com.spotify.music")
                        val name = json.optString("appName", "التطبيق")
                        LaunchApp(pkg, name)
                    }
                    "read_screen", "readscreen" -> ReadScreen
                    "capture_screen", "capturescreen" -> CaptureScreen
                    "click_element", "clickelement", "click" -> {
                        val text = optNullableString("text")
                        val viewId = optNullableString("viewId")
                        val x = if (json.has("x")) json.optInt("x") else null
                        val y = if (json.has("y")) json.optInt("y") else null
                        ClickElement(text, viewId, x, y)
                    }
                    "enter_text", "entertext", "type" -> {
                        val text = json.optString("text", "")
                        val viewId = optNullableString("viewId")
                        EnterText(text, viewId)
                    }
                    "scroll" -> {
                        val dir = json.optString("direction", "down")
                        Scroll(dir)
                    }
                    "press_back", "back" -> PressBack
                    "press_home", "home" -> PressHome
                    "set_alarm", "setalarm", "alarm" -> {
                        val hour = json.optInt("hour", 8)
                        val minute = json.optInt("minute", 0)
                        val label = json.optString("label", "منبه يا مساعد")
                        SetAlarm(hour, minute, label)
                    }
                    "set_timer", "settimer", "timer" -> {
                        val seconds = json.optInt("seconds", 60)
                        val label = json.optString("label", "مؤقت يا مساعد")
                        SetTimer(seconds, label)
                    }
                    "manage_tasks", "managetasks", "task" -> {
                        val act = json.optString("taskAction", json.optString("actionType", "add"))
                        val title = optNullableString("title")
                        val id = if (json.has("taskId")) json.optLong("taskId") else null
                        ManageTasks(act, title, id)
                    }
                    "get_weather", "getweather", "weather" -> {
                        val city = json.optString("city", "القاهرة")
                        GetWeather(city)
                    }
                    "spotify_control", "spotifycontrol", "spotify" -> {
                        val act = json.optString("spotifyAction", json.optString("actionType", "play"))
                        val query = optNullableString("query")
                        SpotifyControl(act, query)
                    }
                    "maps_search", "mapssearch", "maps" -> {
                        val query = json.optString("query", "مطعم قريب")
                        MapsSearch(query)
                    }
                    "reply" -> {
                        val msg = json.optString("message", "تمام يا فندم")
                        Reply(msg)
                    }
                    "ask_user", "askuser" -> {
                        val q = json.optString("question", "تحب أحضر لك إيه أكتير؟")
                        AskUser(q)
                    }
                    "finish_task", "finishtask", "finish" -> {
                        val summary = json.optString("summary", "تم تنفيذ الطلب بنجاح")
                        FinishTask(summary)
                    }
                    else -> FinishTask("تم تنفيذ الطلب")
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
