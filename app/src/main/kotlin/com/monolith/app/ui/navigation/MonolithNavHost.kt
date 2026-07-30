package com.monolith.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.monolith.app.R
import com.monolith.app.ui.appselector.AppSelectorScreen
import com.monolith.app.ui.home.HomeScreen
import com.monolith.app.ui.importantpeople.ImportantPeopleScreen
import com.monolith.app.ui.nfclink.NfcLinkScreen
import com.monolith.app.ui.onboarding.OnboardingCompleteScreen
import com.monolith.app.ui.onboarding.OnboardingScreen

private const val TRANSITION_DURATION_MILLIS = 300

@Composable
fun MonolithNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn(tween(TRANSITION_DURATION_MILLIS)) },
        exitTransition = { fadeOut(tween(TRANSITION_DURATION_MILLIS)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_DURATION_MILLIS)) },
        popExitTransition = { fadeOut(tween(TRANSITION_DURATION_MILLIS)) },
    ) {
        composable(MonolithDestination.Onboarding.route) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(MonolithDestination.OnboardingAppSelector.route)
                },
            )
        }
        composable(MonolithDestination.OnboardingAppSelector.route) {
            AppSelectorScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigate(MonolithDestination.OnboardingNfcLink.route) },
                onboardingStep = 1 to 2,
                onboardingSubtitle = stringResource(R.string.onboarding_select_apps_subtitle),
            )
        }
        composable(MonolithDestination.OnboardingNfcLink.route) {
            NfcLinkScreen(
                onBack = { navController.popBackStack() },
                onboardingStep = 2 to 2,
                onboardingSubtitle = stringResource(R.string.onboarding_link_tag_subtitle),
                onSkip = { navController.navigate(MonolithDestination.OnboardingComplete.route) },
                onLinked = { navController.navigate(MonolithDestination.OnboardingComplete.route) },
            )
        }
        composable(MonolithDestination.OnboardingComplete.route) {
            OnboardingCompleteScreen(
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
                onManageImportantPeople = { navController.navigate(MonolithDestination.ImportantPeople.route) },
                onLinkTag = { navController.navigate(MonolithDestination.NfcLink.route) },
            )
        }
        composable(MonolithDestination.AppSelector.route) {
            AppSelectorScreen(onBack = { navController.popBackStack() })
        }
        composable(MonolithDestination.ImportantPeople.route) {
            ImportantPeopleScreen(onBack = { navController.popBackStack() })
        }
        composable(MonolithDestination.NfcLink.route) {
            NfcLinkScreen(onBack = { navController.popBackStack() })
        }
    }
}
