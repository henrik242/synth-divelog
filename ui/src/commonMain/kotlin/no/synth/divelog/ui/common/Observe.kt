package no.synth.divelog.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

/**
 * The latest value of a repository [flow], which re-runs its query when the data changes.
 * [read] fetches the same value synchronously for the first frame, so a screen neither
 * flashes empty nor loses its scroll position. Both start over when [keys] change.
 */
@Composable
fun <T> observe(vararg keys: Any?, read: () -> T, flow: () -> Flow<T>): T {
    val state = remember(*keys) { mutableStateOf(read()) }
    LaunchedEffect(*keys) { flow().collect { state.value = it } }
    return state.value
}
