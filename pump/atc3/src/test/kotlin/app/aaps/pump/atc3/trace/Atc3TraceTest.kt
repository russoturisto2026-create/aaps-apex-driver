package app.aaps.pump.atc3.trace

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import app.aaps.core.keys.interfaces.Preferences
import java.util.Locale

/**
 * The shape of a trace line, which is a contract rather than a detail.
 *
 * A report written against this format is read months after the log it reads, by someone who was
 * not there. If a value can silently carry a separator into the middle of a line, the report does
 * not fail — it quietly mis-parses, and the answer it gives is wrong in a way nobody notices. So
 * the neutralising of separators is tested, not assumed, and so is the version marker that lets an
 * old report refuse a new log out loud.
 */
class Atc3TraceTest : TestBase() {

    @Mock lateinit var logger: AAPSLogger
    @Mock lateinit var preferences: Preferences

    private lateinit var trace: Atc3Trace

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(true)
        trace = Atc3Trace(logger, preferences)
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(Locale.ENGLISH)
    }

    private fun written(): List<String> {
        val captor = argumentCaptor<String>()
        verify(logger, org.mockito.kotlin.atLeastOnce()).info(any<LTag>(), captor.capture())
        return captor.allValues
    }

    private fun fieldsOf(line: String): Map<String, String> =
        line.split('|')[7].split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun `a line carries marker, version, clock, sequence, session, category and event`() {
        trace.event(Atc3TraceCat.EXCH, "done", "what" to "read", "ok" to true)

        val parts = written().single().split('|')
        assertThat(parts[0]).isEqualTo(Atc3Trace.MARKER)
        assertThat(parts[1]).isEqualTo(Atc3Trace.VERSION.toString())
        assertThat(parts[2].toLong()).isGreaterThan(1_700_000_000_000L)
        assertThat(parts[3].toLong()).isEqualTo(1L)
        assertThat(parts[4]).matches("r[0-9a-f]{4}s0")
        assertThat(parts[5]).isEqualTo("EXCH")
        assertThat(parts[6]).isEqualTo("done")
        assertThat(parts[7]).isEqualTo("what=read ok=1")
    }

    @Test
    fun `every line of one run carries the same run, so two runs cannot be added together`() {
        // The sequence and the connection number both restart with the process, and AAPS restarts
        // often while a driver is being worked on. Without the run, connection three of one run
        // and connection three of the next look like one connection to a report.
        val other = Atc3Trace(logger, preferences)
        trace.event(Atc3TraceCat.AAPS, "bg")
        other.event(Atc3TraceCat.AAPS, "bg")

        val runs = written().map { it.split('|')[4].substringBefore('s') }
        assertThat(runs[0]).matches("r[0-9a-f]{4}")
        assertThat(runs).hasSize(2)
    }

    @Test
    fun `a line with no fields still ends after the event`() {
        trace.event(Atc3TraceCat.AAPS, "aps_done")

        assertThat(written().single().split('|')).hasSize(7)
    }

    @Test
    fun `the trace writes nothing at all when it is switched off`() {
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)

        trace.event(Atc3TraceCat.AAPS, "bg")

        verify(logger, never()).info(any<LTag>(), any<String>())
    }

    @Test
    fun `a separator inside a value cannot break the line apart`() {
        // Comments from the pump arrive as free text and reach the trace as a reason for a failure.
        trace.event(Atc3TraceCat.DRV, "tbr.end", "why" to "the pump refused|the rate=high")

        val line = written().single()
        assertThat(line.split('|')).hasSize(8)
        assertThat(fieldsOf(line)["why"]).isEqualTo("the_pump_refused_the_rate_high")
    }

    @Test
    fun `an empty or missing value is written as a dash rather than as nothing`() {
        trace.event(Atc3TraceCat.DRV, "status.end", "why" to "", "battPct" to null)

        assertThat(fieldsOf(written().single())).containsExactly("why", "-", "battPct", "-")
    }

    @Test
    fun `a dose is written with a dot whatever the phone's language is`() {
        Locale.setDefault(Locale.GERMANY)

        trace.event(Atc3TraceCat.TBR, "start", "rate" to 1.25)

        assertThat(fieldsOf(written().single())["rate"]).isEqualTo("1.250")
    }

    @Test
    fun `the sequence number never repeats, so a gap in the log is visible as a gap`() {
        trace.event(Atc3TraceCat.AAPS, "bg")
        trace.event(Atc3TraceCat.AAPS, "bg")
        trace.event(Atc3TraceCat.AAPS, "bg")

        assertThat(written().map { it.split('|')[3].toLong() }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `everything after a connection is opened belongs to that connection`() {
        trace.sessionOpen("keepalive")
        trace.event(Atc3TraceCat.EXCH, "done")

        val tags = written().map { it.split('|')[4] }
        assertThat(tags).hasSize(2)
        assertThat(tags[0]).endsWith("s1")
        assertThat(tags.toSet()).hasSize(1)
    }

    @Test
    fun `closing a connection reports what it cost`() {
        trace.sessionOpen("keepalive")
        trace.countExchange(ok = true)
        trace.countExchange(ok = false)
        trace.countOut(20)
        trace.countIn(56)

        trace.sessionClose("done")

        val fields = fieldsOf(written().last())
        assertThat(fields["reason"]).isEqualTo("done")
        assertThat(fields["exch"]).isEqualTo("2")
        assertThat(fields["failed"]).isEqualTo("1")
        assertThat(fields["out"]).isEqualTo("20")
        assertThat(fields["in"]).isEqualTo("56")
        assertThat(fields["ms"]?.toLong()).isAtLeast(0L)
    }

    @Test
    fun `a new connection does not inherit the last one's tally`() {
        trace.sessionOpen("first")
        trace.countExchange(ok = true)
        trace.sessionClose("done")

        trace.sessionOpen("second")
        trace.sessionClose("done")

        val fields = fieldsOf(written().last())
        assertThat(fields["exch"]).isEqualTo("0")
    }

    @Test
    fun `a connection that never opened is reported without a made up duration`() {
        // The link can drop before anything asked for it, and a duration measured from zero would
        // read as fifty six years of connection in the report.
        trace.sessionClose("link")

        assertThat(fieldsOf(written().single())["ms"]).isEqualTo("-1")
    }

    @Test
    fun `nothing is counted while the trace is switched off`() {
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)
        trace.countExchange(ok = true)
        trace.countOut(20)
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(true)

        trace.sessionClose("done")

        val fields = fieldsOf(written().single())
        assertThat(fields["exch"]).isEqualTo("0")
        assertThat(fields["out"]).isEqualTo("0")
    }

    /**
     * A connection with nobody else on the link reports zero, and that zero is the point.
     *
     * Another client polling the same pump in parallel would otherwise leave nothing in the log.
     * The value of this field is that its absence of a value is meaningful: a run of connections all reporting foreign=0 is the evidence that the
     * timings taken during them are timings and not upper bounds.
     */
    @Test
    fun `a connection nobody else was talking on reports no foreign answers`() {
        trace.sessionOpen("keepalive")

        trace.sessionClose("done")

        assertThat(fieldsOf(written().last())["foreign"]).isEqualTo("0")
    }

    @Test
    fun `answers nobody asked for are counted into the connection they arrived in`() {
        trace.sessionOpen("keepalive")
        trace.countForeign()
        trace.countForeign()

        trace.sessionClose("done")

        assertThat(fieldsOf(written().last())["foreign"]).isEqualTo("2")
    }

    @Test
    fun `a new connection does not inherit the last one's foreign answers`() {
        trace.sessionOpen("first")
        trace.countForeign()
        trace.sessionClose("done")

        trace.sessionOpen("second")
        trace.sessionClose("done")

        assertThat(fieldsOf(written().last())["foreign"]).isEqualTo("0")
    }

    @Test
    fun `foreign answers are not counted while the trace is switched off`() {
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)
        trace.countForeign()
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(true)

        trace.sessionClose("done")

        assertThat(fieldsOf(written().single())["foreign"]).isEqualTo("0")
    }
}
