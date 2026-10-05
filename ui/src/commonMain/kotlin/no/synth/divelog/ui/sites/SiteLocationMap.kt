package no.synth.divelog.ui.sites

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.abs
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.spatialk.geojson.Position

/**
 * Vector basemap for the site map. OpenFreeMap serves this style with no API key.
 * To swap in a different basemap, change this single style URL (for a key-bearing
 * style, put the key and URL here and nowhere else).
 */
const val SITE_MAP_STYLE_URL: String = "https://tiles.openfreemap.org/styles/liberty"

/**
 * One Compose map for dive-site coordinates, rendered natively on Android, iOS and
 * desktop. [latitude]/[longitude] seed the camera and position the pin; later edits
 * ease the camera to follow them.
 *
 * With [interactive] true (the picker) a tap reports the tapped point through
 * [onPick] and the pin moves to it; the surrounding text fields stay the source of
 * truth, so this map is one more way to set the same two values. With [interactive]
 * false the map is read-only: pan and zoom work, nothing is written back.
 */
@Composable
fun SiteLocationMap(
    latitude: Double?,
    longitude: Double?,
    onPick: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val hasCoord = latitude != null && longitude != null

    // Seed the camera once; edits are followed by the LaunchedEffect below.
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(SITE_MAP_STYLE_URL),
        initialCameraPosition = remember {
            CameraPosition(
                target = Position(latitude = latitude ?: 0.0, longitude = longitude ?: 0.0),
                zoom = if (hasCoord) 11.0 else 1.0,
            )
        },
    )

    // Keep the callback current without restarting the interaction configuration.
    val currentOnPick by rememberUpdatedState(onPick)
    val interactions = remember(interactive) {
        if (!interactive) {
            MapInteractions.Standard
        } else {
            MapInteractions(MapInteractions.Standard) {
                callbacks {
                    click {
                        onEvent { event ->
                            val pos = event.position
                            if (pos != null) currentOnPick(pos.latitude, pos.longitude)
                            ClickResult.Pass
                        }
                    }
                }
            }
        }
    }

    // Follow manual edits (and picked taps) when the coordinate leaves the camera.
    if (latitude != null && longitude != null) {
        LaunchedEffect(latitude, longitude) {
            val target = mapState.cameraPosition.target
            val moved = abs(target.latitude - latitude) > 1e-6 ||
                abs(target.longitude - longitude) > 1e-6
            if (moved) {
                mapState.animateCamera(
                    CameraUpdate(target = Position(latitude = latitude, longitude = longitude)),
                )
            }
        }
    }

    MaplibreMap(
        modifier = modifier,
        state = mapState,
        interactions = interactions,
        overlay = {
            include(MapOverlay.AttributionOnly)
            if (latitude != null && longitude != null) {
                Icon(
                    Icons.Outlined.Place,
                    contentDescription = "Dive site location",
                    tint = MaterialTheme.colorScheme.primary,
                    // The pin's tip sits at the coordinate.
                    modifier = Modifier.placedAt(
                        Position(latitude = latitude, longitude = longitude),
                        alignment = Alignment.BottomCenter,
                    ),
                )
            }
        },
    )
}
