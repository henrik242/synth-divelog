package no.synth.divelog.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A calendar; dates are UTC midnight in epoch milliseconds, as the picker uses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerFor(selectedMillis: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = selectedMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let(onPick) ?: onDismiss() },
                enabled = state.selectedDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state)
    }
}

/** A YYYY-MM-DD date typed or picked from a calendar. A blank one is valid when [optional]. */
@Composable
internal fun DateField(
    text: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        DatePickerFor(
            parseDate(text)?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds(),
            onPick = { onChange(dateOfMillis(it).toString()); picking = false },
            onDismiss = { picking = false },
        )
    }
    OutlinedTextField(
        text,
        { onChange(it.filter { c -> c.isDigit() || c == '-' }) },
        label = { Text("$label (YYYY-MM-DD)") },
        singleLine = true,
        isError = !(optional && text.isBlank()) && parseDate(text) == null,
        trailingIcon = {
            IconButton(onClick = { picking = true }) { Icon(Icons.Filled.CalendarMonth, contentDescription = "Pick a date") }
        },
        modifier = modifier,
    )
}

/** The years the date picker shows (Material 3's default); it fails on a date outside them. */
private val YEARS = 1900..2100

/** A YYYY-MM-DD date the picker can show, or null. */
internal fun parseDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim()) }.getOrNull()?.takeIf { it.year in YEARS }

/** The day of a date picker's UTC midnight in epoch milliseconds. */
internal fun dateOfMillis(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
