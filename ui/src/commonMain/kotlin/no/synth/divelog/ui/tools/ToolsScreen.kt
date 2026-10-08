package no.synth.divelog.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The calculators under the Tools tab. */
enum class Tool(val label: String, val description: String, val icon: ImageVector) {
    GAS_BLENDER("Gas blender", "Partial-pressure fill plan for nitrox and trimix", Icons.Outlined.Science),
}

/** The Tools tab: a list of [Tool]s, or the open [tool]. [blender] keeps its inputs across tab switches. */
@Composable
fun ToolsSection(tool: Tool?, onToolChange: (Tool?) -> Unit, blender: GasBlenderState) {
    when (tool) {
        Tool.GAS_BLENDER -> GasBlenderScreen(blender)
        null -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Tool.entries.forEach { t ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { onToolChange(t) }) {
                    ListItem(
                        headlineContent = { Text(t.label) },
                        supportingContent = { Text(t.description) },
                        leadingContent = { Icon(t.icon, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
