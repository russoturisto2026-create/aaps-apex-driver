package app.aaps.pump.atc3.comm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The Bluetooth password, both as it is presented and as it is changed.
 *
 * The pump never sends its password; its own status screen is the only place it can be read.
 */
class Atc3BtPasswordTest {

    @Test
    fun `the password is presented as its digits, twice`() {
        assertThat(Atc3BtPassword.authValue("487613").toString(Charsets.US_ASCII)).isEqualTo("487613487613")
    }

    @Test
    fun `a pump with no password is told the password of a pump without one`() {
        assertThat(Atc3BtPassword.authValue(Atc3BtPassword.NONE).toString(Charsets.US_ASCII))
            .isEqualTo("000000000000")
    }

    @Test
    fun `anything that is not six digits is not a password`() {
        assertThat(Atc3BtPassword.isValid("48761")).isFalse()
        assertThat(Atc3BtPassword.isValid("4876134")).isFalse()
        assertThat(Atc3BtPassword.isValid("48761a")).isFalse()
        assertThat(Atc3BtPassword.isValid("")).isFalse()
        assertThrows<IllegalArgumentException> { Atc3BtPassword.authValue("48761") }
    }

    @Test
    fun `leading zeroes are part of the password, not decoration`() {
        assertThat(Atc3BtPassword.isValid("000123")).isTrue()
        assertThat(Atc3BtPassword.authValue("000123").toString(Charsets.US_ASCII)).isEqualTo("000123000123")
    }

    /**
     * Payload `05 05 03 01 04 09` sets 487613, and `07 09 06 00 00 09` sets 730473: the payload
     * digits less 65536 exactly.
     */
    @Test
    fun `the change payload is the digits of the password plus 65536`() {
        assertThat(Atc3BtPassword.changePayload(487613).toList())
            .containsExactly(5.toByte(), 5.toByte(), 3.toByte(), 1.toByte(), 4.toByte(), 9.toByte())
            .inOrder()
        assertThat(Atc3BtPassword.changePayload(730473).toList())
            .containsExactly(7.toByte(), 9.toByte(), 6.toByte(), 0.toByte(), 0.toByte(), 9.toByte())
            .inOrder()
    }

    @Test
    fun `the smallest password still fills all six payload digits`() {
        assertThat(Atc3BtPassword.changePayload(0).toList())
            .containsExactly(0.toByte(), 6.toByte(), 5.toByte(), 5.toByte(), 3.toByte(), 6.toByte())
            .inOrder()
    }

    /**
     * The payload has six decimal digits and carries the password plus 65536, so passwords above
     * 934463 have no payload at all. Asking for one would silently send the digits of something
     * else, which is exactly the kind of quiet wrong answer a pump command must not give.
     */
    @Test
    fun `a password the command cannot express is refused rather than truncated`() {
        assertThat(Atc3BtPassword.MAX_SETTABLE).isEqualTo(934_463)
        assertThat(Atc3BtPassword.isSettable(934_463)).isTrue()
        assertThat(Atc3BtPassword.isSettable(934_464)).isFalse()
        assertThat(Atc3BtPassword.isSettable(-1)).isFalse()
        assertThrows<IllegalArgumentException> { Atc3BtPassword.changePayload(934_464) }
    }

    /**
     * A change can land on the payload read literally instead of the value asked for: asking for
     * 123456 sends the digits of 188992, and the pump can end up holding 188992. So a change
     * offers the pump both in turn rather than picking one and locking the driver out when it
     * picks wrong.
     */
    @Test
    fun `a change leaves two possible passwords, the value asked for first`() {
        assertThat(Atc3BtPassword.candidatesFor(123_456)).containsExactly("123456", "188992").inOrder()
        assertThat(Atc3BtPassword.candidatesFor(487_613)).containsExactly("487613", "553149").inOrder()
        // The fallback of the largest settable password is still six digits, so it can be presented.
        assertThat(Atc3BtPassword.candidatesFor(Atc3BtPassword.MAX_SETTABLE).last()).isEqualTo("999999")
    }

    @Test
    fun `a password is shown the way the pump shows it`() {
        assertThat(Atc3BtPassword.format(123)).isEqualTo("000123")
        assertThat(Atc3BtPassword.format(934_463)).isEqualTo("934463")
    }
}
