package dev.fishit.mapper.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.fishit.mapper.android.di.AppContainer
import dev.fishit.mapper.android.di.LocalAppContainer
import dev.fishit.mapper.android.ui.capture.CaptureWebViewScreen
import dev.fishit.mapper.android.ui.projects.ProjectsScreen
import dev.fishit.mapper.android.ui.project.ProjectHomeScreen
import dev.fishit.mapper.android.ui.session.SessionDetailScreen
import dev.fishit.mapper.android.ui.settings.SettingsScreen
import dev.fishit.mapper.android.ui.settings.WebViewDiagnosticsScreen

@Composable
fun FishitApp(
    container: AppContainer,
    customTabReturnState: androidx.compose.runtime.MutableState<MainActivity.CustomTabReturn?>? = null
) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "projects") {
                    composable("projects") {
                        ProjectsScreen(
                            onOpenProject = { projectId ->
                                navController.navigate("project/${projectId}")
                            },
                            onOpenSettings = {
                                navController.navigate("settings")
                            }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenDiagnostics = {
                                navController.navigate("diagnostics")
                            }
                        )
                    }

                    composable("diagnostics") {
                        WebViewDiagnosticsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = "project/{projectId}",
                        arguments = listOf(navArgument("projectId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
                        ProjectHomeScreen(
                            projectId = projectId,
                            onBack = { navController.popBackStack() },
                            onOpenSession = { sessionId ->
                                navController.navigate("project/$projectId/session/$sessionId")
                            },
                            onOpenCapture = { pid, startUrl ->
                                val encodedUrl = java.net.URLEncoder.encode(startUrl, "UTF-8")
                                navController.navigate("capture/$pid?startUrl=$encodedUrl")
                            }
                        )
                    }

                    composable(
                        route = "project/{projectId}/session/{sessionId}",
                        arguments = listOf(
                            navArgument("projectId") { type = NavType.StringType },
                            navArgument("sessionId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
                        val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                        SessionDetailScreen(
                            projectId = projectId,
                            sessionId = sessionId,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // Capture WebView Screen für Traffic Recording
                    composable(
                        route = "capture/{projectId}?startUrl={startUrl}",
                        arguments = listOf(
                            navArgument("projectId") { type = NavType.StringType },
                            navArgument("startUrl") {
                                type = NavType.StringType
                                defaultValue = "https://"
                                nullable = true
                            }
                        )
                    ) { backStackEntry ->
                        val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
                        val startUrl = backStackEntry.arguments?.getString("startUrl")?.let {
                            java.net.URLDecoder.decode(it, "UTF-8")
                        } ?: "https://"

                        CaptureWebViewScreen(
                            startUrl = startUrl,
                            customTabReturnState = customTabReturnState,
                            onExportSession = { session ->
                                // Session wurde beendet - zurück zum Projekt
                                // TODO: Session speichern und in Sessions-Tab anzeigen
                                navController.popBackStack()
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
