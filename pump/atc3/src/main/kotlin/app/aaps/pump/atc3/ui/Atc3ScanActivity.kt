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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.extensions.safeEnable
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.comm.Atc3BtPassword
import app.aaps.pump.atc3.comm.Atc3Frame
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.history.Atc3HistorySync
import app.aaps.pump.atc3.keys.Atc3StringKey
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Pick the pump by its serial number.
 *
 * The serial is typed in first and nothing is listed until it is complete. Only a device
 * advertising itself as that exact pump, the identity prefix followed by the eight digits, is
 * shown; every other device in range is dropped without ever reaching the screen.
 *
 * This is deliberate. The pump has no pairing and no key: it accepts commands from anyone who
 * names its serial, so an address picked from a list of nearby pumps would be enough to drive a
 * stranger's pump, and the serial taken from that same list would match it. Where several pumps
 * are in range, telling them apart is left to the number printed on the pump rather than to a
 * tap on a list of near identical names.
 */
class Atc3ScanActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var blePreCheck: BlePreCheck
    @Inject lateinit var context: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var pumpSync: PumpSync
    @Inject lateinit var atc3HistorySync: Atc3HistorySync
    @Inject lateinit var atc3Pump: Atc3Pump

    private val devices = ArrayList<DeviceItem>()
    private var listAdapter: ListAdapter? = null

    // Nullable rather than lateinit: onCreate can finish early when Bluetooth permissions are
    // missing, and onDestroy still runs afterwards.
    private var listView: ListView? = null
    private var serialInput: EditText? = null
    private var passwordInput: EditText? = null
    private var emptyText: TextView? = null

    /**
     * Scanning is stopped through the very scanner instance that started it, because the adapter
     * can hand out a different instance, or none at all, once Bluetooth is toggled.
     */
    private var scanningWith: BluetoothLeScanner? = null

    private val scanCallback = PumpScanCallback(this)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    /** The serial currently typed in, or null while it is still incomplete. */
    private val enteredSerial: String?
        get() = serialInput?.text?.toString()?.trim()?.takeIf { Atc3Frame.isValidSerial(it) }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.atc3_blescanner_activity)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        title = rh.gs(R.string.atc3_scan_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        emptyText = findViewById(R.id.atc3_scanner_no_device)
        listAdapter = ListAdapter()
        listView = findViewById<ListView>(R.id.atc3_scanner_listview).apply {
            emptyView = emptyText
            adapter = listAdapter
        }
        serialInput = findViewById<EditText>(R.id.atc3_scanner_serial).apply {
            setText(preferences.get(Atc3StringKey.Atc3SerialNumber))
            addTextChangedListener(serialWatcher)
        }
        passwordInput = findViewById<EditText>(R.id.atc3_scanner_password).apply {
            setText(preferences.get(Atc3StringKey.Atc3BtPassword))
        }
        updateEmptyText()
    }

    override fun onResume() {
        super.onResume()
        // prerequisitesCheck requests the Bluetooth permissions asynchronously and returns false
        // straight away, so the screen has to stay open and try again once the user has answered.
        // Closing it here would shut the screen before the permission dialog is even acted on.
        if (!blePreCheck.prerequisitesCheck(this)) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: Bluetooth prerequisites not met yet, waiting")
            return
        }
        bluetoothAdapter?.safeEnable()
        startScan()
    }

    override fun onPause() {
        super.onPause()
        // Keep the entered password even when the screen is left without picking a device: it is a
        // credential in its own right, not tied to choosing a pump from the list.
        savePassword()
        stopScan()
    }

    /** Store the typed password, taking an empty field to mean the pump asks for none. */
    private fun savePassword() {
        val entered = passwordInput?.text?.toString()?.trim() ?: return
        if (entered.isEmpty() || Atc3BtPassword.isValid(entered)) {
            preferences.put(Atc3StringKey.Atc3BtPassword, entered)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop again here: if the scan is still registered, the system holds the callback and with
        // it this activity.
        stopScan()
        serialInput?.removeTextChangedListener(serialWatcher)
        serialInput = null
        passwordInput = null
        listView?.adapter = null
        listView = null
        listAdapter = null
        emptyText = null
        scanCallback.detach()
    }

    /** Editing the serial invalidates what is on screen: those results belong to another pump. */
    private val serialWatcher = object : TextWatcher {

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            stopScan()
            devices.clear()
            listAdapter?.notifyDataSetChanged()
            updateEmptyText()
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanningWith != null) return
        // Nothing is looked for until the pump to look for is named.
        if (enteredSerial == null) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.startScan(scanCallback)
            scanningWith = scanner
        } catch (e: IllegalStateException) {
            // Bluetooth is off, nothing to scan with. Expected, but the screen then sits there
            // finding nothing, so leave a trace of why.
            aapsLogger.debug(LTag.PUMPBTCOMM, "ATC3: cannot scan, Bluetooth is off: ${e.message}")
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission on scan", e)
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

    private fun updateEmptyText() {
        val serial = enteredSerial
        emptyText?.text =
            if (serial == null) rh.gs(R.string.atc3_scan_enter_serial)
            else rh.gs(R.string.atc3_scan_looking_for, advertisedNameFor(serial))
    }

    /**
     * Take in a scan result, keeping it only if it is the pump that was asked for.
     *
     * The advertised name is preferred over [BluetoothDevice.getName], which can answer from the
     * system's cache and so describe the device as it was called at some earlier point.
     */
    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice?, advertisedName: String?) {
        val serial = enteredSerial ?: return
        val name = advertisedName ?: try {
            device?.name
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: missing Bluetooth permission reading device name", e)
            null
        }
        if (device == null || name.isNullOrBlank()) return
        if (!matchesSerial(name, serial)) return
        val item = DeviceItem(device.address, name.trim())
        if (devices.contains(item)) return
        devices.add(item)
        runOnUiThread { listAdapter?.notifyDataSetChanged() }
    }

    private fun select(item: DeviceItem) {
        val serial = enteredSerial ?: return
        val changed = item.address != preferences.get(Atc3StringKey.Atc3Address) ||
            serial != preferences.get(Atc3StringKey.Atc3SerialNumber)
        preferences.put(Atc3StringKey.Atc3Address, item.address)
        preferences.put(Atc3StringKey.Atc3SerialNumber, serial)
        savePassword()
        if (changed) {
            // A different pump has a history of its own. AAPS has to be told, or it refuses every
            // record as belonging to the wrong pump, and the driver's ledger has to go with it, or
            // the new pump's records would be suppressed as already counted.
            aapsLogger.debug(LTag.PUMP, "ATC3: pump changed, resetting history state")
            pumpSync.connectNewPump()
            atc3HistorySync.forgetPump(serial)
            // The cached state belongs to the pump we just stopped using. Left alone, the driver
            // would keep reporting its reservoir, its profiles and its settings, and would count as
            // initialised, so AAPS would act on another pump's numbers until the first status read.
            atc3Pump.reset()
        }
        // Application context: shown right before finish(), an activity-context toast would
        // otherwise leak this activity until the toast is done showing.
        ToastUtils.okToast(applicationContext, rh.gs(R.string.atc3_scan_selected, item.name))
        finish()
    }

    private inner class ListAdapter : BaseAdapter() {

        override fun getCount(): Int = devices.size
        override fun getItem(position: Int): DeviceItem = devices[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: View.inflate(applicationContext, R.layout.atc3_blescanner_item, null)
            val item = getItem(position)
            view.findViewById<TextView>(R.id.atc3_ble_name).text = item.name
            view.findViewById<TextView>(R.id.atc3_ble_address).text = item.address
            view.setOnClickListener { select(item) }
            return view
        }
    }

    private data class DeviceItem(val address: String, val name: String)

    /**
     * Scan callback that does not keep the activity alive.
     *
     * The system's `BluetoothLeScanner` holds the registered callback from native code. An
     * anonymous callback would carry an implicit reference to the enclosing activity and leak it
     * for as long as the system keeps the registration. A weak reference that is cleared in
     * onDestroy removes that path entirely.
     */
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

        /** The name a pump with [serial] advertises: the identity block the protocol uses, as text. */
        fun advertisedNameFor(serial: String): String = Atc3Frame.IDENTITY_PREFIX + serial.trim()

        /** Whether a device calling itself [name] is the pump with [serial]. */
        fun matchesSerial(name: String, serial: String): Boolean =
            Atc3Frame.isValidSerial(serial) && name.trim() == advertisedNameFor(serial)
    }
}
