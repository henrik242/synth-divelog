package no.synth.divelog.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/** One step in a breadcrumb trail. [onClick] is null for the current (last) crumb. */
data class Crumb(val label: String, val onClick: (() -> Unit)? = null)

/**
 * A single-line breadcrumb for the top app bar, e.g. "Sites / Norway / Oslo". Earlier
 * crumbs are clickable and navigate back to that level; the last crumb is the current
 * page and is never clickable. The row scrolls horizontally when it overflows.
 */
@Composable
fun Breadcrumb(crumbs: List<Crumb>, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            val isLast = index == crumbs.lastIndex
            if (index > 0) {
                Text(
                    " / ",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val onClick = crumb.onClick
            Text(
                crumb.label,
                modifier = if (onClick != null && !isLast) Modifier.clickable(onClick = onClick) else Modifier,
                style = MaterialTheme.typography.titleLarge,
                color = if (onClick != null && !isLast) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
