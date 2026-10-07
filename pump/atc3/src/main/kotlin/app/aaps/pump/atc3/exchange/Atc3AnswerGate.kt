package app.aaps.pump.atc3.exchange

/**
 * Tells the answer to the exchange in front of us from a late one. Answers carry no identity, but
 * the pump never answers one request twice: a second frame matching a single-answer exchange that
 * already has its answer is not ours. Bursts, many frames to one request, are exempt.
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

    /** A new link: nothing owed on the last one is owed on this one. */
    @Synchronized
    fun connected() {
        singleAnswer = false
        answered = false
    }

    /**
     * True when a frame that matches the armed exchange is a second answer to it.
     *
     * @param matches whether the exchange's own matcher accepted the frame
     */
    @Synchronized
    fun isSecondAnswer(matches: Boolean): Boolean = singleAnswer && answered && matches

    /** The armed exchange has its answer. */
    @Synchronized
    fun answerAccepted() {
        answered = true
    }
}
