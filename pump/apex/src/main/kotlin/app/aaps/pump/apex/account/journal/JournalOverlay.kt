package app.aaps.pump.apex.account.journal

/**
 * Laying an answer of the pump over the copy of its journal. Records are known by their place: the
 * pump writes them one after another and returns them newest first, whatever their stamps say.
 * The records of the answer above the place where it repeats the copy, record for record, are new;
 * nothing else is.
 */
object JournalOverlay {

    /**
     * How many of [answer]'s records, newest first, are new above [kept], the copy oldest first: the
     * fewest that leave the rest repeating the top of the copy record for record. The newest record
     * of the copy must be among them: an answer that does not show it is not laid over the copy.
     *
     * @param answer identities of the answer's records, newest first, see `ApexResponseFrame.recordIdentity`
     * @param kept identities of the copy, oldest first
     * @return the count of new records, or null when the answer cannot be laid over the copy
     */
    fun newOnTop(answer: List<ByteArray>, kept: List<ByteArray>): Int? {
        if (kept.isEmpty()) return null
        val top = kept.asReversed()
        for (newCount in answer.indices) {
            val overlap = minOf(answer.size - newCount, top.size)
            if ((0 until overlap).all { top[it].contentEquals(answer[newCount + it]) }) return newCount
        }
        return null
    }
}
