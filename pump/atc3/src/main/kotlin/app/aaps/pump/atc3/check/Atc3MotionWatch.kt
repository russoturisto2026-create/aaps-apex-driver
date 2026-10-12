package app.aaps.pump.atc3.check

import app.aaps.pump.atc3.protocol.Atc3Protocol

/**
 * Whether the pump's count moves while the AAPS journal says insulin is going in. Fed one tick at a
 * time with the count and the journal's figure since the window began. A tick where the journal owes
 * a step of insulin and the count did not move is a motionless tick; enough of them in a row and the
 * pump is held as delivering nothing, see [deliveryStopped]. The hold ends by itself when the count
 * moves by a step.
 */
class Atc3MotionWatch {

    /** One tick's figures. */
    private data class Tick(val startMs: Long, val readMs: Long, val counterUnits: Double, val aapsUnits: Double)

    private var lastTick: Tick? = null

    /** Ticks in a row where the journal owed a step of insulin and the count did not move. */
    var motionlessTicks = 0
        private set

    /**
     * The read the count was last seen at its present value before the motionless run began, ms: no
     * insulin left the pump from here on. Zero while no run is on.
     */
    var stillSinceMs = 0L
        private set

    /** True once the count has stood still for enough ticks in a row to stop the loop. */
    val deliveryStopped: Boolean get() = motionlessTicks >= NO_DELIVERY_TICKS_TO_STOP

    /** True once the count has stood still long enough for the user to be told. */
    val tellDue: Boolean get() = motionlessTicks >= NO_DELIVERY_TICKS_TO_TELL

    /**
     * Note one tick. The journal's figure is since the window began, so the last tick's is taken
     * off it; when the window began at the last tick's read, it is the whole of it.
     *
     * @param startMs when the window under way began
     * @param readMs when the status was read
     * @param counterUnits the pump's count at the read, U
     * @param aapsUnits what the AAPS journal holds since the window began, U
     */
    fun note(startMs: Long, readMs: Long, counterUnits: Double, aapsUnits: Double) {
        val last = lastTick
        if (last != null && last.readMs == readMs) return
        lastTick = Tick(startMs, readMs, counterUnits, aapsUnits)
        if (last == null) return
        val owed = when (startMs) {
            last.startMs -> aapsUnits - last.aapsUnits
            last.readMs  -> aapsUnits
            // A window begun anew elsewhere: the count starts over, and so does this.
            else         -> {
                reset()
                return
            }
        }
        val moved = counterUnits - last.counterUnits
        if (moved < 0.0 || moved >= Atc3Protocol.DOSE_SCALE - Atc3Protocol.COUNT_EPSILON) {
            reset()
            return
        }
        if (owed < Atc3Protocol.DOSE_SCALE) return
        if (motionlessTicks == 0) stillSinceMs = last.readMs
        motionlessTicks++
    }

    private fun reset() {
        motionlessTicks = 0
        stillSinceMs = 0L
    }

    companion object {

        /** Ticks with the count standing still while insulin was owed before the user is told. */
        const val NO_DELIVERY_TICKS_TO_TELL = 2

        /** One more, and the loop is stopped as well. */
        const val NO_DELIVERY_TICKS_TO_STOP = 3
    }
}
