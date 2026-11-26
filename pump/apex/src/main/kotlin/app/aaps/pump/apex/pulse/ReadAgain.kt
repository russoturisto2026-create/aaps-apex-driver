package app.aaps.pump.apex.pulse

import app.aaps.pump.apex.exchange.ReadRule
import app.aaps.pump.apex.exchange.ReadRule.READS
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.exchange.ApexExchange
import app.aaps.pump.apex.exchange.PumpDown
import app.aaps.pump.apex.protocol.ApexJournalAnswer
import app.aaps.pump.apex.protocol.ApexRead
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
 * are asked again inside the exchange, see [ReadRule.asksAgain] and
 * `ApexExchange`. Answers of several frames (the bolus journal, the daily statistics, the basal
 * profiles) are read again here, see [journal] and [ReadRule.untilAnswered]. Waiting for our own command to
 * take is not this rule: the answer is sound there, the pump is only not done yet, see `AwaitResult`.
 *
 * The way out. One number of reads for every object, [READS]. A read is made again when it brought
 * nothing sound: no answer, a CRC that fails, a frame that does not decode or is refused, the heartbeat
 * over the answer, an answer with no record, an answer with a place whose record did not come, a record
 * that looks broken or that the pump cannot have written, see [BrokenRecord]. Then:
 * - a sound answer is taken at once;
 * - each broken record came sound at an earlier read, by its number in the journal, and the records sound
 *   in both reads are the same: the answer is taken with those records, see [patched];
 * - all [READS] reads answered and the same, byte for byte, with a broken-looking record: such a record does
 *   not flicker, it is what the pump holds, handed on marked, for the reconciliation to judge, see [BrokenRecord];
 * - the answers kept changing, or not every read was answered, or a record was missing every time: the
 *   object is not read this time, and the next heartbeat reads it again. An answer with a record missing
 *   is never taken: the daily statistics without today would make yesterday the day the next heartbeat counts from.
 *
 * An answer whose count of records is 0 is an empty journal: a state of the pump, taken at once. An
 * answer whose count says there are records, and none of them could be read, brings nothing.
 *
 * Three reads in a row that brought nothing at all (no answer, or an answer with no record read) are a pump
 * that does not work, whatever was read: a fourth read would not help, and no command may go to such a
 * pump. The pump is taken as not working, see [PumpDown]: what runs now ends as not done, a bolus or
 * a temporary basal as a command not carried out.
 */
object ReadAgain {

    /**
     * One answer of several frames, [what], read by [read] again while it brings nothing sound, see the
     * rules above. [brokenIn] gives the broken-looking records of an answer, empty when all look sound.
     * Null when it is not read this time.
     */
    fun <T> journal(
        aapsLogger: AAPSLogger, what: String, read: () -> ApexJournalAnswer<T>?, notWorking: (String) -> Unit,
        brokenIn: (ApexJournalAnswer<T>) -> List<ApexRead<T>>
    ): JournalRead<T>? {
        val earlier = ArrayList<ApexJournalAnswer<T>>()
        var last: ApexJournalAnswer<T>? = null
        var sameRun = 0
        var answered = 0
        var holed = 0
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
            if (answer.lost > 0) {
                aapsLogger.warn(LTag.PUMP, "Apex: the $what read ${attempt + 1} of $READS came with ${answer.lost} record(s) missing, read again")
                holed++
                return@repeat
            }
            answered++
            val broken = brokenIn(answer)
            if (broken.isEmpty()) return JournalRead(answer, repeatedBroken = false)
            broken.forEach { aapsLogger.warn(LTag.PUMP, "Apex: the $what answer has a record that looks broken at position ${it.frame.recordIndex}, read ${attempt + 1} of $READS: ${ApexFrame.hex(it.frame.raw)}") }
            patched(answer, earlier, brokenIn)?.let {
                aapsLogger.info(LTag.PUMP, "Apex: the $what records broken at read ${attempt + 1} came sound at an earlier read, taken from it")
                return JournalRead(it, repeatedBroken = false)
            }
            sameRun = if (last?.let { BrokenRecord.sameAnswer(it, answer) } == true) sameRun + 1 else 1
            if (sameRun >= READS) {
                aapsLogger.warn(LTag.PUMP, "Apex: the $what answer came the same $sameRun times, the broken-looking record is what the pump holds")
                return JournalRead(answer, repeatedBroken = true)
            }
            earlier += answer
            last = answer
        }
        val kept = last
        if (kept == null && holed > 0) {
            aapsLogger.error(LTag.PUMP, "Apex: the $what came with records missing and no read came whole, it is not read this time")
            return null
        }
        if (kept == null) {
            aapsLogger.warn(LTag.PUMP, "Apex: no answer to the read of the $what, it is not read this time")
            notWorking("$READS reads of the $what brought nothing")
            return null
        }
        if (answered < READS) {
            aapsLogger.error(LTag.PUMP, "Apex: the $what answers came broken and not every read was answered, it is not read this time")
            return null
        }
        aapsLogger.error(LTag.PUMP, "Apex: the $what answers kept changing and none came sound, it is not read this time")
        return null
    }

    /**
     * [answer] with each broken record taken from the newest of [earlier] reads where the record of the
     * same number came sound. Only reads that hold the same records as [answer] where both are sound: a new
     * record between the reads moves the numbers. Null when a broken record came sound at no such read.
     */
    private fun <T> patched(answer: ApexJournalAnswer<T>, earlier: List<ApexJournalAnswer<T>>, brokenIn: (ApexJournalAnswer<T>) -> List<ApexRead<T>>): ApexJournalAnswer<T>? {
        val brokenHere = brokenIn(answer).map { it.frame.recordIndex }.toSet()
        val sources = earlier.asReversed().filter { sameWhereSound(it, brokenIn(it), answer, brokenHere) }
        val taken = brokenHere.associateWith { at ->
            sources.firstNotNullOfOrNull { source ->
                val brokenThere = brokenIn(source).map { it.frame.recordIndex }
                source.entries.firstOrNull { it.frame.recordIndex == at && at !in brokenThere }
            } ?: return null
        }
        return ApexJournalAnswer(answer.entries.map { taken[it.frame.recordIndex] ?: it }, answer.recordCount, answer.lost)
    }

    /** [a] and [b] hold the same numbers, and the same bytes at every number sound in both. */
    private fun <T> sameWhereSound(a: ApexJournalAnswer<T>, brokenA: List<ApexRead<T>>, b: ApexJournalAnswer<T>, brokenB: Set<Int>): Boolean {
        val skip = brokenB + brokenA.map { it.frame.recordIndex }
        val numbersA = a.entries.map { it.frame.recordIndex }
        if (a.recordCount != b.recordCount || numbersA != b.entries.map { it.frame.recordIndex }) return false
        return a.entries.zip(b.entries).all { (x, y) -> x.frame.recordIndex in skip || x.frame.raw.contentEquals(y.frame.raw) }
    }

}
