package no.synth.divelog.ui.common

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Handle the system back gesture. Thin wrapper over [NavigationBackHandler] so call
 * sites stay a one-liner; we don't track predictive-back progress, so the state is a
 * single [NavigationEventInfo.None] and only completion fires [onBack].
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}
