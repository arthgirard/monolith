package com.monolith.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.monolith.app.ui.appselector.AppSelectorScreen
import com.monolith.app.ui.home.HomeScreen
import com.monolith.app.ui.nfclink.NfcLinkScreen
import com.monolith.app.ui.onboarding.OnboardingScreen

@Composable
fun MonolithNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(MonolithDestination.Onboarding.route) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(MonolithDestination.Home.route) {
                        popUpTo(MonolithDestination.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }
        composable(MonolithDestination.Home.route) {
            HomeScreen(
                onManageApps = { navController.navigate(MonolithDestination.AppSelector.route) },
                onLinkTag = { navController.navigate(MonolithDestination.NfcLink.route) },
            )
        }
        composable(MonolithDestination.AppSelector.route) {
            AppSelectorScreen(onBack = { navController.popBackStack() })
        }
        composable(MonolithDestination.NfcLink.route) {
            NfcLinkScreen(onBack = { navController.popBackStack() })
        }
    }
}
