package app.aaps.pump.atc3.manager

import app.aaps.pump.atc3.Atc3Const

/**
 * Tells an answer meant for the exchange in front of us from one that belongs to a finished one.
 *
 * A response frame carries no identity whatsoever: no request id, no sequence number, no serial,
 * only the shape of the answer. So when an exchange gives up on its answer and the next exchange of
 * the same shape follows it, a late reply to the first is indistinguishable from a reply to the
 * second by shape alone, and would be taken for it. That reaches the driver's most important reads
 * — the status behind every tick, and the confirmations of a temporary basal being set and
 * cancelled.
 *
 * One thing is known that shape alone does not say, and this class is it:
 *
 * **The pump never answers one request twice.**
 * So a second frame that satisfies a single-answer exchange, after that exchange already has its
 * answer, is somebody else's or a late one, and there is nothing to weigh: it is not ours. Bursts
 * are exempt by construction — they are many frames to one request by design — and they are not
 * what this guards.
 *
 * The time in a status is not used to tell a late status from a fresh one. It is the moment of the
 * pump's last status snapshot, not a clock, and nothing is decided from it here.
 *
 * The bare command acknowledgements, `0x55` and `0xA5`, are covered as well: every control
 * exchange arms as a single-answer one, and the matcher `Atc3Manager.sendControlAndWait` builds
 * accepts both the acknowledgement and the refusal,
 * so a second frame of either kind is discarded before it is read. That is wanted rather than
 * incidental — a refusal owed to an exchange that has already been answered must not set the flag
 * the exchange in front of us is about to be judged by.
 */
class Atc3AnswerGate {

    /** True while the armed exchange expects exactly one frame as its answer. */
    private var singleAnswer = false

    /** True once that one answer has arrived. */
    private var answered = false

    /** A new exchange is waiting. [singleAnswer] is false for the burst reads, which are exempt. */
    @Synchronized
    fun armed(singleAnswer: Boolean) {
        this.singleAnswer = singleAnswer
        answered = false
    }

    /** The exchange is over, one way or the other. */
    @Synchronized
    fun disarmed() {
        singleAnswer = false
        answered = false
    }

    /**
     * A new connection.
     *
     * Nothing owed on the last link is owed on this one.
     *
     * The armed shape is given up along with the answer. Dropping the link does disarm whatever was
     * waiting on it, so in the driver as it stands this changes nothing — but the class should not
     * need a caller to hold its own invariant for it.
     */
    @Synchronized
    fun connected() {
        singleAnswer = false
        answered = false
    }

    /**
     * True when a frame that satisfies the armed match is a second answer to it.
     *
     * @param matches whether the armed exchange's own matcher accepted this frame
     */
    @Synchronized
    fun isSecondAnswer(matches: Boolean): Boolean = singleAnswer && answered && matches

    /** The armed exchange has its answer. */
    @Synchronized
    fun answerAccepted() {
        answered = true
    }
}
