package app.aaps.pump.apex.state

import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.protocol.ApexStamp
import app.aaps.pump.apex.protocol.ApexVersion
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A pump can have its Bluetooth password changed from the password protocol on. */
class ApexPumpStatePasswordTest {

    /** Setting a version sets the form of the stamps for the whole run; leave it plain for the next test. */
    @AfterEach
    fun plainStamps() {
        ApexStamp.digits = false
    }

    private val state = ApexPumpState()

    private fun versionOf(minor: Int) = ApexVersion(listOf(1, 1, 1), ApexConst.PASSWORD_PROTOCOL_MAJOR, minor, listOf(0, 0))

    @Test
    fun `while the version is not known the password cannot be changed`() {
        assertThat(state.canChangePassword).isFalse()
    }

    @Test
    fun `at the password protocol the password can be changed`() {
        state.version = versionOf(ApexConst.PASSWORD_PROTOCOL_MINOR)
        assertThat(state.canChangePassword).isTrue()
    }

    @Test
    fun `one minor below the password protocol the password cannot be changed`() {
        state.version = versionOf(ApexConst.PASSWORD_PROTOCOL_MINOR - 1)
        assertThat(state.canChangePassword).isFalse()
    }
}
