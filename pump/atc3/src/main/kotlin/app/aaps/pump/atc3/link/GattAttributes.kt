package app.aaps.pump.atc3.link

import java.util.UUID

/** The Bluetooth services and characteristics of the pump: data in, data out, and the password's. */
object GattAttributes {

    const val SERVICE_NOTIFY = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_NOTIFY = "0000ffe4-0000-1000-8000-00805f9b34fb"
    const val SERVICE_WRITE = "0000ffe5-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_WRITE = "0000ffe9-0000-1000-8000-00805f9b34fb"
    const val SERVICE_AUTH = "0000ffc0-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_AUTH_WRITE = "0000ffc1-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_AUTH_NOTIFY = "0000ffc2-0000-1000-8000-00805f9b34fb"

    val serviceNotifyUuid: UUID = UUID.fromString(SERVICE_NOTIFY)
    val characteristicNotifyUuid: UUID = UUID.fromString(CHARACTERISTIC_NOTIFY)
    val serviceWriteUuid: UUID = UUID.fromString(SERVICE_WRITE)
    val characteristicWriteUuid: UUID = UUID.fromString(CHARACTERISTIC_WRITE)
    val serviceAuthUuid: UUID = UUID.fromString(SERVICE_AUTH)
    val characteristicAuthWriteUuid: UUID = UUID.fromString(CHARACTERISTIC_AUTH_WRITE)
    val characteristicAuthNotifyUuid: UUID = UUID.fromString(CHARACTERISTIC_AUTH_NOTIFY)

    val characteristicConfigDescriptor: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
