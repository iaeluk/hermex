package com.iaeluk.hermex.ui.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * The Hermes connection setup: address probe, credentials, save.
 * Calls [onDone] with the saved registry id once the host is saved.
 */
@Composable
fun BotSetupRoute(
    viewModel: BotSetupViewModel,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("bots_setup"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect a Hermes host", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Talk directly to a hermes-agent dashboard, alongside your webui servers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.step == BotSetupStep.ADDRESS) {
            OutlinedTextField(
                value = state.addressText,
                onValueChange = viewModel::updateAddress,
                label = { Text("Dashboard address") },
                placeholder = { Text("https://example.com:9119") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
                isError = state.addressError != null,
                supportingText = state.addressError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().testTag("bots_setup_address"),
            )
            Button(
                onClick = { scope.launch { viewModel.probe() } },
                enabled = state.canProbe,
                modifier = Modifier.fillMaxWidth().testTag("bots_setup_probe"),
            ) {
                if (state.isWorking) CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Continue")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } else {
            Text(
                state.address ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.hostVersion?.let {
                Text("Hermes $it", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::updateUsername,
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("bots_setup_username"),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("bots_setup_password"),
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = { Text("Name (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.headersText,
                onValueChange = viewModel::updateHeaders,
                label = { Text("Connection Headers (optional)") },
                placeholder = { Text("CF-Access-Client-Id: ...") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    scope.launch {
                        if (viewModel.connectAndSave()) {
                            viewModel.savedServerId?.let(onDone)
                        }
                    }
                },
                enabled = !state.isWorking && state.username.isNotBlank() && state.password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("bots_setup_connect"),
            ) {
                if (state.isWorking) CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Connect and save")
            }
            TextButton(onClick = viewModel::backToAddress) { Text("Back") }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Start) {
            OutlinedButton(onClick = onBack) { Text("Close") }
        }
        if (state.step == BotSetupStep.DONE) {
            Text(
                "Saved.",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
