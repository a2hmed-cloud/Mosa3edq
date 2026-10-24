package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.agent.AgentOrchestrator
import com.example.ai.GeminiApiClient
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import com.example.data.WeatherRepository
import com.example.speech.SpeechController
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.MainAssistantScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TasksScreen
import com.example.ui.theme.NavyBackground
import com.example.ui.theme.YaMosaedTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var database: AppDatabase
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var geminiApiClient: GeminiApiClient
    private lateinit var speechController: SpeechController
    private lateinit var agentOrchestrator: AgentOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize dependencies
        settingsRepository = SettingsRepository(applicationContext)
        database = AppDatabase.getInstance(applicationContext)
        weatherRepository = WeatherRepository()

        geminiApiClient = GeminiApiClient(
            customApiKeyProvider = {
                runBlocking {
                    try { settingsRepository.apiKeyFlow.first() } catch (e: Exception) { "" }
                }
            },
            customProviderSupplier = {
                runBlocking {
                    try { settingsRepository.providerFlow.first() } catch (e: Exception) { "gemini" }
                }
            },
            customBaseUrlSupplier = {
                runBlocking {
                    try { settingsRepository.baseUrlFlow.first() } catch (e: Exception) { "https://generativelanguage.googleapis.com" }
                }
            }
        )

        speechController = SpeechController(applicationContext)

        agentOrchestrator = AgentOrchestrator(
            context = applicationContext,
            geminiApiClient = geminiApiClient,
            speechController = speechController,
            weatherRepository = weatherRepository,
            database = database
        )

        setContent {
            YaMosaedTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = NavyBackground
                    ) {
                        val navController = rememberNavController()

                        NavHost(
                            navController = navController,
                            startDestination = "main"
                        ) {
                            composable("main") {
                                MainAssistantScreen(
                                    orchestrator = agentOrchestrator,
                                    speechController = speechController,
                                    onNavigateToSettings = { navController.navigate("settings") },
                                    onNavigateToTasks = { navController.navigate("tasks") },
                                    onNavigateToLogs = { navController.navigate("logs") },
                                    onNavigateToPermissions = { navController.navigate("permissions") }
                                )
                            }

                            composable("settings") {
                                SettingsScreen(
                                    settingsRepository = settingsRepository,
                                    geminiApiClient = geminiApiClient,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable("tasks") {
                                TasksScreen(
                                    database = database,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable("logs") {
                                LogsScreen(
                                    database = database,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable("permissions") {
                                PermissionsScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        speechController.destroy()
        super.onDestroy()
    }
}
