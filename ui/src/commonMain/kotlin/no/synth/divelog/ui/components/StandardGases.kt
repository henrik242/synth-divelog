package no.synth.divelog.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.gas.BreathingGas
import no.synth.divelog.core.gas.STANDARD_GASES

/** One-tap chips for the standard gases after any [leading] content; [isSelected] marks the ones in use. */
@Composable
internal fun StandardGasChips(
    isSelected: (BreathingGas) -> Boolean,
    onClick: (BreathingGas) -> Unit,
    leading: @Composable () -> Unit = {},
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        leading()
        STANDARD_GASES.forEach { gas ->
            FilterChip(selected = isSelected(gas), onClick = { onClick(gas) }, label = { Text(gasLabel(gas)) })
        }
    }
}

/** "Air", "32 %", "21/35", "O2". */
private fun gasLabel(gas: BreathingGas): String = when {
    gas.o2 == 1000 -> "O2"
    gas.o2 == 210 && gas.he == 0 -> "Air"
    gas.he == 0 -> "${gas.o2 / 10} %"
    else -> "${gas.o2 / 10}/${gas.he / 10}"
}
