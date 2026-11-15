package app.aaps.pump.apex.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.decimalPlaces
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.step
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.SliderWithButtons
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.preference.CollapsibleCardSectionContent
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewScreen
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.core.ui.compose.unitLabel
import app.aaps.core.ui.compose.valueFormat
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.R
import app.aaps.pump.apex.account.basal.ExactBasalMode
import app.aaps.pump.apex.protocol.ApexProtocol
import kotlin.math.abs

/**
 * The pump screen: the status card AAPS draws, then the driver's blocks. State and the pump are
 * always open; the statistics and the rarely used button and switch fold away. The actions go to
 * the toolbar, put there by the content around this screen, so the row along the bottom is given
 * nothing to draw. Refreshing is a pull on the screen.
 */
@Composable
internal fun ApexOverviewScreen(viewModel: ApexOverviewViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val screen by viewModel.screenState.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        PumpOverviewScreen(
            state = uiState.copy(primaryActions = emptyList(), managementActions = emptyList()),
            customContent = { ApexBlocks(viewModel, screen) }
        )
    }
}

@Composable
private fun ApexBlocks(viewModel: ApexOverviewViewModel, screen: ApexScreenState) {
    var statisticsOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    // The house section cards read their styling from a composition local that only the plugin
    // preferences screen provides by itself, so this screen provides it.
    ProvidePreferenceTheme {
        Column {
            if (screen.pumpChosen) {
                CollapsibleCardSectionContent(
                    title = TextRef.AndroidRes(R.string.apex_section_state),
                    expanded = true,
                    onToggle = {},
                    icon = Icons.Filled.BatteryStd,
                    collapsible = false
                ) { InfoRows(screen.stateRows) }
                CollapsibleCardSectionContent(
                    title = TextRef.AndroidRes(R.string.apex_section_pump),
                    expanded = true,
                    onToggle = {},
                    icon = Icons.Filled.Info,
                    collapsible = false
                ) { InfoRows(screen.pumpRows) }
                CollapsibleCardSectionContent(
                    title = TextRef.AndroidRes(R.string.apex_section_statistics),
                    summaryItems = listOf(daySummary(screen.day)),
                    expanded = statisticsOpen,
                    onToggle = { statisticsOpen = !statisticsOpen },
                    icon = Icons.Filled.BarChart
                ) { DayTableView(screen.day) }
            }
            CollapsibleCardSectionContent(
                title = TextRef.AndroidRes(R.string.apex_section_more),
                expanded = moreOpen,
                onToggle = { moreOpen = !moreOpen },
                icon = Icons.Filled.Tune
            ) { MoreContent(viewModel, screen.pumpChosen) }
        }
    }
}

/** Label on the left, value on the right, a thin line between rows: as AAPS draws its own pump rows. */
@Composable
private fun InfoRows(rows: List<PumpInfoRow>) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = statusLevelToColor(row.level),
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.2f)
                )
            }
            if (index < rows.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** What the folded statistics block says in its header: the gap of the whole day, or that there is none yet. */
private fun daySummary(day: DayTable?): TextRef {
    val pump = day?.total?.pumpUnits ?: return TextRef.AndroidRes(R.string.apex_day_account_none)
    return TextRef.AndroidRes(R.string.apex_day_summary, listOf(pump - day.total.aapsUnits, day.fromText, day.atText))
}

/**
 * Today by AAPS and by the pump, by part, at the moment of the last heartbeat. A gap of a pump step
 * or more is drawn in the warning colour, so it is seen at a glance.
 */
@Composable
private fun DayTableView(day: DayTable?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (day == null) {
            Text(stringResource(R.string.apex_day_account_none), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text(
            text = stringResource(R.string.apex_day_title, day.fromText, day.atText),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            HeaderCell("", LABEL_WEIGHT)
            HeaderCell(stringResource(R.string.apex_day_column_aaps), 1f)
            HeaderCell(stringResource(R.string.apex_day_column_pump), 1f)
            HeaderCell(stringResource(R.string.apex_day_column_difference), 1f)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DayRow(stringResource(R.string.apex_day_bolus), day.bolus, bold = false)
        DayRow(stringResource(R.string.apex_day_basal), day.basal, bold = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DayRow(stringResource(R.string.apex_day_total), day.total, bold = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AccuracyRow(day.total)
        NoteText(stringResource(R.string.apex_day_accuracy_note))
    }
}

private const val NOTE_ALPHA = 0.75f

/** An explanation under a value or a setting: smaller than the text it explains, in italics, so it reads as a note. */
@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NOTE_ALPHA)
    )
}

/**
 * How close the pump came to what AAPS counts for the day: 100 % less the gap of the total, as a
 * share of the AAPS total.
 */
@Composable
private fun AccuracyRow(total: DayLine) {
    val pump = total.pumpUnits
    val accuracy = if (pump == null || total.aapsUnits <= 0.0) null else 100.0 * (1.0 - abs(pump - total.aapsUnits) / total.aapsUnits)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.apex_day_accuracy),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = accuracy?.let { stringResource(R.string.apex_day_percent, it) } ?: stringResource(R.string.apex_value_none),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false
        )
    }
}

private const val LABEL_WEIGHT = 1.1f

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.weight(weight)
    )
}

@Composable
private fun DayRow(label: String, line: DayLine, bold: Boolean) {
    // Figures of one width, so the columns stay in line when a number changes.
    val tableNumbers = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    val none = stringResource(R.string.apex_value_none)
    val gap = line.pumpUnits?.let { it - line.aapsUnits }
    // The AAPS side is already on the pump's steps, so a real gap is a whole step; half a step is the edge.
    val apart = gap != null && abs(gap) >= ApexProtocol.DOSE_SCALE / 2
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = tableNumbers, fontWeight = weight, maxLines = 1, softWrap = false, modifier = Modifier.weight(LABEL_WEIGHT))
        Text(
            text = stringResource(R.string.apex_day_units, line.aapsUnits),
            style = tableNumbers,
            fontWeight = weight,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = line.pumpUnits?.let { stringResource(R.string.apex_day_units, it) } ?: none,
            style = tableNumbers,
            fontWeight = weight,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (gap == null) none else if (apart) stringResource(R.string.apex_day_gap, gap) else stringResource(R.string.apex_day_no_gap),
            style = tableNumbers,
            fontWeight = if (apart) FontWeight.Bold else weight,
            color = if (apart) statusLevelToColor(StatusLevel.WARNING) else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MoreContent(viewModel: ApexOverviewViewModel, pumpChosen: Boolean) {
    val exactBasal by viewModel.exactBasal.collectAsStateWithLifecycle()
    val heartbeatPeriod by viewModel.heartbeatPeriod.collectAsStateWithLifecycle()
    val pumpLimits by viewModel.pumpLimits.collectAsStateWithLifecycle()
    val exactOn = exactBasal != ExactBasalMode.OFF
    var periodOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SettingRow(
            icon = Icons.Filled.Balance,
            title = stringResource(R.string.apex_exact_basal_title),
            note = stringResource(if (exactOn) R.string.apex_exact_basal_on_note else R.string.apex_exact_basal_off_note),
            onClick = { viewModel.setExactBasal(if (exactOn) ExactBasalMode.OFF else ExactBasalMode.HEARTBEAT) }
        ) {
            Switch(checked = exactOn, onCheckedChange = { viewModel.setExactBasal(if (it) ExactBasalMode.HEARTBEAT else ExactBasalMode.OFF) })
        }
        SettingDivider()
        SettingRow(
            icon = Icons.Filled.MonitorHeart,
            title = stringResource(R.string.apex_heartbeat_period_title),
            note = null,
            onClick = { periodOpen = true }
        ) {
            Text(
                text = stringResource(R.string.apex_heartbeat_period_value, heartbeatPeriod),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        SettingDivider()
        // The same keys as the AAPS safety and loop settings: a change here is a change there. The pump follows at the next heartbeat.
        LimitSlider(Icons.Filled.Vaccines, stringResource(R.string.apex_max_bolus_title), DoubleKey.SafetyMaxBolus)
        LimitSlider(Icons.Filled.Speed, stringResource(R.string.apex_max_basal_title), DoubleKey.ApsMaxBasal)
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { LimitsNote(pumpLimits) }
        SettingDivider()
        Button(
            onClick = viewModel::setupForAaps,
            enabled = pumpChosen && viewModel.canSetUpForAaps(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(stringResource(R.string.apex_setup_for_aaps), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (periodOpen) {
        PeriodDialog(
            current = heartbeatPeriod,
            onPick = { viewModel.setHeartbeatPeriod(it); periodOpen = false },
            onDismiss = { periodOpen = false }
        )
    }
}

/** A setting: its icon, its title and its control on one line, and under the title a note set apart in smaller italics. */
@Composable
private fun SettingRow(icon: ImageVector, title: String, note: String?, onClick: () -> Unit, control: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(ICON_SIZE))
            Spacer(modifier = Modifier.width(ICON_GAP))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            control()
        }
        // The note starts under the title, not under the icon.
        if (note != null) Column(modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP)) { NoteText(note) }
    }
}

private val ICON_SIZE = 24.dp
private val ICON_GAP = 16.dp

@Composable
private fun SettingDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * A limit of AAPS as a slider, the way AAPS shows its own: the value is AAPS's preference itself.
 * The slider ends at [PUMP_LIMIT_MAX], above which the pump takes no limit; a higher value AAPS
 * already holds stays reachable.
 */
@Composable
private fun LimitSlider(icon: ImageVector, title: String, key: DoubleKey) {
    val preferences = LocalPreferences.current
    var value by remember { mutableDoubleStateOf(preferences.get(key)) }
    val unitType = key.unitType
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(ICON_SIZE))
            Spacer(modifier = Modifier.width(ICON_GAP))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        SliderWithButtons(
            value = value,
            onValueChange = { preferences.put(key, it); value = it },
            valueRange = key.min..maxOf(PUMP_LIMIT_MAX, value),
            step = unitType.step(),
            showValue = true,
            valueFormatRef = unitType.valueFormat(),
            valueFormat = NumberFormat.withDecimals(unitType.decimalPlaces()),
            unitLabel = unitType.unitLabel(),
            dialogLabel = title
        )
    }
}

/** The heartbeat periods to pick from. */
@Composable
private fun PeriodDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apex_heartbeat_period_title)) },
        text = {
            Column {
                ApexConst.HEARTBEAT_PERIODS.forEach { minutes ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(minutes) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = minutes == current, onClick = { onPick(minutes) })
                        Text(stringResource(R.string.apex_heartbeat_period_value, minutes))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

/** The highest maximum bolus and maximum basal the pump takes, U and U/h. */
private const val PUMP_LIMIT_MAX = 25.0

/** What the pump holds against AAPS's maximum bolus and basal. */
@Composable
private fun LimitsNote(pump: Pair<Double, Double>?) {
    val preferences = LocalPreferences.current
    val bolus = preferences.get(DoubleKey.SafetyMaxBolus)
    val basal = preferences.get(DoubleKey.ApsMaxBasal)
    val text = when {
        pump == null -> stringResource(R.string.apex_limits_note_unknown)
        ApexProtocol.sameDose(pump.first, bolus) && ApexProtocol.sameDose(pump.second, basal) -> stringResource(R.string.apex_limits_note_same, pump.first, pump.second)
        else -> stringResource(R.string.apex_limits_note_differ, pump.first, pump.second)
    }
    NoteText(text)
}
