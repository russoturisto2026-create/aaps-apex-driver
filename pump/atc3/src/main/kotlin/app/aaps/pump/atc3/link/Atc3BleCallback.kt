package app.aaps.pump.atc3.link

/** What [Atc3BLE] tells the layer above it: the link has no knowledge of frames, nor that layer of Android Bluetooth. */
interface Atc3BleCallback {

    /** The link is set up and ready. */
    fun onConnected()

    /** The link was lost or closed: whatever waits for an answer fails now. */
    fun onDisconnected()

    /** One notification, as received; putting frames together is the layer above's. */
    fun onDataReceived(chunk: ByteArray)

    fun onSendError(reason: String)

    /** The pump refused the Bluetooth password: nothing improves by waiting. */
    fun onAuthenticationRejected()
}
