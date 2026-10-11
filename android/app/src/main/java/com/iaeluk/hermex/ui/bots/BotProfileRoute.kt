package com.iaeluk.hermex.ui.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** Create (existing=null) or edit one bot profile. */
@Composable
fun BotProfileRoute(
    viewModel: BotProfileViewModel,
    title: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("bots_profile"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            label = { Text("Name") },
            isError = state.nameError != null,
            supportingText = state.nameError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("bots_profile_name"),
        )
        OutlinedTextField(
            value = state.description,
            onValueChange = viewModel::updateDescription,
            label = { Text("Description") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("bots_profile_description"),
        )
        OutlinedTextField(
            value = state.soul,
            onValueChange = viewModel::updateSoul,
            label = { Text("Soul / personality") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("bots_profile_soul"),
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (state.isSaving) {
            CircularProgressIndicator()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { viewModel.save() } },
                    modifier = Modifier.testTag("bots_profile_save"),
                ) { Text("Save") }
                OutlinedButton(onClick = onBack) { Text("Cancel") }
            }
        }
    }
}
