package app.aaps.pump.apex.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.pump.apex.R

/**
 * Connecting a pump: its serial, Find, its password, Connect, under the steps written out. A
 * connected pump is shown as connected, with Disconnect and a new random password, each with what
 * it does; its serial and password are not shown. The steps are [ApexScanViewModel]'s. All text is
 * the small size of the driver's screen, and the screen scrolls, so nothing is cut off.
 */
@Composable
internal fun ApexScanScreen(viewModel: ApexScanViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .clearFocusOnTap(focusManager)
    ) {
        if (state.paired) PairedContent(viewModel, state) else PairingContent(viewModel, state)
        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = rowModifier
            )
        }
    }
}

private val rowModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)

@Composable
private fun PairedContent(viewModel: ApexScanViewModel, state: ApexScanUiState) {
    Text(text = viewModel.statusText(state), style = MaterialTheme.typography.bodySmall, modifier = rowModifier)
    OutlinedButton(onClick = viewModel::disconnect, modifier = rowModifier) {
        Text(stringResource(R.string.apex_scanner_disconnect), style = MaterialTheme.typography.bodySmall)
    }
    Summary(R.string.apex_scanner_disconnect_summary)
    // Firmware older than the password has none to change.
    if (!state.canChangePassword) return
    Button(onClick = viewModel::changePassword, enabled = !state.changingPassword, modifier = rowModifier) {
        Text(
            stringResource(if (state.changingPassword) R.string.apex_scanner_changing_password else R.string.apex_scanner_change_password),
            style = MaterialTheme.typography.bodySmall
        )
    }
    Summary(R.string.apex_scanner_change_password_summary)
}

@Composable
private fun PairingContent(viewModel: ApexScanViewModel, state: ApexScanUiState) {
    val found = state.step == ApexScanStep.FOUND || state.step == ApexScanStep.CONNECTING
    Text(text = stringResource(R.string.apex_scan_hint), style = MaterialTheme.typography.bodySmall, modifier = rowModifier)
    OutlinedTextField(
        value = state.serial,
        onValueChange = viewModel::onSerialChanged,
        enabled = state.step == ApexScanStep.SERIAL || state.step == ApexScanStep.FOUND,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(stringResource(R.string.apex_scanner_serial_hint), style = MaterialTheme.typography.bodySmall) },
        modifier = rowModifier
    )
    Button(
        onClick = viewModel::find,
        enabled = state.step == ApexScanStep.SERIAL && state.serialComplete,
        modifier = rowModifier
    ) {
        Text(
            stringResource(if (state.step == ApexScanStep.SEARCHING) R.string.apex_scanner_searching else R.string.apex_scanner_find),
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (found) {
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChanged,
            enabled = state.step == ApexScanStep.FOUND,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            label = { Text(stringResource(R.string.apex_scanner_password_hint), style = MaterialTheme.typography.bodySmall) },
            modifier = rowModifier
        )
        Button(
            onClick = viewModel::connect,
            enabled = state.step == ApexScanStep.FOUND && state.passwordComplete,
            modifier = rowModifier
        ) {
            Text(stringResource(R.string.apex_scanner_connect), style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(text = viewModel.statusText(state), style = MaterialTheme.typography.bodySmall, modifier = rowModifier)
}

/** What a button does, under it, in the colour of secondary text. */
@Composable
private fun Summary(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = rowModifier
    )
}
