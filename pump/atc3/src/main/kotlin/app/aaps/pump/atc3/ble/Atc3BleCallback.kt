package app.aaps.pump.atc3.ble

/**
 * Events raised by [Atc3BLE].
 *
 * Keeping the transport behind this interface means the GATT layer has no knowledge of the ATC3
 * protocol and the protocol layer has no knowledge of Android Bluetooth, so both can be reasoned
 * about and tested separately.
 */
interface Atc3BleCallback {

    /** The pump is connected and the protocol characteristics are ready for use. */
    fun onConnected()

    /** The connection was lost or closed. Any command awaiting a response must fail now. */
    fun onDisconnected()

    /**
     * One notification payload arrived. Chunks are delivered exactly as received, reassembly into
     * frames is the responsibility of the protocol layer.
     */
    fun onDataReceived(chunk: ByteArray)

    /** Sending failed at the transport level. */
    fun onSendError(reason: String)

    /**
     * The pump refused the Bluetooth password, so the link was closed without ever being usable.
     *
     * Told apart from an ordinary failure because nothing about it improves with waiting: the
     * password is wrong until somebody reads the new one off the pump and enters it.
     */
    fun onAuthenticationRejected()
}
