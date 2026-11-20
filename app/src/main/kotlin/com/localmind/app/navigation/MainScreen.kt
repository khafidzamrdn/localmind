package com.localmind.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.localmind.app.LocalMindApplication
import com.localmind.app.core.i18n.t
import com.localmind.app.feature.chat.ChatScreen
import com.localmind.app.feature.chat.ChatViewModel
import com.localmind.app.feature.models.ModelsScreen
import com.localmind.app.feature.models.ModelsViewModel
import com.localmind.app.feature.projects.ProjectsScreen
import com.localmind.app.feature.settings.SettingsScreen
import com.localmind.app.feature.settings.SettingsViewModel
import com.localmind.app.feature.termux.TermuxConsoleScreen
import com.localmind.app.feature.termux.TermuxConsoleViewModel

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val app = LocalContext.current.applicationContext as LocalMindApplication

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route ||
                            (destination == Destination.Settings && currentRoute == TERMUX_CONSOLE_ROUTE),
                        onClick = { navController.navigateToTab(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = t(destination.labelKey),
                            )
                        },
                        label = { Text(t(destination.labelKey)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Chat.route,
            modifier = Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .imePadding(),
            enterTransition = { fadeIn(tween(160)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(160)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable(Destination.Chat.route) {
                val factory = remember(app) {
                    viewModelFactory {
                        initializer {
                            ChatViewModel(
                                conversationRepository = app.container.conversationRepository,
                                modelRepository = app.container.modelRepository,
                                settingsRepository = app.container.settingsRepository,
                                engine = app.container.inferenceEngine,
                                controller = app.container.inferenceController,
                                termuxBridge = app.container.termuxBridge,
                            )
                        }
                    }
                }
                val chatViewModel: ChatViewModel = viewModel(factory = factory)
                ChatScreen(
                    viewModel = chatViewModel,
                    onManageModels = { navController.navigateToTab(Destination.Models) },
                )
            }

            composable(Destination.Projects.route) {
                ProjectsScreen()
            }

            composable(Destination.Models.route) {
                val factory = remember(app) {
                    viewModelFactory {
                        initializer {
                            ModelsViewModel(
                                modelRepository = app.container.modelRepository,
                                settingsRepository = app.container.settingsRepository,
                                contentResolver = app.contentResolver,
                                validator = app.container.ggufValidator,
                                estimator = app.container.resourceEstimator,
                                controller = app.container.inferenceController,
                            )
                        }
                    }
                }
                val modelsViewModel: ModelsViewModel = viewModel(factory = factory)
                ModelsScreen(viewModel = modelsViewModel)
            }

            composable(Destination.Settings.route) {
                val factory = remember(app) {
                    viewModelFactory {
                        initializer {
                            SettingsViewModel(
                                settingsRepository = app.container.settingsRepository,
                                conversationRepository = app.container.conversationRepository,
                                modelRepository = app.container.modelRepository,
                                databaseFile = app.container.databaseFile,
                                resourceEstimator = app.container.resourceEstimator,
                                controller = app.container.inferenceController,
                                termuxBridge = app.container.termuxBridge,
                            )
                        }
                    }
                }
                val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenModels = { navController.navigateToTab(Destination.Models) },
                    onOpenTermux = { navController.navigate(TERMUX_CONSOLE_ROUTE) },
                )
            }

            composable(TERMUX_CONSOLE_ROUTE) {
                val factory = remember(app) {
                    viewModelFactory {
                        initializer {
                            TermuxConsoleViewModel(bridge = app.container.termuxBridge)
                        }
                    }
                }
                val termuxViewModel: TermuxConsoleViewModel = viewModel(factory = factory)
                TermuxConsoleScreen(
                    viewModel = termuxViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private const val TERMUX_CONSOLE_ROUTE = "termux-console"
