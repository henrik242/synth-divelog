package no.synth.divelog.ui.sites

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Raster tile source for the site map. OpenStreetMap standard tiles need no API
 * key. To swap in a different basemap, change this single style document (for a
 * key-bearing vector style, put the key and URL here and nowhere else).
 */
const val SITE_MAP_STYLE_JSON: String =
    """
    {
      "version": 8,
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          "tileSize": 256,
          "attribution": "(c) OpenStreetMap contributors"
        }
      },
      "layers": [
        { "id": "osm", "type": "raster", "source": "osm" }
      ]
    }
    """

/**
 * An interactive map for picking a dive-site coordinate. The user pans and zooms
 * to position a centre pin, and the chosen latitude/longitude is reported back
 * through [onPick]. [latitude]/[longitude] seed the initial camera and follow
 * later manual edits. Manual numeric entry stays the source of truth; this map
 * is an optional way to set the same two values.
 *
 * Only Android has a native map; the other platforms fall back to a panel that
 * leaves coordinate entry to the surrounding text fields.
 */
@Composable
expect fun SiteLocationMap(
    latitude: Double?,
    longitude: Double?,
    onPick: (latitude: Double, longitude: Double) -> Unit,
    modifier: Modifier = Modifier,
)
