package app.aaps.pump.apex.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.BleScanner
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Finding the pump, and nothing else.
 *
 * Separate from [ApexBLE], which is about talking to a pump already chosen: this one only ever
 * reports what is advertising nearby, and it is the screen that decides which of those is the pump
 * the user meant. Scanning while connected is what a driver should not do, and one class holding
 * both would invite it.
 *
 * The advertised name is preferred over the device's own: `BluetoothDevice.getName` can answer from
 * the system's cache and so describe a device by whatever it was called at some earlier point.
 */
@SingleIn(AppScope::class)
@Inject
class ApexBleScanner(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) : BleScanner {

    private val _scannedDevices = MutableSharedFlow<ScannedDevice>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val scannedDevices: SharedFlow<ScannedDevice> = _scannedDevices

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var scanning = false

    /** Whether a scan runs: [startScan] cannot say so itself, and fails quietly with Bluetooth off. */
    val isScanning: Boolean get() = scanning

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = result.scanRecord?.deviceName
                ?: runCatching { device.name }.getOrNull()
                ?: return
            _scannedDevices.tryEmit(ScannedDevice(name = name.trim(), address = device.address))
        }

        override fun onScanFailed(errorCode: Int) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Apex: scan failed, error $errorCode")
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        if (scanning) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            aapsLogger.error(LTag.PUMPBTCOMM, "Apex: no Bluetooth to scan with")
            return
        }
        runCatching { scanner.startScan(callback) }
            .onSuccess {
                scanning = true
                aapsLogger.debug(LTag.PUMPBTCOMM, "Apex: scanning")
            }
            .onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "Apex: could not start scanning", it) }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!scanning) return
        scanning = false
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(callback) }
            .onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "Apex: could not stop scanning", it) }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Apex: stopped scanning")
    }
}
