package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentAction
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
}
