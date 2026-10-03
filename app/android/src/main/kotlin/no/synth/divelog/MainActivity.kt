package no.synth.divelog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import no.synth.divelog.capture.CaptureScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The full app UI arrives in M3; for now the entry point is the
            // debug capture screen used to pull fixtures off the dive computer.
            MaterialTheme {
                Scaffold { padding ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                        CaptureScreen()
                    }
                }
            }
        }
    }
}
