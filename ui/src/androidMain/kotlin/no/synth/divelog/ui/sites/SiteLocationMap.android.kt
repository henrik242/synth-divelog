package no.synth.divelog.ui.sites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Android actual: a real MapLibre Native map. The pin sits at the centre of the
 * view (drawn as a Compose overlay), so panning and zooming the map moves the
 * pin; whatever the map settles on is written back through [onPick]. Tapping
 * recentres the map on the tapped point. Only gesture-driven moves report back,
 * so edits typed into the manual fields reposition the camera without echoing.
 */
@Composable
actual fun SiteLocationMap(
    latitude: Double?,
    longitude: Double?,
    onPick: (latitude: Double, longitude: Double) -> Unit,
    modifier: Modifier,
) {
    // Keeps the MapLibreMap handle and a "user is dragging" flag across recompositions.
    val state = remember { MapHolder() }

    Box(modifier) {
        AndroidView(
            factory = { context ->
                MapLibre.getInstance(context)
                MapView(context).also { view ->
                    view.onCreate(null)
                    view.onStart()
                    view.onResume()
                    view.getMapAsync { map ->
                        state.map = map
                        map.setStyle(Style.Builder().fromJson(SITE_MAP_STYLE_JSON))
                        val hasCoord = latitude != null && longitude != null
                        val target = LatLng(latitude ?: 0.0, longitude ?: 0.0)
                        map.cameraPosition = CameraPosition.Builder()
                            .target(target)
                            .zoom(if (hasCoord) 11.0 else 1.0)
                            .build()

                        // Only a human-driven pan/zoom should feed the text fields.
                        map.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                state.userMoving = true
                            }
                        }
                        map.addOnCameraIdleListener {
                            if (state.userMoving) {
                                state.userMoving = false
                                val c = map.cameraPosition.target
                                if (c != null) onPick(c.latitude, c.longitude)
                            }
                        }
                        // A tap recentres the pin on the tapped point.
                        map.addOnMapClickListener { point ->
                            state.userMoving = true
                            map.animateCamera(CameraUpdateFactory.newLatLng(point))
                            true
                        }
                    }
                }
            },
            update = { view ->
                // Follow manual coordinate edits without triggering a write-back.
                val map = state.map
                if (map != null && latitude != null && longitude != null) {
                    val here = map.cameraPosition.target
                    val moved = here == null ||
                        kotlin.math.abs(here.latitude - latitude) > 1e-6 ||
                        kotlin.math.abs(here.longitude - longitude) > 1e-6
                    if (moved) {
                        map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(latitude, longitude)))
                    }
                }
                view.onResume()
            },
            onRelease = { view ->
                view.onPause()
                view.onStop()
                view.onDestroy()
                state.map = null
            },
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            Icons.Outlined.Place,
            contentDescription = "Chosen location",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Mutable holder so the map callbacks and the update lambda share one map handle. */
private class MapHolder {
    var map: MapLibreMap? = null
    var userMoving: Boolean = false
}
