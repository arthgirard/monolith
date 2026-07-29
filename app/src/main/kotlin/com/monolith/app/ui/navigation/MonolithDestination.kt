package com.monolith.app.ui.navigation

sealed class MonolithDestination(val route: String) {
    data object Onboarding : MonolithDestination("onboarding")
    data object Home : MonolithDestination("home")
    data object AppSelector : MonolithDestination("app_selector")
    data object NfcLink : MonolithDestination("nfc_link")
}
