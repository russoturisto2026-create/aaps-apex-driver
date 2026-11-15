package app.aaps.pump.apex.protocol

import app.aaps.pump.apex.protocol.CrcUtilTest.Companion.hexToBytes
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The handshake answer, object `0x31`: the firmware in the order the pump shows it, and the protocol.
 */
class ApexVersionTest {

    private fun decode(hex: String): ApexVersion? =
        ApexVersion.decode(ApexResponseParser().feed(hexToBytes(hex))[0])

    @Test
    fun `the firmware reads in the order the pump shows it, empty leading parts left out`() {
        val version = decode("AA 10 00 A3 31 00 00 00 00 01 01 01 04 0C 0C 7E")
        assertThat(version).isNotNull()
        assertThat(version!!.firmwareText).isEqualTo("1.1.1")
        assertThat(version.protocolText).isEqualTo("4.12")
        assertThat(version.unknown).containsExactly(0, 0).inOrder()
    }

    @Test
    fun `a firmware of two parts reads as the pump names it`() {
        // A firmware of two parts: the two bytes before them are empty.
        val version = decode("AA 10 00 A3 31 00 00 00 00 00 06 19 04 0A 30 CF")
        assertThat(version).isNotNull()
        assertThat(version!!.firmwareText).isEqualTo("6.25")
        assertThat(version.protocolText).isEqualTo("4.10")
    }

    @Test
    fun `a frame that is not the handshake is not decoded as one`() {
        // Status V2, which has its own decoder and no version in it.
        val other = decode("AA 1C 01 A3 0C AA 01 00 00 8A 01 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 AC 4F")
        assertThat(other).isNull()
    }

    private fun versionOf(major: Int, minor: Int) = ApexVersion(listOf(1, 1, 1), major, minor, listOf(0, 0))

    @Test
    fun `the protocol is at least itself`() {
        assertThat(versionOf(4, 12).protocolAtLeast(4, 12)).isTrue()
    }

    @Test
    fun `a higher minor of the same major is at least`() {
        assertThat(versionOf(4, 13).protocolAtLeast(4, 12)).isTrue()
    }

    @Test
    fun `a higher major is at least whatever the minor`() {
        assertThat(versionOf(5, 0).protocolAtLeast(4, 12)).isTrue()
    }

    @Test
    fun `a lower minor of the same major is not at least`() {
        assertThat(versionOf(4, 11).protocolAtLeast(4, 12)).isFalse()
    }

    @Test
    fun `a lower major is not at least whatever the minor`() {
        assertThat(versionOf(3, 99).protocolAtLeast(4, 12)).isFalse()
    }
}
