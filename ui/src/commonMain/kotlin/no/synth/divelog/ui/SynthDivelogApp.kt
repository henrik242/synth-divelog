package no.synth.divelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.AppInfo

/** Root composable, shared across every platform. M0 renders a placeholder. */
@Composable
fun SynthDivelogApp() {
    MaterialTheme {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = AppInfo.NAME, style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Dives, sites and buddies will live here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
