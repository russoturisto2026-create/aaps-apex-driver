package app.aaps.pump.atc3.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.elements.NumberPicker
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.command.Atc3SetBtPassword
import app.aaps.pump.atc3.command.Atc3SetSuspended
import app.aaps.pump.atc3.command.Atc3WriteSettings
import app.aaps.pump.atc3.databinding.Atc3DialogPasswordBinding
import app.aaps.pump.atc3.databinding.Atc3FragmentBinding
import app.aaps.pump.atc3.events.EventAtc3PumpDataChanged
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.link.Atc3BtPassword
import app.aaps.pump.atc3.link.Atc3Connection
import app.aaps.pump.atc3.link.Atc3LinkProtection
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.protocol.Atc3Protocol
import app.aaps.pump.atc3.protocol.Atc3Settings
import app.aaps.pump.atc3.protocol.Atc3StatusV2
import app.aaps.pump.atc3.state.Atc3DoseGrid
import app.aaps.pump.atc3.state.Atc3PumpState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.lang.ref.WeakReference
import java.text.DecimalFormat
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The pump screen: its state, and the few settings the driver offers, the maximum bolus and basal
 * rate and the bolus speed from the pump's settings block, see [Atc3Settings], and the Bluetooth
 * password and the trace, which are the driver's own. The pump takes its settings only whole, so
 * edits are collected into one draft and sent on Save; a row can be edited only once the pump has
 * reported its settings. What stops editing is having no way to reach the pump, not its link being
 * down at this instant: the queue connects for what it is given.
 */
class Atc3Fragment : DaggerFragment() {

    @Inject lateinit var pumpState: Atc3PumpState
    @Inject lateinit var atc3Manager: Atc3Manager
    @Inject lateinit var atc3Connection: Atc3Connection
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var aapsLogger: AAPSLogger

    private val disposable = CompositeDisposable()
    private var binding: Atc3FragmentBinding? = null
    private val handler = Handler(Looper.getMainLooper())

    /** The edits made here, not yet sent; null when the rows show what the pump reported. */
    private var draft: Atc3Settings? = null

    /** The settings write the command queue is carrying out, null when nothing is in flight. */
    private var inFlight: Atc3Settings? = null

    /** What the pump answered when it would not take the last write. */
    private var lastFailure: String? = null

    /** True while pump values are being put into the widgets, so their listeners stay quiet. */
    private var loading = false

    /** Average units a day over the last [TDD_DAYS] days, zero while that is not known yet. */
    private var averageDailyUnits: Double = 0.0

    /** When [averageDailyUnits] was last asked for, so it is not asked for on every redraw. */
    private var averageDailyUnitsAt: Long = 0L

    /** Keeps the last connection line honest while the screen is open and nothing else happens. */
    private val tick = object : Runnable {
        override fun run() {
            updateGui()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** A number typed by hand is rounded to the pump's scale once the field is left: noticed, not waited for. */
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> updateGui() }

    /** What the rows show: the draft if there is one, otherwise what the pump reported. */
    private fun shownSettings(): Atc3Settings? = draft ?: pumpState.settings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        Atc3FragmentBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        binding.atc3ScreenStatus.atc3SelectPump.setOnClickListener {
            startActivity(Intent(requireContext(), Atc3ScanActivity::class.java))
        }
        binding.atc3ScreenStatus.atc3PumpStateButton.setOnClickListener { confirmDeliveryChange() }

        binding.atc3SwipeRefresh.setColorSchemeColors(
            rh.gac(context, android.R.attr.colorPrimaryDark),
            rh.gac(context, android.R.attr.colorPrimary),
            rh.gac(context, com.google.android.material.R.attr.colorSecondary)
        )
        binding.atc3SwipeRefresh.setOnRefreshListener { readStatusNow() }

        binding.atc3SaveButton.setOnClickListener { saveDraft() }
        binding.atc3DiscardButton.setOnClickListener { discardDraft() }

        bindSettingsRows()

        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAtc3PumpDataChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        handler.postDelayed(tick, TICK_MS)
        updateGui()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        disposable.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusListener)
        handler.removeCallbacksAndMessages(null)
        binding = null
    }

    /**
     * Give the pump a new Bluetooth password. The one AAPS presents is entered on the pump selection
     * screen; this only changes the pump's. A random value within what the change command can set, and
     * never `000000`, is offered.
     */
    private fun showPasswordDialog() {
        val dialogBinding = Atc3DialogPasswordBinding.inflate(layoutInflater)
        dialogBinding.atc3PasswordInput.setText(preferences.get(Atc3StringKey.Atc3BtPassword))
        dialogBinding.atc3PasswordRandom.setOnClickListener {
            dialogBinding.atc3PasswordInput.setText(
                Atc3BtPassword.format(Random.nextInt(Atc3BtPassword.MIN_SETTABLE + 1, Atc3BtPassword.MAX_SETTABLE + 1))
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.atc3_password_dialog_title)
            .setMessage(R.string.atc3_password_change_hint)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.atc3_password_write) { _, _ ->
                val entered = dialogBinding.atc3PasswordInput.text?.toString().orEmpty().trim()
                writePasswordToPump(entered)
            }
            .setNegativeButton(app.aaps.core.ui.R.string.cancel, null)
            .show()
    }

    private fun writePasswordToPump(entered: String) {
        val context = context?.applicationContext ?: return
        if (!Atc3BtPassword.isValid(entered)) {
            ToastUtils.warnToast(context, rh.gs(R.string.atc3_password_invalid))
            return
        }
        val value = entered.toInt()
        if (!Atc3BtPassword.isSettable(value)) {
            ToastUtils.warnToast(
                context,
                rh.gs(R.string.atc3_password_out_of_range, Atc3BtPassword.format(Atc3BtPassword.MAX_SETTABLE))
            )
            return
        }
        aapsLogger.debug(LTag.PUMP, "ATC3: user asked to change the Bluetooth password")
        commandQueue.customCommand(
            Atc3SetBtPassword(value),
            QueueCallback(this) { screen, success, comment -> screen.onPasswordWritten(value, success, comment) }
        )
    }

    private fun onPasswordWritten(value: Int, success: Boolean, comment: String) {
        val context = context?.applicationContext ?: return
        if (success) {
            ToastUtils.okToast(context, rh.gs(R.string.atc3_password_written, Atc3BtPassword.format(value)))
        } else {
            aapsLogger.error(LTag.PUMP, "ATC3: the pump did not take the new Bluetooth password, $comment")
            ToastUtils.errorToast(
                context,
                rh.gs(R.string.atc3_password_write_failed, comment.ifBlank { rh.gs(R.string.atc3_not_connected) })
            )
        }
        updateGui()
    }

    // Status

    /** Pull to refresh: ask the queue for a status read, which is what fills every screen here. */
    private fun readStatusNow() {
        val queuedRead = commandQueue.readStatus(
            rh.gs(app.aaps.core.ui.R.string.user_request),
            QueueCallback(this) { screen, _, _ -> screen.onStatusRead() }
        )
        if (!queuedRead) binding?.atc3SwipeRefresh?.isRefreshing = false
    }

    private fun onStatusRead() {
        binding?.atc3SwipeRefresh?.isRefreshing = false
        updateGui()
    }

    private fun updateGui() {
        val binding = binding ?: return
        refreshAverageDailyUnits()
        updateConnectionCard(binding)
        updateLinkWarning(binding)
        updateReservoir(binding)
        updateMetrics(binding)
        updateDeliveryState(binding)
        updateSettingsRows(binding)
        updateDayAccount(binding)
        binding.atc3ScreenStatus.atc3DriverVersionValue.text = Atc3Const.DRIVER_VERSION
        updateSaveBar(binding)
    }

    /**
     * The day so far by the AAPS journal and by the pump's count, and how far apart: the journal's sum
     * shown on the pump's nearest step, counted from midnight or the last beginning, which is then named.
     */
    private fun updateDayAccount(binding: Atc3FragmentBinding) {
        val day = pumpState.dayAccount
        binding.atc3ScreenStatus.atc3DayAccountValue.text = if (day == null) {
            rh.gs(R.string.atc3_day_account_none)
        } else {
            val aapsUnits = Math.round(day.aapsUnits / Atc3Protocol.DOSE_SCALE) * Atc3Protocol.DOSE_SCALE
            val since = day.sinceMs
            if (since == null) rh.gs(R.string.atc3_day_account_value, aapsUnits, day.pumpUnits, day.pumpUnits - aapsUnits)
            else rh.gs(R.string.atc3_day_account_value_since, aapsUnits, day.pumpUnits, day.pumpUnits - aapsUnits, dateUtil.timeString(since))
        }
    }

    private fun updateConnectionCard(binding: Atc3FragmentBinding) {
        val status = binding.atc3ScreenStatus
        val serial = preferences.get(Atc3StringKey.Atc3SerialNumber)
        val address = preferences.get(Atc3StringKey.Atc3Address)
        status.atc3SerialValue.text = serial.ifBlank { rh.gs(R.string.atc3_no_serial) }
        status.atc3AddressValue.text = address
        status.atc3AddressValue.visibility = if (address.isBlank()) View.GONE else View.VISIBLE
        status.atc3SelectPump.contentDescription =
            if (serial.isBlank() && address.isBlank()) rh.gs(R.string.atc3_select_pump) else rh.gs(R.string.atc3_change_pump)
        status.atc3SelectPump.setBackgroundResource(
            if (bluetoothRadioEnabled()) R.drawable.atc3_icon_ring_on else R.drawable.atc3_icon_ring_off
        )
    }

    /**
     * Say first when anybody in radio range can drive the pump: no password set, which a tap fixes, or
     * firmware without one, which only a firmware update does. Hidden until a link has said which.
     */
    private fun updateLinkWarning(binding: Atc3FragmentBinding) {
        val warning = binding.atc3ScreenStatus.atc3LinkWarning
        val text = when (atc3Connection.linkProtection) {
            Atc3LinkProtection.UNPROTECTED -> rh.gs(R.string.atc3_link_unprotected)
            Atc3LinkProtection.UNSUPPORTED -> rh.gs(
                R.string.atc3_link_unsupported,
                pumpState.version?.firmwareText ?: rh.gs(R.string.atc3_firmware_unknown),
                Atc3Const.PASSWORD_FIRMWARE.joinToString(".")
            )

            Atc3LinkProtection.PROTECTED,
            Atc3LinkProtection.UNKNOWN     -> null
        }
        warning.text = text.orEmpty()
        warning.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Whether the phone's own Bluetooth radio is on. Without it the pump cannot be reached at all. */
    private fun bluetoothRadioEnabled(): Boolean =
        try {
            (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter?.isEnabled == true
        } catch (e: SecurityException) {
            // No Bluetooth permission: shown as the radio off, said in the log.
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: cannot read the Bluetooth state", e)
            false
        }

    /** Whether a pump has been picked: an address to reach and a serial to address it by. */
    private fun pumpConfigured(): Boolean =
        preferences.get(Atc3StringKey.Atc3Address).isNotBlank() && atc3Connection.isConfigured

    private fun updateReservoir(binding: Atc3FragmentBinding) {
        val status = binding.atc3ScreenStatus
        val maxReservoir = PumpType.ATC3.maxReservoirReading()
        val noData = rh.gs(R.string.atc3_placeholder_value)
        if (pumpState.isInitialized) {
            val percent = (pumpState.reservoirUnits / maxReservoir * 100).roundToInt().coerceIn(0, 100)
            status.atc3ReservoirRing.progress = percent
            status.atc3ReservoirUnits.text = pumpState.reservoirUnits.roundToInt().toString()
            status.atc3ReservoirPercent.text = "$percent%"
        } else {
            status.atc3ReservoirRing.progress = 0
            status.atc3ReservoirUnits.text = noData
            status.atc3ReservoirPercent.text = noData
        }
        status.atc3ReservoirCaption.text = rh.gs(R.string.atc3_reservoir_of, maxReservoir)

        // What the remaining units mean in time, at the rate insulin has actually been used.
        val days = if (pumpState.isInitialized && averageDailyUnits > 0.0) pumpState.reservoirUnits / averageDailyUnits else null
        if (days != null) {
            status.atc3ReservoirDays.text = rh.gs(R.string.atc3_days_value, DecimalFormat("0.0").format(days))
            status.atc3ReservoirDaysCaption.text = rh.gs(R.string.atc3_days_caption, TDD_DAYS.toInt())
        } else {
            status.atc3ReservoirDays.text = noData
            status.atc3ReservoirDaysCaption.text = rh.gs(R.string.atc3_days_no_data)
        }
    }

    /** The average daily use, from the treatment history, off the UI thread and not on every redraw. */
    private fun refreshAverageDailyUnits() {
        if (averageDailyUnitsAt > 0L && dateUtil.now() - averageDailyUnitsAt < TDD_REFRESH_MS) return
        averageDailyUnitsAt = dateUtil.now()
        disposable += Single
            .fromCallable {
                tddCalculator.averageTDD(tddCalculator.calculate(TDD_DAYS, allowMissingDays = true))?.data?.totalAmount ?: 0.0
            }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe(
                { average ->
                    averageDailyUnits = average
                    binding?.let { updateReservoir(it) }
                },
                { error -> aapsLogger.error(LTag.PUMP, "ATC3: average daily use could not be worked out", error) }
            )
    }

    private fun updateMetrics(binding: Atc3FragmentBinding) {
        val status = binding.atc3ScreenStatus

        // The charge is worked out from the volts, see Atc3StatusV2: both are shown.
        val v2 = pumpState.statusV2
        val percent = v2?.batteryPercent
        if (v2 != null && percent != null) {
            status.atc3BatteryRing.progress = percent.coerceIn(0, 100)
            status.atc3BatteryValue.text = percent.toString()
            status.atc3BatteryCaption.text = rh.gs(R.string.atc3_battery_volts, v2.batteryVolts)
            status.atc3BatteryCaption.visibility = View.VISIBLE
        } else {
            status.atc3BatteryRing.progress = 0
            status.atc3BatteryValue.text = rh.gs(R.string.atc3_placeholder_value)
            status.atc3BatteryCaption.visibility = View.GONE
        }

        status.atc3ConnectionValue.text = when {
            atc3Connection.isConnected  -> rh.gs(R.string.atc3_connection_connected)
            atc3Connection.isConnecting -> rh.gs(R.string.atc3_connection_connecting)
            else                     -> rh.gs(R.string.atc3_connection_waiting)
        }
        status.atc3ConnectionCaption.text = lastConnectionText()
    }

    /** Ask before stopping or resuming the pump: a stop stops basal and a running bolus too. */
    private fun confirmDeliveryChange() {
        if (!pumpState.isInitialized) return
        val wantStopped = !pumpState.suspended
        val context = context ?: return
        OKDialog.showConfirmation(
            context,
            rh.gs(if (wantStopped) R.string.atc3_confirm_stop else R.string.atc3_confirm_resume),
            Runnable {
                aapsLogger.debug(LTag.PUMP, "ATC3: user asked to ${if (wantStopped) "stop" else "resume"} the pump")
                commandQueue.customCommand(
                    Atc3SetSuspended(wantStopped),
                    QueueCallback(this) { screen, success, comment -> screen.onDeliveryChangeFinished(success, comment) }
                )
            }
        )
    }

    private fun onDeliveryChangeFinished(success: Boolean, comment: String) {
        if (!success) {
            aapsLogger.error(LTag.PUMP, "ATC3: the pump did not change its delivery state, $comment")
            // Application context: a Toast outlives the fragment's.
            ToastUtils.errorToast(context?.applicationContext, comment.ifBlank { rh.gs(R.string.atc3_not_connected) })
        }
        updateGui()
    }

    /**
     * Running or stopped, shown on the button that changes it, and the lock under it: a locked pump
     * still delivers, and refuses every command until unlocked on the pump.
     */
    private fun updateDeliveryState(binding: Atc3FragmentBinding) {
        val status = binding.atc3ScreenStatus
        val card = pumpState.statusCard
        val running = card != null && !card.suspended
        status.atc3PumpStateLocked.visibility =
            if (card?.locked == true) View.VISIBLE else View.GONE
        status.atc3PumpStateLabel.text = when {
            card == null            -> rh.gs(R.string.atc3_state_unknown)
            card.suspended          -> rh.gs(R.string.atc3_state_suspended)
            else                    -> rh.gs(R.string.atc3_state_running)
        }
        status.atc3PumpStateIcon.setImageResource(if (running) R.drawable.ic_atc3_play else R.drawable.ic_atc3_pause)
        val stateColor = when {
            card == null            -> R.color.atc3_faint
            card.suspended          -> R.color.atc3_warn
            else                    -> R.color.atc3_battery
        }
        status.atc3PumpStateIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(status.atc3PumpStateIcon.context, stateColor))
    }

    /** When the pump was last heard from, in plain words rather than a timestamp. */
    private fun lastConnectionText(): String {
        val last = pumpState.lastConnection
        if (last <= 0L) return rh.gs(R.string.atc3_never_connected)
        val minutes = ((dateUtil.now() - last) / 60_000L).toInt()
        return when {
            minutes < 1               -> rh.gs(R.string.atc3_just_now)
            minutes < MINUTES_PER_HOUR -> rh.gq(R.plurals.atc3_minutes_ago, minutes, minutes)
            minutes < MINUTES_PER_DAY -> (minutes / MINUTES_PER_HOUR).let { rh.gq(R.plurals.atc3_hours_ago, it, it) }
            else                      -> (minutes / MINUTES_PER_DAY).let { rh.gq(R.plurals.atc3_days_ago, it, it) }
        }
    }

    // Settings rows

    private fun bindSettingsRows() {
        val rows = binding?.atc3ScreenStatus ?: return

        rows.atc3MaxBolusPicker.prepare(0.0, 30.0, Atc3Protocol.DOSE_SCALE, "0.000")
        rows.atc3MaxBolusPicker.onDoseEdited({ it.maxBolus }) { base, value -> base.withMaxBolus(value) }

        rows.atc3MaxBasalPicker.prepare(0.0, PumpType.ATC3.baseBasalMaxValue() ?: 25.0, Atc3Protocol.DOSE_SCALE, "0.000")
        rows.atc3MaxBasalPicker.onDoseEdited({ it.maxBasal }) { base, value -> base.withMaxBasal(value) }

        rows.atc3RowBolusSpeed.setOnClickListener {
            val base = shownSettings() ?: return@setOnClickListener
            showChoices(
                R.string.atc3_bolus_speed_label,
                listOf(
                    Choice(rh.gs(R.string.atc3_bolus_speed_normal), !base.lowBolusSpeed) { it.copy(lowBolusSpeed = false) },
                    Choice(rh.gs(R.string.atc3_bolus_speed_low), base.lowBolusSpeed) { it.copy(lowBolusSpeed = true) }
                )
            )
        }
        rows.atc3RowPassword.setOnClickListener { showPasswordDialog() }
        rows.atc3ExactBasalSwitch.isChecked = preferences.get(Atc3BooleanKey.ExactBasal)
        rows.atc3ExactBasalSwitch.setOnCheckedChangeListener { _, on -> preferences.put(Atc3BooleanKey.ExactBasal, on) }
        rows.atc3HoldLinkSwitch.isChecked = preferences.get(Atc3BooleanKey.HoldLink)
        rows.atc3HoldLinkSwitch.setOnCheckedChangeListener { _, on -> preferences.put(Atc3BooleanKey.HoldLink, on) }
        rows.atc3TraceSwitch.isChecked = preferences.get(Atc3BooleanKey.Trace)
        rows.atc3TraceSwitch.setOnCheckedChangeListener { _, on -> preferences.put(Atc3BooleanKey.Trace, on) }
    }

    private fun updateSettingsRows(binding: Atc3FragmentBinding) {
        val settings = shownSettings()
        val rows = binding.atc3ScreenStatus

        // Not reaching the pump at all, or never having read it, blocks editing; the link down now does not.
        val blocked = when {
            !bluetoothRadioEnabled()  -> rh.gs(R.string.atc3_settings_no_bluetooth)
            !pumpConfigured()         -> rh.gs(R.string.atc3_settings_no_pump)
            pumpState.settings == null -> rh.gs(R.string.atc3_settings_need_read)
            else                      -> null
        }
        val editable = blocked == null && settings != null
        rows.atc3SettingsHint.text = blocked.orEmpty()
        rows.atc3SettingsHint.visibility = if (blocked == null) View.GONE else View.VISIBLE

        // The password is the driver's own, and has to be reachable when the pump cannot be read.
        rows.atc3PasswordValue.text = preferences.get(Atc3StringKey.Atc3BtPassword)
            .ifBlank { rh.gs(R.string.atc3_password_none) }

        rows.atc3RowMaxBolus.setRowEnabled(editable)
        rows.atc3RowMaxBasal.setRowEnabled(editable)
        rows.atc3RowBolusSpeed.setRowEnabled(editable)

        settings ?: return
        rows.atc3MaxBolusPicker.showDose(settings.maxBolus)
        rows.atc3MaxBasalPicker.showDose(settings.maxBasal)
        rows.atc3BolusSpeedValue.text =
            rh.gs(if (settings.lowBolusSpeed) R.string.atc3_bolus_speed_low else R.string.atc3_bolus_speed_normal)
    }

    // The draft and the save bar

    /** How many settings have been changed here and not sent, 0 when the rows follow the pump. */
    private fun unsavedCount(): Int {
        val edited = draft ?: return 0
        val stored = pumpState.settings ?: return 0
        return edited.differenceCount(stored)
    }

    private fun updateSaveBar(binding: Atc3FragmentBinding) {
        val unsaved = unsavedCount()
        val sending = inFlight != null
        val failure = lastFailure
        binding.atc3SaveBar.visibility = if (unsaved > 0 || sending) View.VISIBLE else View.GONE
        binding.atc3SaveHint.text = when {
            sending          -> rh.gs(R.string.atc3_settings_sending)
            failure != null  -> rh.gs(R.string.atc3_settings_failed, failure)
            else             -> rh.gq(R.plurals.atc3_unsaved, unsaved, unsaved)
        }
        binding.atc3SaveButton.isEnabled = !sending && unsaved > 0
        binding.atc3DiscardButton.isEnabled = !sending
    }

    private fun saveDraft() {
        val wanted = draft ?: return
        // Hand-typed numbers are rounded on the way into the draft: the fields show what is sent.
        binding?.root?.clearFocus()
        inFlight = wanted
        lastFailure = null
        updateGui()
        aapsLogger.debug(LTag.PUMP, "ATC3: sending settings $wanted")
        commandQueue.customCommand(
            Atc3WriteSettings(wanted),
            QueueCallback(this) { screen, success, comment -> screen.onWriteFinished(success, comment) }
        )
    }

    private fun discardDraft() {
        binding?.root?.clearFocus()
        draft = null
        lastFailure = null
        updateGui()
    }

    private fun onWriteFinished(success: Boolean, comment: String) {
        inFlight = null
        if (success) {
            lastFailure = null
            // Confirmed against a fresh status read, so the pump's own copy is the one to show now.
            draft = null
        } else {
            aapsLogger.error(LTag.PUMP, "ATC3: settings write failed, $comment")
            lastFailure = comment.ifBlank { rh.gs(R.string.atc3_not_connected) }
            // The draft is kept so the change can be sent again without being typed again.
        }
        updateGui()
    }

    /** Fold one change into the draft. Nothing leaves the phone until Save. */
    private fun edit(change: (Atc3Settings) -> Atc3Settings) {
        val base = shownSettings() ?: return
        draft = change(base)
        lastFailure = null
        updateGui()
    }

    /** A queue callback that does not hold the screen: a settings write takes seconds, and leaving meanwhile must not strand it. */
    private class QueueCallback(
        fragment: Atc3Fragment,
        private val onResult: (Atc3Fragment, Boolean, String) -> Unit
    ) : Callback() {

        private val fragment = WeakReference(fragment)

        override fun run() {
            val success = result.success
            val comment = result.comment
            fragment.get()?.let { screen -> screen.handler.post { onResult(screen, success, comment) } }
        }
    }

    // Widget plumbing

    private class Choice(val label: String, val selected: Boolean, val apply: (Atc3Settings) -> Atc3Settings)

    private fun showChoices(titleRes: Int, choices: List<Choice>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setSingleChoiceItems(
                choices.map { it.label }.toTypedArray(),
                choices.indexOfFirst { it.selected }
            ) { dialog, which ->
                dialog.dismiss()
                edit(choices[which].apply)
            }
            .setNegativeButton(app.aaps.core.ui.R.string.cancel, null)
            .show()
    }

    /** Range, step and decimals: the field offers only what the pump can store. */
    private fun NumberPicker.prepare(min: Double, max: Double, step: Double, pattern: String) {
        setParams(min, min, max, step, DecimalFormat(pattern), true, null)
    }

    /**
     * A field holding an amount of insulin, on the pump's scale, [Atc3DoseGrid]: a button press moves to
     * the next amount, a typed number to the nearest.
     *
     * @param current the value the field stands for
     */
    private fun NumberPicker.onDoseEdited(current: (Atc3Settings) -> Double, change: (Atc3Settings, Double) -> Atc3Settings) {
        setOnValueChangedListener { value ->
            if (loading) return@setOnValueChangedListener
            val before = shownSettings()?.let(current) ?: return@setOnValueChangedListener
            val raw = value.coerceIn(minValue, maxValue)
            val oneStep = abs(raw - before) <= step + SNAP_TOLERANCE
            val onScale = when {
                oneStep && raw > before -> Atc3DoseGrid.up(raw)
                oneStep && raw < before -> Atc3DoseGrid.down(raw)
                else                    -> Atc3DoseGrid.nearest(raw)
            }.coerceIn(minValue, maxValue)
            edit { base -> change(base, onScale) }
        }
    }

    /**
     * Put an amount of insulin into its picker, with the step and the decimals the pump's scale
     * has at that amount.
     */
    private fun NumberPicker.showDose(value: Double) {
        if (hasFocus()) return
        step = Atc3DoseGrid.stepBelow(value)
        val pattern = Atc3DoseGrid.pattern(value)
        if ((formatter as? DecimalFormat)?.toPattern() != pattern) {
            formatter = DecimalFormat(pattern)
            // Shown again even when the number has not changed: its decimals have.
            loading = true
            this.value = value
            loading = false
        }
        showValue(value)
    }

    /** Put a value into a picker, leaving a field the user is in the middle of typing alone. */
    private fun NumberPicker.showValue(value: Double) {
        if (hasFocus()) return
        // The pump may hold a value outside the range offered: widened, not clamped, so showing it does not change it.
        if (value > maxValue) maxValue = value
        if (value < minValue) minValue = value
        if (abs(value - currentValue) <= SNAP_TOLERANCE) return
        loading = true
        this.value = value
        loading = false
    }

    private fun View.setRowEnabled(enabled: Boolean) {
        alpha = if (enabled) 1f else DISABLED_ALPHA
        setEnabledDeep(enabled)
    }

    private fun View.setEnabledDeep(enabled: Boolean) {
        isEnabled = enabled
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).setEnabledDeep(enabled)
    }

    companion object {

        /** How often the last connection line is redrawn while the screen is open, milliseconds. */
        private const val TICK_MS = 30_000L

        /** Days of history the average daily use is taken over. */
        private const val TDD_DAYS = 7L

        /** How long an average daily use figure is kept before it is worked out again. */
        private const val TDD_REFRESH_MS = 10 * 60 * 1000L

        /** Below this, two of these values are the same number said differently. */
        private const val SNAP_TOLERANCE = 1e-6
        private const val DISABLED_ALPHA = 0.45f
        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 24 * 60
    }
}
