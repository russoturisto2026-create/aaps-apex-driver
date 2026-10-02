package app.aaps.pump.atc3.ble

import java.util.UUID

/**
 * GATT identifiers used by the ATC3 protocol.
 *
 * The protocol uses **two separate services**, which is easy to get wrong: the notify
 * characteristic and the write characteristic do not live together.
 *
 * ```
 * service 0xFFE0, handles 0x001A..0x001E
 *   characteristic 0xFFE4, value handle 0x001C, properties 0x10 NOTIFY      pump -> phone
 *     descriptor 0x2902 at handle 0x001D
 * service 0xFFE5, handles 0x001F..0x0022
 *   characteristic 0xFFE9, value handle 0x0021, properties 0x0C WRITE|WRITE_NO_RESP   phone -> pump
 * ```
 *
 * All protocol traffic runs on exactly these two handles.
 *
 * A third service carries the connection authorisation, which the pump requires before it will
 * permit the subscription above:
 *
 * ```
 * service 0xFFC0
 *   characteristic 0xFFC1, value handle 0x0041, properties 0x08 WRITE          phone -> pump
 *   characteristic 0xFFC2, value handle 0x0044, properties 0x10 NOTIFY         pump -> phone
 *     descriptor 0x2902 at handle 0x0045
 * ```
 *
 * The pump also exposes a service 0xFF90, which carries no traffic at all and is ignored.
 */
object GattAttributes {

    /** Service holding the notify characteristic. */
    const val SERVICE_NOTIFY = "0000ffe0-0000-1000-8000-00805f9b34fb"

    /** Notify characteristic, pump to phone. */
    const val CHARACTERISTIC_NOTIFY = "0000ffe4-0000-1000-8000-00805f9b34fb"

    /** Service holding the write characteristic. */
    const val SERVICE_WRITE = "0000ffe5-0000-1000-8000-00805f9b34fb"

    /** Write characteristic, phone to pump. */
    const val CHARACTERISTIC_WRITE = "0000ffe9-0000-1000-8000-00805f9b34fb"

    /** Service holding the connection authorisation characteristics. */
    const val SERVICE_AUTH = "0000ffc0-0000-1000-8000-00805f9b34fb"

    /** Authorisation characteristic, the Bluetooth password goes here. */
    const val CHARACTERISTIC_AUTH_WRITE = "0000ffc1-0000-1000-8000-00805f9b34fb"

    /** Authorisation answer, one byte: 0x00 accepted, 0x01 refused. */
    const val CHARACTERISTIC_AUTH_NOTIFY = "0000ffc2-0000-1000-8000-00805f9b34fb"

    val serviceNotifyUuid: UUID = UUID.fromString(SERVICE_NOTIFY)
    val characteristicNotifyUuid: UUID = UUID.fromString(CHARACTERISTIC_NOTIFY)
    val serviceWriteUuid: UUID = UUID.fromString(SERVICE_WRITE)
    val characteristicWriteUuid: UUID = UUID.fromString(CHARACTERISTIC_WRITE)
    val serviceAuthUuid: UUID = UUID.fromString(SERVICE_AUTH)
    val characteristicAuthWriteUuid: UUID = UUID.fromString(CHARACTERISTIC_AUTH_WRITE)
    val characteristicAuthNotifyUuid: UUID = UUID.fromString(CHARACTERISTIC_AUTH_NOTIFY)

    /** Standard client characteristic configuration descriptor. */
    val characteristicConfigDescriptor: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
