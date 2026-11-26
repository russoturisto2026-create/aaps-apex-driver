package app.aaps.pump.apex.protocol

/** The pump's firmware and protocol versions. */
data class ApexVersion(
    /** The firmware as the pump shows it, most significant part first, without the empty leading parts. */
    val firmware: List<Int>,
    val protocolMajor: Int,
    val protocolMinor: Int,
    /** Two bytes not decoded, kept for the log. */
    val unknown: List<Int>
) {

    val firmwareText: String get() = firmware.joinToString(".")

    val protocolText: String get() = "$protocolMajor.$protocolMinor"

    /** Whether the protocol is [major].[minor] or newer. */
    fun protocolAtLeast(major: Int, minor: Int): Boolean =
        protocolMajor > major || (protocolMajor == major && protocolMinor >= minor)

    /**
     * True when the driver works with this pump: protocol 4.10 or newer. From it on the pump gives the
     * daily statistics and the temporary basal marks, and the account rests on them.
     */
    val supported: Boolean get() = protocolAtLeast(4, 10)

    /**
     * True when the pump answers Status V2 (0x0C): protocol 4.11 or newer. A 4.10 pump never answers it,
     * so it is not asked; its battery comes from Status V1, in quarters.
     */
    val hasStatusV2: Boolean get() = protocolAtLeast(4, 11)

    /** True when the pump writes the stamps of its records as decimal digits: protocol 4.10. A newer one writes them plain. See [ApexStamp]. */
    val stampsInDigits: Boolean get() = !protocolAtLeast(4, 11)

    internal object Offset {

        const val UNKNOWN = 2
        const val FIRMWARE = 4
        const val FIRMWARE_PARTS = 4
        const val PROTOCOL = 8
    }

    companion object {

        fun decode(frame: ApexResponseFrame): ApexVersion? {
            if (frame.objectType != ApexProtocol.ReadOpcode.VERSION) return null
            if (!frame.has(Offset.PROTOCOL, 2)) return null
            return ApexVersion(
                // The parts stand in the order the pump shows them; a part of zero before the first other one is no part.
                firmware = (0 until Offset.FIRMWARE_PARTS).map { frame.byteAt(Offset.FIRMWARE + it) }.dropWhile { it == 0 },
                protocolMajor = frame.byteAt(Offset.PROTOCOL),
                protocolMinor = frame.byteAt(Offset.PROTOCOL + 1),
                unknown = (0 until Offset.FIRMWARE - Offset.UNKNOWN)
                    .map { frame.byteAt(Offset.UNKNOWN + it) }
            )
        }
    }
}
