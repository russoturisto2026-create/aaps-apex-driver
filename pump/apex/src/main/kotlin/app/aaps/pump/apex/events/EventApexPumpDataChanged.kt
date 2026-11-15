package app.aaps.pump.apex.events

import app.aaps.core.interfaces.rx.events.Event

/** Something the driver's screens show has changed: AAPS's own pump event marks only the link going up and down. */
class EventApexPumpDataChanged : Event()
