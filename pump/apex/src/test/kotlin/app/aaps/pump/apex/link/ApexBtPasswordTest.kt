package app.aaps.pump.apex.link

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The Bluetooth password as it is changed: the pump never sends its password, only its status screen shows it. */
class ApexBtPasswordTest {

    /** Payload `05 05 03 01 04 09` sets 487613: the payload digits less 65536 exactly. */
    @Test
    fun `the change payload is the digits of the password plus 65536`() {
        assertThat(ApexBtPassword.changePayload(487613).toList())
            .containsExactly(5.toByte(), 5.toByte(), 3.toByte(), 1.toByte(), 4.toByte(), 9.toByte())
            .inOrder()
    }

    /** Six payload digits carry the password plus 65536, so a password above 934463 has no payload. */
    @Test
    fun `a password the command cannot carry is refused rather than cut`() {
        assertThat(ApexBtPassword.MAX_SETTABLE).isEqualTo(934_463)
        assertThat(ApexBtPassword.isSettable(934_463)).isTrue()
        assertThat(ApexBtPassword.isSettable(934_464)).isFalse()
        // 000000 is the pump's way of having no password.
        assertThat(ApexBtPassword.isSettable(0)).isFalse()
        assertThrows<IllegalArgumentException> { ApexBtPassword.changePayload(934_464) }
    }

    @Test
    fun `a random password is always one the command can carry`() {
        repeat(10_000) { assertThat(ApexBtPassword.isSettable(ApexBtPassword.random())).isTrue() }
    }

    /** The pump can take the payload read literally: asking for 123456 sends the digits of 188992. */
    @Test
    fun `a change leaves two possible passwords, the value asked for first`() {
        assertThat(ApexBtPassword.candidatesFor(123_456)).containsExactly("123456", "188992").inOrder()
        assertThat(ApexBtPassword.candidatesFor(ApexBtPassword.MAX_SETTABLE).last()).isEqualTo("999999")
    }

    @Test
    fun `a password is shown the way the pump shows it`() {
        assertThat(ApexBtPassword.format(123)).isEqualTo("000123")
    }
}
