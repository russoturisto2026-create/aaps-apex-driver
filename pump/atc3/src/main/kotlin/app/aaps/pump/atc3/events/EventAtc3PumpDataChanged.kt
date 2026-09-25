package app.aaps.pump.atc3.events

import app.aaps.core.interfaces.rx.events.Event

/**
 * Something the ATC3 screens display has changed: a status was decoded, or a settings write
 * finished.
 *
 * [app.aaps.core.interfaces.rx.events.EventPumpStatusChanged] only marks the link coming up and
 * going down, so on its own it never refreshes a reservoir reading or a settings row.
 */
class EventAtc3PumpDataChanged : Event()
