package app.aaps.pump.atc3

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
import android.widget.TextView
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
import app.aaps.core.ui.elements.NumberPicker
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import kotlin.random.Random
import app.aaps.pump.atc3.comm.Atc3Frame
import app.aaps.pump.atc3.comm.Atc3Settings
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.comm.Atc3BtPassword
import app.aaps.pump.atc3.comm.Atc3LinkProtection
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.databinding.Atc3FragmentBinding
import app.aaps.pump.atc3.databinding.Atc3HaloDialogPasswordBinding
import app.aaps.pump.atc3.databinding.Atc3HaloDialogSerialBinding
import app.aaps.pump.atc3.events.EventAtc3PumpDataChanged
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.manager.Atc3Manager
import app.aaps.pump.atc3.manager.Atc3SetBtPassword
import app.aaps.pump.atc3.manager.Atc3SetSuspended
import app.aaps.pump.atc3.manager.Atc3WriteSettings
import app.aaps.pump.atc3.ui.Atc3ScanActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.lang.ref.WeakReference
import java.text.DecimalFormat
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The ATC3 pump screen.
 *
 * One screen: the pump's state, and under it the few settings this driver offers -- the maximum
 * bolus and basal rate and the bolus speed, which are fields of the pump's own settings block,
 * read out of Status V1 and written back with [Atc3Const.ControlOpcode.WRITE_SETTINGS], see
 * [Atc3Settings]; and the Bluetooth password and the trace, which are the driver's own.
 *
 * The pump takes that block whole, sixteen bytes at a time, and that decides how editing works
 * here. Edits are collected into one draft and go to the pump together when Save is pressed;
 * nothing is sent while a value is being changed. A row can only be edited once the pump has
 * reported its settings at least once, because a write without them would replace the fields not
 * offered here with guesses.
 *
 * Whether the pump is connected at this instant is not part of that: the command queue connects
 * for whatever it is given. What does stop editing is having no way to reach the pump at all, no
 * Bluetooth or no pump selected.
 */
class Atc3Fragment : DaggerFragment() {

    @Inject lateinit var atc3Pump: Atc3Pump
    @Inject lateinit var atc3Manager: Atc3Manager
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

    /**
     * The edits made here, not yet sent.
     *
     * Null means the rows simply show what the pump reported.
     */
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

    /**
     * A number typed by hand is only rounded to something the pump can hold once the field is
     * left, so the rounding has to be noticed rather than waited for.
     */
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> updateGui() }

    /** What the rows show: the draft if there is one, otherwise what the pump reported. */
    private fun shownSettings(): Atc3Settings? = draft ?: atc3Pump.settings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        Atc3FragmentBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        binding.atc3ScreenStatus.atc3SelectPump.setOnClickListener {
            startActivity(Intent(requireContext(), Atc3ScanActivity::class.java))
        }
        binding.atc3ScreenStatus.atc3SerialRow.setOnClickListener { showSerialDialog() }
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

    /** A serial has to be exactly [Atc3Frame.SERIAL_LENGTH] digits, the pump refuses anything else. */
    private fun showSerialDialog() {
        val dialogBinding = Atc3HaloDialogSerialBinding.inflate(layoutInflater)
        dialogBinding.atc3SerialInput.setText(preferences.get(Atc3StringKey.Atc3SerialNumber))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.atc3_halo_serial_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(app.aaps.core.ui.R.string.ok) { _, _ ->
                val entered = dialogBinding.atc3SerialInput.text?.toString().orEmpty().trim()
                if (Atc3Frame.isValidSerial(entered)) {
                    if (entered != preferences.get(Atc3StringKey.Atc3SerialNumber)) {
                        // The stored address was found for the previous serial, so it points at a
                        // different pump. Clearing it sends the user back to the selection screen,
                        // where the address is looked up for this serial and no other.
                        preferences.put(Atc3StringKey.Atc3Address, "")
                    }
                    preferences.put(Atc3StringKey.Atc3SerialNumber, entered)
                    updateGui()
                } else {
                    ToastUtils.warnToast(requireContext().applicationContext, rh.gs(R.string.atc3_halo_serial_invalid))
                }
            }
            .setNegativeButton(app.aaps.core.ui.R.string.cancel, null)
            .show()
    }

    /**
     * Change the pump's Bluetooth password.
     *
     * The password AAPS presents is entered on the pump selection screen, next to the serial; this
     * screen is only for giving the pump a new one. The range is narrower than a password can be:
     * the change command carries six decimal digits of the value plus 65536, so anything above
     * [Atc3BtPassword.MAX_SETTABLE] has no payload that would ask for it. A random value in that
     * range is offered for the common case of not caring what the new password is; it is never
     * `000000`, which is the pump's way of having no password at all.
     */
    private fun showPasswordDialog() {
        val dialogBinding = Atc3HaloDialogPasswordBinding.inflate(layoutInflater)
        dialogBinding.atc3PasswordInput.setText(preferences.get(Atc3StringKey.Atc3BtPassword))
        dialogBinding.atc3PasswordRandom.setOnClickListener {
            dialogBinding.atc3PasswordInput.setText(
                Atc3BtPassword.format(Random.nextInt(Atc3BtPassword.MIN_SETTABLE + 1, Atc3BtPassword.MAX_SETTABLE + 1))
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.atc3_halo_password_dialog_title)
            .setMessage(R.string.atc3_halo_password_change_hint)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.atc3_halo_password_write) { _, _ ->
                val entered = dialogBinding.atc3PasswordInput.text?.toString().orEmpty().trim()
                writePasswordToPump(entered)
            }
            .setNegativeButton(app.aaps.core.ui.R.string.cancel, null)
            .show()
    }

    private fun writePasswordToPump(entered: String) {
        val context = context?.applicationContext ?: return
        if (!Atc3BtPassword.isValid(entered)) {
            ToastUtils.warnToast(context, rh.gs(R.string.atc3_halo_password_invalid))
            return
        }
        val value = entered.toInt()
        if (!Atc3BtPassword.isSettable(value)) {
            ToastUtils.warnToast(
                context,
                rh.gs(R.string.atc3_halo_password_out_of_range, Atc3BtPassword.format(Atc3BtPassword.MAX_SETTABLE))
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
            ToastUtils.okToast(context, rh.gs(R.string.atc3_halo_password_written, Atc3BtPassword.format(value)))
        } else {
            aapsLogger.error(LTag.PUMP, "ATC3: the pump did not take the new Bluetooth password, $comment")
            ToastUtils.errorToast(
                context,
                rh.gs(R.string.atc3_halo_password_write_failed, comment.ifBlank { rh.gs(R.string.atc3_not_connected) })
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
        updateSaveBar(binding)
    }

    private fun updateConnectionCard(binding: Atc3FragmentBinding) {
        val status = binding.atc3ScreenStatus
        val serial = preferences.get(Atc3StringKey.Atc3SerialNumber)
        val address = preferences.get(Atc3StringKey.Atc3Address)
        status.atc3SerialValue.text = serial.ifBlank { rh.gs(R.string.atc3_halo_no_serial) }
        status.atc3AddressValue.text = address
        status.atc3AddressValue.visibility = if (address.isBlank()) View.GONE else View.VISIBLE
        status.atc3SelectPump.contentDescription =
            if (serial.isBlank() && address.isBlank()) rh.gs(R.string.atc3_halo_select_pump) else rh.gs(R.string.atc3_halo_change_pump)
        status.atc3SelectPump.setBackgroundResource(
            if (bluetoothRadioEnabled()) R.drawable.atc3_halo_icon_ring_on else R.drawable.atc3_halo_icon_ring_off
        )
    }

    /**
     * Say, above everything else on the screen, when anybody within radio range can drive the pump.
     *
     * There are two ways for that to be true and they need different words. A pump with no password
     * set is one tap away from having one, and the row that does it is on the device screen. A pump
     * whose firmware has no password at all cannot be fixed from here at all, and telling its owner
     * to set one would send them looking for a menu that does not exist; the honest thing to say is
     * which firmware would give them one.
     *
     * Both are only known once a link has come up, so the banner stays hidden until then rather
     * than claiming safety the driver has not checked.
     */
    private fun updateLinkWarning(binding: Atc3FragmentBinding) {
        val warning = binding.atc3ScreenStatus.atc3LinkWarning
        val text = when (atc3Manager.linkProtection) {
            Atc3LinkProtection.UNPROTECTED -> rh.gs(R.string.atc3_halo_link_unprotected)
            Atc3LinkProtection.UNSUPPORTED -> rh.gs(
                R.string.atc3_halo_link_unsupported,
                atc3Pump.version?.firmwareText ?: rh.gs(R.string.atc3_halo_firmware_unknown),
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
            // No Bluetooth permission. The screen will say the radio is off, which is the same
            // thing from the user's side, but the reason is worth knowing when reading a log.
            aapsLogger.error(LTag.PUMPBTCOMM, "ATC3: cannot read the Bluetooth state", e)
            false
        }

    /** Whether a pump has been picked: an address to reach and a serial to address it by. */
    private fun pumpConfigured(): Boolean =
        preferences.get(Atc3StringKey.Atc3Address).isNotBlank() && atc3Manager.isConfigured

    private fun updateReservoir(binding: Atc3FragmentBinding) {
        val status = binding.atc3ScreenStatus
        val maxReservoir = PumpType.ATC3.maxReservoirReading()
        val noData = rh.gs(R.string.atc3_halo_placeholder_value)
        if (atc3Pump.isInitialized) {
            val percent = (atc3Pump.reservoirUnits / maxReservoir * 100).roundToInt().coerceIn(0, 100)
            status.atc3ReservoirRing.progress = percent
            status.atc3ReservoirUnits.text = atc3Pump.reservoirUnits.roundToInt().toString()
            status.atc3ReservoirPercent.text = "$percent%"
        } else {
            status.atc3ReservoirRing.progress = 0
            status.atc3ReservoirUnits.text = noData
            status.atc3ReservoirPercent.text = noData
        }
        status.atc3ReservoirCaption.text = rh.gs(R.string.atc3_halo_reservoir_of, maxReservoir)

        // What the remaining units mean in time, at the rate insulin has actually been used.
        val days = if (atc3Pump.isInitialized && averageDailyUnits > 0.0) atc3Pump.reservoirUnits / averageDailyUnits else null
        if (days != null) {
            status.atc3ReservoirDays.text = rh.gs(R.string.atc3_halo_days_value, DecimalFormat("0.0").format(days))
            status.atc3ReservoirDaysCaption.text = rh.gs(R.string.atc3_halo_days_caption, TDD_DAYS.toInt())
        } else {
            status.atc3ReservoirDays.text = noData
            status.atc3ReservoirDaysCaption.text = rh.gs(R.string.atc3_halo_days_no_data)
        }
    }

    /**
     * Work out the average daily use, away from the UI thread.
     *
     * The figure comes from the treatment history day by day, which is a database read and not
     * something to repeat every time this screen redraws. It moves by the hour at most, so it is
     * kept and refreshed at a distance.
     */
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

        // The pump sends volts and the charge is worked out from them, see Atc3StatusV2. Both are
        // shown: the percentage is the answer, the voltage is where it came from.
        val percent = atc3Pump.batteryPercent
        if (percent != null) {
            status.atc3BatteryRing.progress = percent.coerceIn(0, 100)
            status.atc3BatteryValue.text = percent.toString()
            status.atc3BatteryCaption.text = rh.gs(R.string.atc3_halo_battery_volts, atc3Pump.batteryVolts)
            status.atc3BatteryCaption.visibility = View.VISIBLE
        } else {
            status.atc3BatteryRing.progress = 0
            status.atc3BatteryValue.text = rh.gs(R.string.atc3_halo_placeholder_value)
            status.atc3BatteryCaption.visibility = View.GONE
        }

        status.atc3ConnectionValue.text = when {
            atc3Manager.isConnected  -> rh.gs(R.string.atc3_halo_connection_connected)
            atc3Manager.isConnecting -> rh.gs(R.string.atc3_halo_connection_connecting)
            else                     -> rh.gs(R.string.atc3_halo_connection_waiting)
        }
        status.atc3ConnectionCaption.text = lastConnectionText()
    }

    /**
     * Ask before stopping or resuming the pump.
     *
     * Stopping a pump is not an ordinary tap: it stops basal, and it stops a running bolus too. So
     * it is confirmed rather than done on the first touch, and it goes through the command queue,
     * which is what connects the pump and what keeps it out of the middle of a bolus.
     */
    private fun confirmDeliveryChange() {
        if (!atc3Pump.isInitialized) return
        val wantStopped = !atc3Pump.suspended
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
            // Application context: a Toast outlives the context it was built from (it's held by
            // the system until it finishes showing), so the fragment's activity context would leak.
            ToastUtils.errorToast(context?.applicationContext, comment.ifBlank { rh.gs(R.string.atc3_not_connected) })
        }
        updateGui()
    }

    /**
     * The running or suspended state, shown on the button that changes it.
     *
     * A running temp basal is not shown here. AAPS puts it on its own main screen, and this screen
     * is about what only the pump itself can say.
     *
     * The lock is shown here too, under the state rather than instead of it: a locked pump goes on
     * delivering, so it is still running or paused, and what the lock changes is that this button
     * and every other command will be refused until somebody unlocks the pump on the pump.
     */
    private fun updateDeliveryState(binding: Atc3FragmentBinding) {
        val status = binding.atc3ScreenStatus
        val running = atc3Pump.isInitialized && !atc3Pump.suspended
        status.atc3PumpStateLocked.visibility =
            if (atc3Pump.isInitialized && atc3Pump.locked) View.VISIBLE else View.GONE
        status.atc3PumpStateLabel.text = when {
            !atc3Pump.isInitialized -> rh.gs(R.string.atc3_halo_state_unknown)
            atc3Pump.suspended      -> rh.gs(R.string.atc3_halo_state_suspended)
            else                    -> rh.gs(R.string.atc3_halo_state_running)
        }
        status.atc3PumpStateIcon.setImageResource(if (running) R.drawable.ic_atc3_play else R.drawable.ic_atc3_pause)
        val stateColor = when {
            !atc3Pump.isInitialized -> R.color.atc3_halo_faint
            atc3Pump.suspended      -> R.color.atc3_halo_warn
            else                    -> R.color.atc3_halo_battery
        }
        status.atc3PumpStateIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(status.atc3PumpStateIcon.context, stateColor))
    }

    /** When the pump was last heard from, in plain words rather than a timestamp. */
    private fun lastConnectionText(): String {
        val last = atc3Pump.lastConnection
        if (last <= 0L) return rh.gs(R.string.atc3_halo_never_connected)
        val minutes = ((dateUtil.now() - last) / 60_000L).toInt()
        return when {
            minutes < 1               -> rh.gs(R.string.atc3_halo_just_now)
            minutes < MINUTES_PER_HOUR -> rh.gq(R.plurals.atc3_halo_minutes_ago, minutes, minutes)
            minutes < MINUTES_PER_DAY -> (minutes / MINUTES_PER_HOUR).let { rh.gq(R.plurals.atc3_halo_hours_ago, it, it) }
            else                      -> (minutes / MINUTES_PER_DAY).let { rh.gq(R.plurals.atc3_halo_days_ago, it, it) }
        }
    }

    // Settings rows

    private fun bindSettingsRows() {
        val rows = binding?.atc3ScreenStatus ?: return

        rows.atc3MaxBolusPicker.prepare(0.0, 30.0, Atc3Const.DOSE_SCALE, "0.000")
        rows.atc3MaxBolusPicker.onEdited { base, value -> base.withMaxBolus(value) }

        rows.atc3MaxBasalPicker.prepare(0.0, PumpType.ATC3.baseBasalMaxValue() ?: 25.0, Atc3Const.DOSE_SCALE, "0.000")
        rows.atc3MaxBasalPicker.onEdited { base, value -> base.withMaxBasal(value) }

        rows.atc3RowBolusSpeed.setOnClickListener {
            val base = shownSettings() ?: return@setOnClickListener
            showChoices(
                R.string.atc3_halo_bolus_speed_label,
                listOf(
                    Choice(rh.gs(R.string.atc3_halo_bolus_speed_normal), !base.lowBolusSpeed) { it.copy(lowBolusSpeed = false) },
                    Choice(rh.gs(R.string.atc3_halo_bolus_speed_low), base.lowBolusSpeed) { it.copy(lowBolusSpeed = true) }
                )
            )
        }
        rows.atc3RowPassword.setOnClickListener { showPasswordDialog() }
        rows.atc3TraceSwitch.isChecked = preferences.get(Atc3BooleanKey.Trace)
        rows.atc3TraceSwitch.setOnCheckedChangeListener { _, on -> preferences.put(Atc3BooleanKey.Trace, on) }
    }

    private fun updateSettingsRows(binding: Atc3FragmentBinding) {
        val settings = shownSettings()
        val rows = binding.atc3ScreenStatus

        // What blocks editing is not being able to reach the pump at all, or never having read it.
        // The link being down at this instant is not a reason: the command queue connects for
        // whatever it is handed.
        val blocked = when {
            !bluetoothRadioEnabled()  -> rh.gs(R.string.atc3_halo_settings_no_bluetooth)
            !pumpConfigured()         -> rh.gs(R.string.atc3_halo_settings_no_pump)
            atc3Pump.settings == null -> rh.gs(R.string.atc3_halo_settings_need_read)
            else                      -> null
        }
        val editable = blocked == null && settings != null
        rows.atc3SettingsHint.text = blocked.orEmpty()
        rows.atc3SettingsHint.visibility = if (blocked == null) View.GONE else View.VISIBLE

        // Independent of the settings block: the password is the driver's own configuration, and it
        // is exactly what has to be reachable when the pump cannot be read.
        rows.atc3PasswordValue.text = preferences.get(Atc3StringKey.Atc3BtPassword)
            .ifBlank { rh.gs(R.string.atc3_halo_password_none) }

        rows.atc3RowMaxBolus.setRowEnabled(editable)
        rows.atc3RowMaxBasal.setRowEnabled(editable)
        rows.atc3RowBolusSpeed.setRowEnabled(editable)

        settings ?: return
        rows.atc3MaxBolusPicker.showValue(settings.maxBolus)
        rows.atc3MaxBasalPicker.showValue(settings.maxBasal)
        rows.atc3BolusSpeedValue.text =
            rh.gs(if (settings.lowBolusSpeed) R.string.atc3_halo_bolus_speed_low else R.string.atc3_halo_bolus_speed_normal)
    }

    // The draft and the save bar

    /** How many settings have been changed here and not sent, 0 when the rows follow the pump. */
    private fun unsavedCount(): Int {
        val edited = draft ?: return 0
        val stored = atc3Pump.settings ?: return 0
        return edited.differenceCount(stored)
    }

    private fun updateSaveBar(binding: Atc3FragmentBinding) {
        val unsaved = unsavedCount()
        val sending = inFlight != null
        val failure = lastFailure
        binding.atc3SaveBar.visibility = if (unsaved > 0 || sending) View.VISIBLE else View.GONE
        binding.atc3SaveHint.text = when {
            sending          -> rh.gs(R.string.atc3_halo_settings_sending)
            failure != null  -> rh.gs(R.string.atc3_halo_settings_failed, failure)
            else             -> rh.gq(R.plurals.atc3_halo_unsaved, unsaved, unsaved)
        }
        binding.atc3SaveButton.isEnabled = !sending && unsaved > 0
        binding.atc3DiscardButton.isEnabled = !sending
    }

    private fun saveDraft() {
        val wanted = draft ?: return
        // A number typed by hand is rounded when it reaches the draft, so make the fields show the
        // rounded value before it goes: what is on screen has to be what was sent.
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

    /**
     * A queue callback that does not keep this screen alive.
     *
     * The command queue holds a callback until its command is finished, and a settings write takes
     * seconds of connecting, writing and reading back. An anonymous object here would hold the
     * fragment for all of that, and the fragment holds the activity, so leaving the screen mid
     * write would strand it. [onResult] is passed the screen rather than capturing it, so it stays
     * a lambda that holds nothing.
     */
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

    /**
     * Range, step and the number of decimals the step needs. Set once, the value follows the pump.
     *
     * The step is the pump's own granularity, so the field can only offer numbers the pump can
     * actually store, and a number typed by hand is rounded to one of them on the way into the
     * draft.
     */
    private fun NumberPicker.prepare(min: Double, max: Double, step: Double, pattern: String) {
        setParams(min, min, max, step, DecimalFormat(pattern), true, null)
    }

    private fun NumberPicker.onEdited(change: (Atc3Settings, Double) -> Atc3Settings) {
        setOnValueChangedListener { value ->
            if (loading) return@setOnValueChangedListener
            edit { base -> change(base, value.coerceIn(minValue, maxValue)) }
        }
    }

    private fun SwitchMaterial.onToggled(change: (Atc3Settings, Boolean) -> Atc3Settings) {
        setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            if (shownSettings() == null) {
                showChecked(!checked)
                return@setOnCheckedChangeListener
            }
            edit { base -> change(base, checked) }
        }
    }

    /** Put a value into a picker, leaving a field the user is in the middle of typing alone. */
    private fun NumberPicker.showValue(value: Double) {
        if (hasFocus()) return
        // The pump is allowed to hold a value outside the range offered here; widen rather than
        // clamp it, otherwise showing the pump's own setting would silently change it.
        if (value > maxValue) maxValue = value
        if (value < minValue) minValue = value
        if (abs(value - currentValue) <= SNAP_TOLERANCE) return
        loading = true
        this.value = value
        loading = false
    }

    private fun SwitchMaterial.showChecked(checked: Boolean) {
        if (isChecked == checked) return
        loading = true
        isChecked = checked
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
