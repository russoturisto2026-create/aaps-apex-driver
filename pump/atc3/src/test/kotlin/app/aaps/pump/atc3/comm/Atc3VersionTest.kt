package app.aaps.pump.atc3.comm

import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.CrcUtilTest.Companion.hexToBytes
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The handshake answer, object `0x31`.
 *
 * Firmware 1.1.1.0, least significant part first, and protocol 4.12.
 */
class Atc3VersionTest {

    private fun decode(hex: String): Atc3Version? =
        Atc3Version.decode(Atc3ResponseParser().feed(hexToBytes(hex))[0])

    @Test
    fun `the firmware is the four bytes the pump shows, least significant first`() {
        val version = decode("AA 10 00 A3 31 00 00 00 00 01 01 01 04 0C 0C 7E")
        assertThat(version).isNotNull()
        assertThat(version!!.firmwareText).isEqualTo("1.1.1.0")
        assertThat(version.protocolText).isEqualTo("4.12")
        assertThat(version.unknown).containsExactly(0, 0).inOrder()
    }

    @Test
    fun `firmware 1_1_1_0 is new enough to have a Bluetooth password`() {
        val version = decode("AA 10 00 A3 31 00 00 00 00 01 01 01 04 0C 0C 7E")
        assertThat(version!!.isAtLeast(Atc3Const.PASSWORD_FIRMWARE)).isTrue()
    }

    @Test
    fun `versions compare part by part rather than as text`() {
        val version = Atc3Version(listOf(1, 2, 0, 0), 4, 12, listOf(0, 0))
        assertThat(version.isAtLeast(listOf(1, 1, 1, 0))).isTrue()
        assertThat(version.isAtLeast(listOf(1, 2, 0, 0))).isTrue()
        assertThat(version.isAtLeast(listOf(1, 10, 0, 0))).isFalse()
        assertThat(version.isAtLeast(listOf(2, 0, 0, 0))).isFalse()
        // A shorter version is the same as one padded with zeroes, both ways round.
        assertThat(version.isAtLeast(listOf(1, 2))).isTrue()
        assertThat(Atc3Version(listOf(1, 1), 4, 12, listOf(0, 0)).isAtLeast(listOf(1, 1, 1, 0))).isFalse()
    }

    @Test
    fun `a frame that is not the handshake is not decoded as one`() {
        // Status V2, which has its own decoder and no version in it.
        val other = decode("AA 1C 01 A3 0C AA 01 00 00 8A 01 03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 AC 4F")
        assertThat(other).isNull()
    }
}
