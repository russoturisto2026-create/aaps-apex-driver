package app.aaps.pump.atc3.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.extensions.safeEnable
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.link.Atc3BtPassword
import app.aaps.pump.atc3.link.Atc3Pairing
import app.aaps.pump.atc3.protocol.Atc3Frame
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * The screen a pump is connected and disconnected from, in three steps:
 *
 * 1. The serial is typed and Find pressed: only a device advertising that serial counts. The pump
 *    accepts anyone who names its serial, so pumps are told apart by the number printed on them.
 * 2. The pump found, its password is asked for.
 * 3. Connect, which keeps both only if the pump answers, see [Atc3Pairing].
 *
 * A connected pump is shown with Disconnect; another can be connected only after it.
 */
class Atc3ScanActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var blePreCheck: BlePreCheck
    @Inject lateinit var context: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var pairing: Atc3Pairing

    private enum class Step { SERIAL, SEARCHING, FOUND, CONNECTING }

    private var step = Step.SERIAL

    /** The address of the pump found for the serial on screen; null until it is found. */
    private var foundAddress: String? = null

    private val handler = Handler(Looper.getMainLooper())

    // Nullable rather than lateinit: onCreate can finish early when Bluetooth permissions are
    // missing, and onDestroy still runs afterwards.
    private var serialInput: EditText? = null
    private var findButton: Button? = null
    private var statusText: TextView? = null
    private var passwordLayout: View? = null
    private var passwordInput: EditText? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null

    /** The scanner that started the scan stops it: toggling Bluetooth can change the adapter's. */
    private var scanningWith: BluetoothLeScanner? = null

    private val scanCallback = PumpScanCallback(this)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    /** The serial currently typed in, or null while it is still incomplete. */
    private val enteredSerial: String?
        get() = serialInput?.text?.toString()?.trim()?.takeIf { Atc3Frame.isValidSerial(it) }

    /** The password currently typed in, or null while it is not yet six digits. */
    private val enteredPassword: String?
        get() = passwordInput?.text?.toString()?.trim()?.takeIf { Atc3BtPassword.isValid(it) }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.atc3_blescanner_activity)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        title = rh.gs(R.string.atc3_scan_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        statusText = findViewById(R.id.atc3_scanner_status)
        passwordLayout = findViewById(R.id.atc3_scanner_password_layout)
        // What is stored is shown only for a connected pump: nothing else is ever stored.
        val paired = pairing.isPaired
        serialInput = findViewById<EditText>(R.id.atc3_scanner_serial).apply {
            if (paired) setText(preferences.get(Atc3StringKey.Atc3SerialNumber))
            addTextChangedListener(serialWatcher)
        }
        passwordInput = findViewById<EditText>(R.id.atc3_scanner_password).apply {
            if (paired) setText(preferences.get(Atc3StringKey.Atc3BtPassword))
            addTextChangedListener(passwordWatcher)
        }
        findButton = findViewById<Button>(R.id.atc3_scanner_find).apply { setOnClickListener { find() } }
        connectButton = findViewById<Button>(R.id.atc3_scanner_connect).apply { setOnClickListener { connect() } }
        disconnectButton = findViewById<Button>(R.id.atc3_scanner_disconnect).apply { setOnClickListener { disconnect() } }
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        // The permission dialog is asked for here, so it is up before Find is pressed.
        if (!blePreCheck.prerequisitesCheck(this)) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: Bluetooth prerequisites not met yet, waiting")
            return
        }
        bluetoothAdapter?.safeEnable()
    }

    override fun onPause() {
        super.onPause()
        // A search is not carried on behind the user's back; Find starts it again.
        if (step == Step.SEARCHING) {
            handler.removeCallbacks(pumpNotFound)
            step = Step.SERIAL
            updateUi()
        }
        stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // A scan still registered would keep this activity through its callback.
        stopScan()
        serialInput?.removeTextChangedListener(serialWatcher)
        passwordInput?.removeTextChangedListener(passwordWatcher)
        serialInput = null
        findButton = null
        statusText = null
        passwordLayout = null
        passwordInput = null
        connectButton = null
        disconnectButton = null
        scanCallback.detach()
    }

    /** A different serial is a different pump: whatever was found was found for the old one. */
    private val serialWatcher = object : TextWatcher {

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (step == Step.FOUND) {
                step = Step.SERIAL
                foundAddress = null
            }
            updateUi()
        }
    }

    private val passwordWatcher = object : TextWatcher {

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = updateUi()
    }

    private fun updateUi() {
        val paired = pairing.isPaired
        val found = !paired && (step == Step.FOUND || step == Step.CONNECTING)
        serialInput?.isEnabled = !paired && (step == Step.SERIAL || step == Step.FOUND)
        findButton?.visibility = if (paired) View.GONE else View.VISIBLE
        findButton?.isEnabled = step == Step.SERIAL && enteredSerial != null
        findButton?.text = rh.gs(if (step == Step.SEARCHING) R.string.atc3_scanner_searching else R.string.atc3_scanner_find)
        passwordLayout?.visibility = if (paired || found) View.VISIBLE else View.GONE
        passwordInput?.isEnabled = !paired && step == Step.FOUND
        connectButton?.visibility = if (found) View.VISIBLE else View.GONE
        connectButton?.isEnabled = step == Step.FOUND && enteredPassword != null
        disconnectButton?.visibility = if (paired) View.VISIBLE else View.GONE
        val serial = enteredSerial
        statusText?.text = when {
            paired                    -> rh.gs(R.string.atc3_scanner_paired, advertisedNameFor(preferences.get(Atc3StringKey.Atc3SerialNumber)))
            serial == null            -> rh.gs(R.string.atc3_scan_enter_serial)
            step == Step.SEARCHING    -> rh.gs(R.string.atc3_scan_looking_for, advertisedNameFor(serial))
            step == Step.FOUND        -> rh.gs(R.string.atc3_scanner_found, advertisedNameFor(serial))
            step == Step.CONNECTING   -> rh.gs(R.string.atc3_scanner_connecting, advertisedNameFor(serial))
            else                      -> rh.gs(R.string.atc3_scanner_press_find)
        }
    }

    /** Step 1: look for the pump with the serial on screen. */
    private fun find() {
        if (step != Step.SERIAL || enteredSerial == null || pairing.isPaired) return
        if (!startScan()) {
            ToastUtils.warnToast(this, rh.gs(R.string.atc3_scanner_no_bluetooth))
            return
        }
        step = Step.SEARCHING
        handler.postDelayed(pumpNotFound, PUMP_SEARCH_MS)
        updateUi()
    }

    private val pumpNotFound = Runnable {
        stopScan()
        step = Step.SERIAL
        val serial = enteredSerial.orEmpty()
        aapsLogger.debug(LTag.PUMP, "ATC3: pump $serial not found nearby")
        ToastUtils.warnToast(this, rh.gs(R.string.atc3_scanner_not_found, advertisedNameFor(serial)))
        updateUi()
    }

    /** @return whether the scan is running */
    @SuppressLint("MissingPermission")
    private fun startScan(): Boolean {
        if (scanningWith != null) return true
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return false
        return try {
            scanner.startScan(scanCallback)
            scanningWith = scanner
            true
        } catch (e: IllegalStateException) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: cannot scan, Bluetooth is off: ${e.message}")
            false
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission on scan", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val scanner = scanningWith ?: return
        scanningWith = null
        try {
            scanner.stopScan(scanCallback)
        } catch (e: IllegalStateException) {
            // Bluetooth was switched off, so the scan is already gone. Nothing to do, but say so.
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: scan already stopped, Bluetooth is off: ${e.message}")
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission on scan stop", e)
        }
    }

    /** Take in a scan result, by its advertised name rather than the system's cached one. */
    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice?, advertisedName: String?) {
        val name = advertisedName ?: try {
            device?.name
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission reading device name", e)
            null
        }
        if (device == null || name.isNullOrBlank()) return
        val address = device.address
        // Scan results arrive on a binder thread; the screen is read and changed on its own.
        runOnUiThread { onDeviceSeen(address, name.trim()) }
    }

    /** Step 2: the pump is on the air, so its password is asked for. */
    private fun onDeviceSeen(address: String, name: String) {
        if (step != Step.SEARCHING) return
        val serial = enteredSerial ?: return
        if (!matchesSerial(name, serial)) return
        handler.removeCallbacks(pumpNotFound)
        stopScan()
        foundAddress = address
        step = Step.FOUND
        aapsLogger.debug(LTag.PUMP, "ATC3: found $name at $address")
        updateUi()
        passwordInput?.requestFocus()
    }

    /** Step 3: connect, keeping the serial and the password only if the pump answers. */
    private fun connect() {
        val address = foundAddress ?: return
        val serial = enteredSerial ?: return
        val password = enteredPassword ?: return
        if (step != Step.FOUND) return
        step = Step.CONNECTING
        updateUi()
        val screen = WeakReference(this)
        pairing.connect(address, serial, password) { outcome, comment ->
            screen.get()?.let { it.runOnUiThread { it.onConnectResult(outcome, comment, serial) } }
        }
    }

    private fun onConnectResult(outcome: Atc3Pairing.Outcome, comment: String, serial: String) {
        // The pump found stays found: a wrong password is corrected and Connect pressed again.
        step = Step.FOUND
        when (outcome) {
            Atc3Pairing.Outcome.CONNECTED -> ToastUtils.okToast(this, rh.gs(R.string.atc3_scanner_connected, advertisedNameFor(serial)))
            Atc3Pairing.Outcome.REFUSED   -> ToastUtils.warnToast(this, rh.gs(R.string.atc3_scanner_refused))
            Atc3Pairing.Outcome.NO_ANSWER -> ToastUtils.warnToast(
                this, rh.gs(R.string.atc3_scanner_failed, comment.ifBlank { rh.gs(R.string.atc3_not_connected) })
            )
        }
        if (outcome == Atc3Pairing.Outcome.CONNECTED) {
            step = Step.SERIAL
            foundAddress = null
        }
        updateUi()
    }

    private fun disconnect() {
        if (!pairing.isPaired) return
        if (pairing.bolusUnderWay) {
            ToastUtils.warnToast(this, rh.gs(R.string.atc3_scanner_bolus_running))
            return
        }
        pairing.disconnect()
        step = Step.SERIAL
        foundAddress = null
        serialInput?.setText("")
        passwordInput?.setText("")
        ToastUtils.okToast(this, rh.gs(R.string.atc3_scanner_disconnected))
        updateUi()
    }

    /** A scan callback that does not keep the activity: the system holds it from native code. */
    private class PumpScanCallback(activity: Atc3ScanActivity) : ScanCallback() {

        private var activityRef: WeakReference<Atc3ScanActivity>? = WeakReference(activity)

        fun detach() {
            activityRef?.clear()
            activityRef = null
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            activityRef?.get()?.addDevice(result.device, result.scanRecord?.deviceName)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            val activity = activityRef?.get() ?: return
            results?.forEach { activity.addDevice(it.device, it.scanRecord?.deviceName) }
        }

        override fun onScanFailed(errorCode: Int) {
            activityRef?.get()?.aapsLogger?.error(LTag.PUMPBTCOMM, "ATC3: scan failed with code $errorCode")
        }
    }

    companion object {

        /** How long Find looks for the pump: time enough to bring it close and wake its screen. */
        private const val PUMP_SEARCH_MS = 30_000L

        /** The name a pump with [serial] advertises: the identity block the protocol uses, as text. */
        fun advertisedNameFor(serial: String): String = Atc3Frame.IDENTITY_PREFIX + serial.trim()

        /** Whether a device calling itself [name] is the pump with [serial]. */
        fun matchesSerial(name: String, serial: String): Boolean =
            Atc3Frame.isValidSerial(serial) && name.trim() == advertisedNameFor(serial)
    }
}
