package com.monolith.app.ui.navigation

sealed class MonolithDestination(val route: String) {
    data object Onboarding : MonolithDestination("onboarding")
    data object OnboardingAppSelector : MonolithDestination("onboarding_app_selector")
    data object OnboardingNfcLink : MonolithDestination("onboarding_nfc_link")
    data object OnboardingStrictness : MonolithDestination("onboarding_strictness")
    data object OnboardingComplete : MonolithDestination("onboarding_complete")

    /** Permission step on its own, for installs that finished setup and later lost a permission. */
    data object Permissions : MonolithDestination("permissions")
    data object Home : MonolithDestination("home")
    data object AppSelector : MonolithDestination("app_selector")
    data object ImportantPeople : MonolithDestination("important_people")
    data object NfcLink : MonolithDestination("nfc_link")
    data object TimeSaved : MonolithDestination("time_saved")
    data object Schedules : MonolithDestination("schedules")
    data object Settings : MonolithDestination("settings")
    data object Strictness : MonolithDestination("strictness")
}
