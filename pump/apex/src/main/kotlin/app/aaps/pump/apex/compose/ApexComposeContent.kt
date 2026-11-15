package app.aaps.pump.apex.compose

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.pump.BlePreCheckHost
import app.aaps.pump.apex.R

/**
 * The Apex screen: what the pump is doing, and the way to pick which pump that is. The driver's
 * switches reach the user through the ordinary AAPS preference list, which 4.0 builds out of the
 * keys themselves.
 */
class ApexComposeContent(
    private val pluginName: String,
    private val blePreCheck: BlePreCheck
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val overviewViewModel: ApexOverviewViewModel = metroViewModel()
        val overviewState by overviewViewModel.uiState.collectAsStateWithLifecycle()
        var showScan by remember { mutableStateOf(false) }
        val scanTitle = stringResource(R.string.apex_scan_title)
        val navIcon: @Composable () -> Unit = {
            IconButton(onClick = { if (showScan) showScan = false else onNavigateBack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(app.aaps.core.ui.R.string.back)
                )
            }
        }
        // What acts on the pump belongs at the top, beside the plugin's own settings. An action with
        // no icon has nothing to show in a toolbar; the label is its description, read on a long press.
        val toolbarActions: @Composable RowScope.() -> Unit = {
            if (!showScan) {
                for (action in overviewState.primaryActions + overviewState.managementActions) {
                    val icon = action.icon
                    if (!action.visible || icon == null) continue
                    IconButton(onClick = action.onClick, enabled = action.enabled) {
                        Icon(icon, contentDescription = action.label)
                    }
                }
            }
        }
        LaunchedEffect(showScan, overviewState) {
            setToolbarConfig(
                ToolbarConfig(
                    title = if (showScan) scanTitle else pluginName,
                    navigationIcon = navIcon,
                    actions = toolbarActions
                )
            )
        }
        LaunchedEffect(overviewViewModel) {
            overviewViewModel.events.collect { event ->
                when (event) {
                    is ApexOverviewEvent.StartScan -> showScan = true
                }
            }
        }
        if (showScan) {
            // Scanning needs the radio up and the permissions granted, and asking for them here is
            // the only moment the user has a reason to say yes.
            var bleReady by remember { mutableStateOf(false) }
            if (!bleReady) {
                BlePreCheckHost(
                    blePreCheck = blePreCheck,
                    onReady = { bleReady = true },
                    onFailed = { showScan = false }
                )
            }
            if (bleReady) {
                // Stays open once the pump is connected, showing it with Disconnect; back closes it.
                val scanViewModel: ApexScanViewModel = metroViewModel()
                ApexScanScreen(viewModel = scanViewModel)
            }
        } else {
            ApexOverviewScreen(viewModel = overviewViewModel)
        }
    }
}
