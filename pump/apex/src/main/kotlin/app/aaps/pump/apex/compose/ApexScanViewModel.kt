package app.aaps.pump.apex.compose

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.R
import app.aaps.pump.apex.command.ApexSetBtPassword
import app.aaps.pump.apex.link.ApexBleScanner
import app.aaps.pump.apex.link.ApexBtPassword
import app.aaps.pump.apex.link.ApexPairing
import app.aaps.pump.apex.protocol.ApexFrame
import app.aaps.pump.apex.state.ApexPumpState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the screen is in connecting a pump. */
enum class ApexScanStep { SERIAL, SEARCHING, FOUND, CONNECTING }

/** What the screen shows. [message] is the answer to the last thing the user did, or null. */
data class ApexScanUiState(
    val serial: String = "",
    val password: String = "",
    val step: ApexScanStep = ApexScanStep.SERIAL,
    val paired: Boolean = false,
    /** True while the new password is being written. */
    val changingPassword: Boolean = false,
    /** True when the pump's firmware has a password to change, see `ApexPumpState.canChangePassword`. */
    val canChangePassword: Boolean = false,
    val message: String? = null
) {

    val serialComplete: Boolean get() = ApexFrame.isValidSerial(serial)
    val passwordComplete: Boolean get() = ApexBtPassword.isValid(password)
}

/**
 * Connecting a pump and disconnecting it, in three steps:
 *
 * 1. The serial is typed and Find pressed: only a device that advertises that serial counts. The pump
 *    accepts anyone who names its serial, so pumps are told apart by the number printed on them.
 * 2. The pump found, its password is asked for.
 * 3. Connect, which keeps both only if the pump answers, see [ApexPairing].
 *
 * A connected pump is shown with Disconnect and with a button that gives it a new random
 * password; another pump can be connected only after Disconnect. The serial and the password of a
 * connected pump are not shown: the serial is on the driver's screen, the password is the pump's
 * own secret, shown on its status screen.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class ApexScanViewModel(
    private val preferences: Preferences,
    private val scanner: ApexBleScanner,
    private val pairing: ApexPairing,
    private val rh: ResourceHelper,
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    private val pumpState: ApexPumpState
) : ViewModel() {

    private val _state = MutableStateFlow(if (pairing.isPaired) pairedState() else ApexScanUiState())
    val state: StateFlow<ApexScanUiState> = _state.asStateFlow()

    /** The address of the pump found for the serial on screen; null until it is found. */
    private var foundAddress: String? = null
    private var searchJob: Job? = null

    private fun pairedState() = ApexScanUiState(paired = true, canChangePassword = pumpState.canChangePassword)

    init {
        // The version is read on the first link after AAPS starts: the button shows once it is known.
        pumpState.versionFlow
            .onEach { _ -> _state.update { it.copy(canChangePassword = it.paired && pumpState.canChangePassword) } }
            .launchIn(viewModelScope)
    }

    fun onSerialChanged(value: String) {
        val now = _state.value
        if (now.paired || (now.step != ApexScanStep.SERIAL && now.step != ApexScanStep.FOUND)) return
        // Another serial is another pump: the one found belongs to the old number.
        foundAddress = null
        _state.update { it.copy(serial = value.trim(), step = ApexScanStep.SERIAL, message = null) }
    }

    fun onPasswordChanged(value: String) {
        if (_state.value.step != ApexScanStep.FOUND) return
        _state.update { it.copy(password = value.filter(Char::isDigit).take(ApexBtPassword.DIGITS), message = null) }
    }

    /** Step 1: look for the pump with the serial on screen, for [PUMP_SEARCH_MS] at most. */
    fun find() {
        val now = _state.value
        if (now.step != ApexScanStep.SERIAL || !now.serialComplete || pairing.isPaired) return
        val name = advertisedNameFor(now.serial)
        _state.update { it.copy(step = ApexScanStep.SEARCHING, message = null) }
        searchJob = viewModelScope.launch {
            // Collected before the scan starts, so that an answer at once is not missed.
            val seen = launch {
                val device = scanner.scannedDevices.first { it.name.trim() == name }
                onFound(device.address, device.name)
            }
            scanner.startScan()
            if (!scanner.isScanning) {
                seen.cancel()
                _state.update { it.copy(step = ApexScanStep.SERIAL, message = rh.gs(R.string.apex_scanner_no_bluetooth)) }
                return@launch
            }
            val found = withTimeoutOrNull(PUMP_SEARCH_MS) { seen.join() } != null
            scanner.stopScan()
            if (!found) {
                seen.cancel()
                aapsLogger.debug(LTag.PUMP, "Apex: pump ${now.serial} not found nearby")
                _state.update { it.copy(step = ApexScanStep.SERIAL, message = rh.gs(R.string.apex_scanner_not_found, name)) }
            }
        }
    }

    /** Step 2: the pump is on the air, so its password is asked for. */
    private fun onFound(address: String, name: String) {
        if (_state.value.step != ApexScanStep.SEARCHING) return
        foundAddress = address
        aapsLogger.debug(LTag.PUMP, "Apex: found $name at $address")
        _state.update { it.copy(step = ApexScanStep.FOUND, password = "") }
    }

    /** Step 3: connect, keeping the serial and the password only if the pump answers. */
    fun connect() {
        val now = _state.value
        val address = foundAddress ?: return
        if (now.step != ApexScanStep.FOUND || !now.serialComplete || !now.passwordComplete) return
        _state.update { it.copy(step = ApexScanStep.CONNECTING, message = null) }
        viewModelScope.launch {
            pairing.connect(address, now.serial, now.password) { outcome, comment -> onConnectResult(outcome, comment, now.serial) }
        }
    }

    private fun onConnectResult(outcome: ApexPairing.Outcome, comment: String, serial: String) {
        val message = when (outcome) {
            ApexPairing.Outcome.CONNECTED -> rh.gs(R.string.apex_scanner_connected, advertisedNameFor(serial))
            ApexPairing.Outcome.REFUSED   -> rh.gs(R.string.apex_scanner_refused)
            ApexPairing.Outcome.NO_ANSWER -> rh.gs(R.string.apex_scanner_failed, comment.ifBlank { rh.gs(R.string.apex_not_connected) })
        }
        if (outcome == ApexPairing.Outcome.CONNECTED) {
            foundAddress = null
            _state.value = pairedState().copy(message = message)
        } else {
            // The pump found stays found: a wrong password is corrected and Connect pressed again.
            _state.update { it.copy(step = ApexScanStep.FOUND, message = message) }
        }
    }

    fun disconnect() {
        if (!pairing.isPaired) return
        if (pairing.bolusUnderWay) {
            _state.update { it.copy(message = rh.gs(R.string.apex_scanner_bolus_running)) }
            return
        }
        foundAddress = null
        viewModelScope.launch {
            pairing.disconnect()
            _state.value = ApexScanUiState(message = rh.gs(R.string.apex_scanner_disconnected))
        }
    }

    /** A new random Bluetooth password for the connected pump, in its turn on the queue, see `ApexPumpSetup.changeBtPassword`. */
    fun changePassword() {
        val now = _state.value
        if (!now.paired || now.changingPassword || !now.canChangePassword) return
        _state.update { it.copy(changingPassword = true, message = null) }
        viewModelScope.launch {
            val result = commandQueue.customCommand(ApexSetBtPassword())
            aapsLogger.debug(LTag.PUMP, "Apex: screen asked for a new Bluetooth password, ok=${result.success}")
            val message =
                if (result.success) rh.gs(R.string.apex_password_changed)
                else rh.gs(R.string.apex_password_change_failed, result.comment.ifBlank { rh.gs(R.string.apex_not_connected) })
            _state.update { it.copy(changingPassword = false, message = message) }
        }
    }

    /** What the status line says now. */
    fun statusText(state: ApexScanUiState): String = when {
        state.paired                          -> rh.gs(R.string.apex_scanner_paired)
        !state.serialComplete                 -> rh.gs(R.string.apex_scan_enter_serial)
        state.step == ApexScanStep.SEARCHING  -> rh.gs(R.string.apex_scan_looking_for, advertisedNameFor(state.serial))
        state.step == ApexScanStep.FOUND      -> rh.gs(R.string.apex_scanner_found, advertisedNameFor(state.serial))
        state.step == ApexScanStep.CONNECTING -> rh.gs(R.string.apex_scanner_connecting, advertisedNameFor(state.serial))
        else                                  -> rh.gs(R.string.apex_scanner_press_find)
    }

    override fun onCleared() {
        searchJob?.cancel()
        scanner.stopScan()
        super.onCleared()
    }

    companion object {

        /** How long Find looks for the pump: time enough to bring it close and wake its screen. */
        const val PUMP_SEARCH_MS = 30_000L

        /** The name a pump with [serial] advertises: the identity block the protocol uses, as text. */
        fun advertisedNameFor(serial: String): String = ApexFrame.IDENTITY_PREFIX + serial.trim()
    }
}
