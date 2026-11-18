package app.aaps.pump.apex.deep

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
import app.aaps.pump.apex.pulse.JournalRead
import app.aaps.pump.apex.protocol.ApexFrame

/**
 * One rule for reading an object of the pump again, whatever the object.
 *
 * The problem. One read does not always bring what the pump holds. The Bluetooth module of the pump
 * now and then swallows an answer; an answer can fail its CRC; a frame can come that does not decode;
 * the heartbeat can lie over the answer; an answer of several frames can come with no record at all;
 * a cell of a journal can come broken once, and show its record at the next read. A number taken from
 * such a read becomes a row in AAPS, or a read that is simply dropped leaves the account a heartbeat
 * behind.
 *
 * When it applies. Every read of every object, and only reads: a command is never sent twice, a second
 * send would be a second order. Single objects (Status V1, Status V2, the last finished temporary basal)
 * are asked again inside the exchange, see [asksAgain] and
 * `ApexExchange`. Answers of several frames (the bolus journal, the daily statistics, the basal
 * profiles) are read again here, see [journal] and [untilAnswered]. Waiting for our own command to
 * take is not this rule: the answer is sound there, the pump is only not done yet, see `AwaitResult`.
 *
 * The way out. One number of reads for every object, [READS]. A read is made again when it brought
 * nothing sound: no answer, a CRC that fails, a frame that does not decode or is refused, the heartbeat
 * over the answer, an answer with no record, a record that looks broken or that the pump cannot have
 * written, see [BrokenRecordDeep] and [PlausibleRecordDeep]. Then:
 * - a sound answer is taken at once;
 * - every read answered, and the same broken-looking record every time: it is what the pump holds,
 *   handed on marked, for the reconciliation to judge, see [BrokenRecordDeep];
 * - the answers kept changing, or not every read was answered: the object is not read this time, and
 *   the next heartbeat reads it again.
 *
 * An answer whose count of records is 0 is an empty journal: a state of the pump, taken at once. An
 * answer whose count says there are records, and none of them could be read, brings nothing.
 *
 * Three reads in a row that brought nothing at all (no answer, or an answer with no record read) are a pump
 * that does not work, whatever was read: a fourth read would not help, and no command may go to such a
 * pump. The pump is taken as not working, see [PumpDownDeep]: what runs now ends as not done, a bolus or
 * a temporary basal as a command not carried out.
 */
object ReadAgainDeep {

    /** How many times one object is read in all before the driver stops trying. */
    const val READS = 3

    /** True when a single object is read again after [asked] reads: the last one brought nothing sound, [fault], and reads are left. */
    fun asksAgain(fault: Boolean, asked: Int): Boolean = fault && asked < READS

    /** True when one of [READS] asks, [ask] given the number of the ask from 1, was answered: for an answer of several frames with no records to judge. */
    fun untilAnswered(ask: (Int) -> Boolean): Boolean = (1..READS).any(ask)

    /**
     * One answer of several frames, [what], read by [read] again while it brings nothing sound, see the
     * rules above. [brokenIn] gives the first broken-looking record of an answer, null when all look sound.
     * Null when it is not read this time.
     */
    fun <T> journal(
        aapsLogger: AAPSLogger, what: String, read: () -> ApexJournalAnswer<T>?, notWorking: (String) -> Unit, brokenIn: (ApexJournalAnswer<T>) -> ApexRead<T>?
    ): JournalRead<T>? {
        var last: ApexJournalAnswer<T>? = null
        var sameEveryTime = true
        var answered = 0
        repeat(READS) { attempt ->
            val answer = read()
            if (answer != null && answer.empty) {
                aapsLogger.info(LTag.PUMP, "Apex: the $what holds no record, read ${attempt + 1} of $READS")
                return JournalRead(answer, repeatedBroken = false)
            }
            if (answer == null || answer.entries.isEmpty()) {
                aapsLogger.warn(LTag.PUMP, "Apex: the $what read ${attempt + 1} of $READS got ${if (answer == null) "no answer" else "no record it could read, taken as silence"}")
                return@repeat
            }
            answered++
            val broken = brokenIn(answer) ?: return JournalRead(answer, repeatedBroken = false)
            aapsLogger.warn(LTag.PUMP, "Apex: the $what answer has a record that looks broken at position ${broken.frame.recordIndex}, read ${attempt + 1} of $READS: ${ApexFrame.hex(broken.frame.raw)}")
            if (last != null && !BrokenRecordDeep.sameAnswer(last!!, answer)) sameEveryTime = false
            last = answer
        }
        val kept = last
        if (kept == null) {
            aapsLogger.warn(LTag.PUMP, "Apex: no answer to the read of the $what, it is not read this time")
            notWorking("$READS reads of the $what brought nothing")
            return null
        }
        if (answered < READS) {
            aapsLogger.error(LTag.PUMP, "Apex: the $what answers came broken and not every read was answered, it is not read this time")
            return null
        }
        if (sameEveryTime) {
            aapsLogger.warn(LTag.PUMP, "Apex: the $what answer came the same every time, the broken-looking record is what the pump holds")
            return JournalRead(kept, repeatedBroken = true)
        }
        aapsLogger.error(LTag.PUMP, "Apex: the $what answers kept changing and none came sound, it is not read this time")
        return null
    }

}
