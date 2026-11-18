package app.aaps.pump.apex.account.store

/** The store in memory, for tests of the account: the same contract as the Room one, nothing else. */
class FakeStore : ApexStore {

    private var snapshots = HashMap<Pair<String, SnapshotKind>, Snapshot>()
    private var actions = HashMap<String, MutableList<Action>>()
    private var nextActionId = 1L
    private var clocks = HashMap<String, ClockOffset>()
    private var clockLogs = HashMap<String, MutableList<ClockLogEntry>>()
    private var states = HashMap<Pair<String, String>, String>()

    /** A copy of everything is kept; a block that throws puts the copy back. */
    override fun <T> transaction(block: () -> T): T {
        val keptSnapshots = HashMap(snapshots)
        val keptActions = HashMap(actions.mapValues { ArrayList(it.value) })
        val keptNextActionId = nextActionId
        val keptClocks = HashMap(clocks)
        val keptClockLogs = HashMap(clockLogs.mapValues { ArrayList(it.value) })
        val keptStates = HashMap(states)
        try {
            return block()
        } catch (e: Throwable) {
            snapshots = keptSnapshots
            actions = HashMap(keptActions.mapValues { it.value.toMutableList() })
            nextActionId = keptNextActionId
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

    override fun actions(serial: String): List<Action> = actions.getOrPut(serial) { ArrayList() }.sortedWith(compareBy({ it.acceptedAtMs }, { it.id }))

    override fun addAction(serial: String, action: Action): Action {
        val stored = action.copy(id = nextActionId++)
        actions.getOrPut(serial) { ArrayList() }.add(stored)
        return stored
    }

    override fun updateAction(serial: String, action: Action) {
        val list = actions.getOrPut(serial) { ArrayList() }
        list.removeAll { it.id == action.id }
        list.add(action)
    }

    override fun dropActions(serial: String) {
        actions[serial]?.clear()
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

    override fun state(serial: String, key: String): String? = states[serial to key]

    override fun putState(serial: String, key: String, value: String?) {
        if (value == null) states.remove(serial to key) else states[serial to key] = value
    }

    override fun forgetPump(serial: String) {
        snapshots.keys.removeAll { it.first == serial }
        actions.remove(serial)
        clocks.remove(serial)
        clockLogs.remove(serial)
        states.keys.removeAll { it.first == serial }
    }
}
