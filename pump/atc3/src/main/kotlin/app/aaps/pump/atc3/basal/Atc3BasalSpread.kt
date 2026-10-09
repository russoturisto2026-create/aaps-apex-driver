package app.aaps.pump.atc3.basal

import javax.inject.Inject
import javax.inject.Singleton

/**
 * What to do with a closed window whose basal by the pump's count is not what the rows order. Nothing
 * yet: the window goes to the trace, and the figures gathered under the loop decide. This is kept
 * apart from the main account on purpose: whichever way is chosen, it is a variant, and the account
 * itself does not depend on it.
 *
 * The ways open:
 *
 * - leave the rows as ordered: the difference is counted and shown, nothing is written;
 * - move the rows in time, their rates untouched, so that over the window they add up to the count:
 *   inside a half hour that is always possible;
 * - replace the rows of the window with one row at its average rate.
 *
 * A tolerance goes with the choice. The standard allows 5 % of what was delivered; the aim is 0. An
 * earlier figure was a tenth of a unit or 10 % of the window's basal, whichever is more. A temporary
 * basal of another's shorter than a tick is not worth a rule yet. Correcting a window already passed
 * stays optional whichever way is chosen: the loop decides on the rows as they stand.
 */
@Singleton
class Atc3BasalSpread @Inject constructor() {

    /** Take a closed window. Nothing is written, see the class. */
    fun settle(window: Atc3BasalPeriod.Window) = Unit
}
