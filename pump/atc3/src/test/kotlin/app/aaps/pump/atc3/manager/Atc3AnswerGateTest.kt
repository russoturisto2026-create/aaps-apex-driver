package app.aaps.pump.atc3.manager

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * An answer to an exchange that already gave up, taken for the answer to the next one.
 *
 * A response frame carries no request id, no sequence number, nothing that says which request it
 * belongs to. So when a status read times out and the driver asks again, the first pump's reply
 * arriving a moment later is, by shape, a perfectly good answer to the second question — and it is
 * accepted as one. That reaches the status behind every tick and the confirmations of a temporary
 * basal being set and cancelled, which is why the fact pinned down here is worth a class.
 *
 * The fact: **the pump never answers one request twice**, so a second frame satisfying an exchange
 * that already has its answer is somebody else's. The time in a status is not used for this: it is
 * the moment of the pump's last status snapshot, not a clock.
 *
 * The exemption these tests exist to keep intact: bursts are many frames to one request by design,
 * so the rule must never fire on them — it would break every history read.
 */
class Atc3AnswerGateTest {

    /** Whatever the armed exchange's matcher would say about the frame in hand. */
    private val matches = true
    private val doesNotMatch = false

    // The pump never answers one request twice

    @Test
    fun `the first frame of a single-answer exchange is the answer`() {
        val gate = Atc3AnswerGate()
        gate.armed(singleAnswer = true)
        assertThat(gate.isSecondAnswer(matches)).isFalse()
    }

    @Test
    fun `a second matching frame after the answer is not ours`() {
        // The status read that timed out is answered late, while the retry already has its answer.
        val gate = Atc3AnswerGate()
        gate.armed(singleAnswer = true)
        gate.answerAccepted()
        assertThat(gate.isSecondAnswer(matches)).isTrue()
    }

    @Test
    fun `a frame the exchange was not waiting for is not a second answer`() {
        // Alarms and the heartbeat arrive in the middle of anything. They answer nothing, so they
        // are nobody's second answer either, and this gate must let them through untouched.
        val gate = Atc3AnswerGate()
        gate.armed(singleAnswer = true)
        gate.answerAccepted()
        assertThat(gate.isSecondAnswer(doesNotMatch)).isFalse()
    }

    @Test
    fun `with nothing armed nothing is a second answer`() {
        assertThat(Atc3AnswerGate().isSecondAnswer(matches)).isFalse()
    }

    @Test
    fun `a burst answers one request with many frames and every one of them counts`() {
        // History reads, the bolus search, the profile sweep: the pump sends frame after frame to
        // one question. If this ever returned true the driver would discard all but the first and
        // every history read would come back a single record long.
        val gate = Atc3AnswerGate()
        gate.armed(singleAnswer = false)
        gate.answerAccepted()
        assertThat(gate.isSecondAnswer(matches)).isFalse()
        gate.answerAccepted()
        assertThat(gate.isSecondAnswer(matches)).isFalse()
    }

    @Test
    fun `the end of an exchange clears the answer it had`() {
        val gate = Atc3AnswerGate()
        gate.armed(singleAnswer = true)
        gate.answerAccepted()
        gate.disarmed()
        gate.armed(singleAnswer = true)
        assertThat(gate.isSecondAnswer(matches)).isFalse()
    }

    @Test
    fun `arming again clears it as well, without a disarm in between`() {
        val gate = Atc3AnswerGate()
        gate.armed(singleAnswer = true)
        gate.answerAccepted()
        gate.armed(singleAnswer = true)
        assertThat(gate.isSecondAnswer(matches)).isFalse()
    }

    @Test
    fun `a disarmed gate reports nothing at all`() {
        val gate = Atc3AnswerGate()
        gate.armed(singleAnswer = true)
        gate.answerAccepted()
        gate.disarmed()
        assertThat(gate.isSecondAnswer(matches)).isFalse()
    }

    @Test
    fun `a new connection also clears an answer owed on the old link`() {
        // The exchange that was waiting when the link dropped is not waiting on this one.
        val gate = Atc3AnswerGate()
        gate.armed(singleAnswer = true)
        gate.answerAccepted()
        gate.connected()
        assertThat(gate.isSecondAnswer(matches)).isFalse()
        // The armed shape goes with it, so nothing arriving before the next exchange is armed can
        // be anybody's second answer. The driver disarms on a dropped link and would never show
        // this, but the class must not lean on a caller to hold its own invariant.
        gate.answerAccepted()
        assertThat(gate.isSecondAnswer(matches)).isFalse()
    }
}
