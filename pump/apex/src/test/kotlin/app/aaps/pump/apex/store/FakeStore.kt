package app.aaps.pump.apex.store

/** The store in memory, for tests of the account: the same contract as the Room one, nothing else. */
class FakeStore : ApexStore {

    private var snapshots = HashMap<Pair<String, SnapshotKind>, Snapshot>()
    private var cells = HashMap<String, MutableList<BolusCell>>()
    private var nextCellId = 1L
    private var clocks = HashMap<String, ClockOffset>()
    private var clockLogs = HashMap<String, MutableList<ClockLogEntry>>()
    private var states = HashMap<Pair<String, String>, String>()

    /** A copy of everything is kept; a block that throws puts the copy back. */
    override fun <T> transaction(block: () -> T): T {
        val keptSnapshots = HashMap(snapshots)
        val keptCells = HashMap(cells.mapValues { ArrayList(it.value) })
        val keptNextCellId = nextCellId
        val keptClocks = HashMap(clocks)
        val keptClockLogs = HashMap(clockLogs.mapValues { ArrayList(it.value) })
        val keptStates = HashMap(states)
        try {
            return block()
        } catch (e: Throwable) {
            snapshots = keptSnapshots
            cells = HashMap(keptCells.mapValues { it.value.toMutableList() })
            nextCellId = keptNextCellId
            clocks = keptClocks
            clockLogs = HashMap(keptClockLogs.mapValues { it.value.toMutableList() })
            states = keptStates
            throw e
        }
    }

    override fun snapshot(serial: String, kind: SnapshotKind): Snapshot? = snapshots[serial to kind]

    override fun saveSnapshot(serial: String, kind: SnapshotKind, snapshot: Snapshot) {
        snapshots[serial to kind] = snapshot
    }

    override fun cells(serial: String): List<BolusCell> = cells.getOrPut(serial) { ArrayList() }.sortedBy { it.id }

    override fun putCell(serial: String, cell: BolusCell): BolusCell {
        val list = cells.getOrPut(serial) { ArrayList() }
        val stored = if (cell.id == 0L) nextCellId++.let { id -> cell.copy(id = id, action = cell.action?.copy(id = id)) } else cell
        // As the unique index of the Room table: one cell per record number.
        check(stored.seq == null || list.none { it.seq == stored.seq && it.id != stored.id }) { "two cells for record ${stored.seq}" }
        list.removeAll { it.id == stored.id }
        list.add(stored)
        return stored
    }

    override fun dropCells(serial: String, ids: Collection<Long>) {
        cells[serial]?.removeAll { it.id in ids }
    }

    override fun clock(serial: String): ClockOffset? = clocks[serial]

    override fun saveClock(serial: String, offset: ClockOffset, bolusCount: Long) {
        clockLogs.getOrPut(serial) { ArrayList() }.add(ClockLogEntry(offset.writtenAtMs, clocks[serial]?.offsetMs, offset.offsetMs, offset.reason, bolusCount))
        clocks[serial] = offset
    }

    override fun clockLog(serial: String): List<ClockLogEntry> = clockLogs[serial] ?: emptyList()

    override fun pruneClockLog(serial: String, olderThanMs: Long) {
        clockLogs[serial]?.removeAll { it.writtenAtMs < olderThanMs }
    }

    override fun dropClockLogAbove(serial: String, bolusCount: Long): Int {
        val log = clockLogs[serial] ?: return 0
        val before = log.size
        log.removeAll { it.bolusCount > bolusCount }
        return before - log.size
    }

    override fun state(serial: String, key: String): String? = states[serial to key]

    override fun putState(serial: String, key: String, value: String?) {
        if (value == null) states.remove(serial to key) else states[serial to key] = value
    }

    override fun forgetPump(serial: String) {
        snapshots.keys.removeAll { it.first == serial }
        cells.remove(serial)
        clocks.remove(serial)
        clockLogs.remove(serial)
        states.keys.removeAll { it.first == serial }
    }
}
