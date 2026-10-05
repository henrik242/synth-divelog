package no.synth.divelog.ui.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Desktop actual: no native map here. This fallback keeps the feature usable by
 * showing the current coordinate and pointing at the manual fields; the map
 * picker itself is Android only. It never crashes and needs no tiles.
 */
@Composable
actual fun SiteLocationMap(
    latitude: Double?,
    longitude: Double?,
    onPick: (latitude: Double, longitude: Double) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Tap-to-pick map is available on Android. Enter the coordinate in the fields below.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            if (latitude != null && longitude != null) {
                Text("$latitude, $longitude", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
