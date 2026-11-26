package app.aaps.pump.apex.exchange

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat

/**
 * The pump taken as not working.
 *
 * The problem. A pump can stop answering while the link looks alive: the stack may lose a link
 * without reporting it, the pump may hang, or it may be out of range. Or it answers, but every
 * answer is broken (a bad CRC, a frame that does not decode), and may stay so. Requests sent then
 * bring nothing usable, and the driver would go on sending them without end: a bolus after a read
 * that brought nothing, a second and a third ask of every object, a heartbeat that waits on each
 * one. A command sent on such a link is a command whose fate nobody knows.
 *
 * When it applies. Three requests in a row with no usable answer: no answer, or an answer that
 * cannot be read, whether the link was up or not (a request that cannot even be sent counts too),
 * see [noteReachability]; or three reads of one object that brought nothing sound, see [ReadRule].
 * An answer the pump's heartbeat lay over is not counted: the heartbeat shows the pump is there.
 *
 * The way out. The pump is taken as not working: the link is dropped first, then every request is
 * refused until the pump comes back, and what runs now ends as not done. The user is told once,
 * when the pump is taken as not working; the notice goes only when a heartbeat is reconciled in full,
 * see [onReconciled]. Not with the first usable answer: on a new link that is the answer to the
 * heartbeat period, and a pump with one object that keeps answering broken would make the notice come
 * and go at every link. The broken answers are in the log with their bytes, so that what happens can be seen. The links do not come up by
 * themselves, only a new connect brings one: the next heartbeat watch, a command of the loop, a
 * reconnection. The pump is back with its heartbeat frame, or with a new link.
 */
class PumpDown(
    private val aapsLogger: AAPSLogger,
    private val trace: ApexTrace,
    /** Drops the link, for the reason given. */
    private val dropLink: (String) -> Unit,
    /** Tells the user: true when the pump is taken as not working, false at the first full reconciliation after it. */
    private val tellUser: (Boolean) -> Unit = {}
) {

    /** The pump is taken as not working: no request goes to it. */
    @Volatile var down: Boolean = false
        private set

    /** Requests in a row with no usable answer, see [noteReachability]. */
    @Volatile private var unreachable = 0

    /** The user was told the pump gives no usable answer, and no heartbeat was reconciled in full since. */
    @Volatile private var told = false

    /**
     * One request got a usable answer, [reached], or not: no answer, or one that cannot be read; [why] is
     * how it ended. After [UNREACHABLE_BEFORE_DOWN] in a row with no usable answer the pump is taken as
     * not working. Counted by request, so that the asks again of one read count too.
     *
     * @return true when the pump was taken as not working now
     */
    fun noteReachability(reached: Boolean, why: String): Boolean {
        if (reached) {
            unreachable = 0
            return false
        }
        unreachable++
        if (unreachable < UNREACHABLE_BEFORE_DOWN) return false
        val run = unreachable
        aapsLogger.error(LTag.PUMPCOMM, "Apex: $run requests in a row got no usable answer, the last one $why")
        trace.event(ApexTraceCat.BLE, "link_dead", "after" to run, "why" to why)
        notWorking("no usable answer to $run requests, the last one $why")
        return true
    }

    /** Three reads in a row brought nothing, [why]: the link is dropped, and nothing more is asked until the pump comes back. */
    fun notWorking(why: String) {
        if (!down) {
            aapsLogger.error(LTag.PUMPCOMM, "Apex: the pump is taken as not working, $why; nothing more is asked until its heartbeat or a new link")
            trace.event(ApexTraceCat.BLE, "pump_down", "why" to why)
            if (!told) {
                told = true
                tellUser(true)
            }
        }
        // The link first: a heartbeat still on its way over it must not bring the pump back.
        dropLink(why)
        down = true
        unreachable = 0
    }

    /** A heartbeat was reconciled in full: the pump works, and the notice that it gave no usable answer goes. */
    fun onReconciled() {
        if (!told) return
        told = false
        tellUser(false)
    }

    /** The pump's heartbeat frame came: requests go to it again. */
    fun onHeartbeat() = back("its heartbeat came")

    /** A new link: requests go to the pump again, and what went unanswered on the last link says nothing of this one. */
    fun onLinkUp() {
        back("a new link")
        unreachable = 0
    }

    private fun back(how: String) {
        if (!down) return
        down = false
        aapsLogger.info(LTag.PUMPCOMM, "Apex: the pump is back, $how; requests go to it again")
        trace.event(ApexTraceCat.BLE, "pump_back", "how" to how)
    }

    companion object {

        /** Requests in a row that reach nothing before the pump is taken as not working: one can be a busy stack. */
        const val UNREACHABLE_BEFORE_DOWN = 3
    }
}
