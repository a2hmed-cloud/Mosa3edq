package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentAction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string app_name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("يا مساعد", appName)
    }

    @Test
    fun `test AgentAction json parsing`() {
        val jsonStr = """{"action": "set_alarm", "hour": 8, "minute": 30, "label": "منبه العمل"}"""
        val action = AgentAction.fromJson(jsonStr)

        assertTrue(action is AgentAction.SetAlarm)
        val alarm = action as AgentAction.SetAlarm
        assertEquals(8, alarm.hour)
        assertEquals(30, alarm.minute)
        assertEquals("منبه العمل", alarm.label)
    }

    @Test
    fun `test SpotifyControl action json parsing`() {
        val jsonStr = """{"action": "spotify_control", "spotifyAction": "search_and_play", "query": "عمرو دياب"}"""
        val action = AgentAction.fromJson(jsonStr)

        assertTrue(action is AgentAction.SpotifyControl)
        val spotify = action as AgentAction.SpotifyControl
        assertEquals("search_and_play", spotify.action)
        assertEquals("عمرو دياب", spotify.query)
    }

    @Test
    fun `test OpenAI compatible models list json parsing`() {
        val jsonStr = """
            {
              "data": [
                {"id": "nvidia/ising-calibration-1.5-31b", "object": "model"},
                {"id": "gpt-4o", "object": "model"}
              ]
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val dataArray = json.getJSONArray("data")
        val modelsList = mutableListOf<String>()

        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)
            modelsList.add(item.getString("id"))
        }

        assertEquals(2, modelsList.size)
        assertEquals("nvidia/ising-calibration-1.5-31b", modelsList[0])
        assertEquals("gpt-4o", modelsList[1])
    }

    @Test
    fun `test Gemini models list json parsing`() {
        val jsonStr = """
            {
              "models": [
                {"name": "models/gemini-3.5-flash", "version": "001"},
                {"name": "models/gemini-3.1-pro-preview", "version": "001"}
              ]
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val modelsArray = json.getJSONArray("models")
        val modelsList = mutableListOf<String>()

        for (i in 0 until modelsArray.length()) {
            val item = modelsArray.getJSONObject(i)
            val name = item.getString("name").removePrefix("models/")
            modelsList.add(name)
        }

        assertEquals(2, modelsList.size)
        assertEquals("gemini-3.5-flash", modelsList[0])
        assertEquals("gemini-3.1-pro-preview", modelsList[1])
    }
}
