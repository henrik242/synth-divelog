package no.synth.divelog.ui

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import no.synth.divelog.core.logbook.reason

/** Runs [work] in the background and shows its message, or "[failure]: why", in [snackbar]. */
internal fun CoroutineScope.launchReported(snackbar: SnackbarHostState, failure: String, work: suspend () -> String?) {
    launch {
        val message = try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "$failure: ${e.reason}"
        }
        message?.let { snackbar.showSnackbar(it) }
    }
}
