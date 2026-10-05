package no.synth.divelog.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Collects the Subsurface cloud account email and password, prefilled with any saved
 * values. Shared by the import (Add dives) and export (Settings) flows; [title] and
 * [confirmLabel] name the action.
 */
@Composable
fun CloudCredentialsDialog(
    title: String,
    confirmLabel: String,
    initialEmail: String,
    initialPassword: String,
    onConfirm: (email: String, pass: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    var pass by remember { mutableStateOf(initialPassword) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The Subsurface cloud stores your logbook as a git repository. Sign in " +
                        "with the email and password of your Subsurface cloud account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    email, { email = it },
                    label = { Text("Subsurface cloud email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    pass, { pass = it },
                    label = { Text("Subsurface cloud password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(email.trim(), pass) },
                enabled = email.isNotBlank() && pass.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
