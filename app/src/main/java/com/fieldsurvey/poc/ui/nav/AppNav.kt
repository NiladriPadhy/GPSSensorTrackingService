package com.fieldsurvey.poc.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fieldsurvey.poc.tracking.DateKeys
import com.fieldsurvey.poc.ui.home.HomeScreen
import com.fieldsurvey.poc.ui.log.LogScreen
import com.fieldsurvey.poc.ui.map.MapScreen
import com.fieldsurvey.poc.ui.settings.SettingsScreen
import com.fieldsurvey.poc.ui.whitelist.WhitelistScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val WHITELIST = "whitelist"
    const val MAP_PATTERN = "map/{date}"
    const val LOG_PATTERN = "log/{date}"
    fun map(dateKey: String) = "map/$dateKey"
    fun log(dateKey: String) = "log/$dateKey"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenMap = { date -> nav.navigate(Routes.map(date)) },
                onOpenLogs = { date -> nav.navigate(Routes.log(date)) },
                onOpenWhitelist = { nav.navigate(Routes.WHITELIST) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.WHITELIST) {
            WhitelistScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.MAP_PATTERN,
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { entry ->
            val date = entry.arguments?.getString("date") ?: DateKeys.today()
            MapScreen(dateKey = date, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.LOG_PATTERN,
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { entry ->
            val date = entry.arguments?.getString("date") ?: DateKeys.today()
            LogScreen(dateKey = date, onBack = { nav.popBackStack() })
        }
    }
}
