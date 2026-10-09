package app.aaps.pump.atc3.link

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBase
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The setup chain driven by the stack's callbacks as a pump answers them: each callback takes the
 * link one step on, from the state before that step only, until it is ready; and whatever arrives
 * for a link that has ended touches nothing.
 */
class Atc3BleCallbackTest : TestBase() {

    @Mock lateinit var context: Context
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var callback: Atc3BleCallback

    private lateinit var ble: Atc3BLE

    /** Writes from another thread, as the exchange does. */
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var gatt: BluetoothGatt

    private val dataDescriptor = descriptor()
    private val authDescriptor = descriptor()
    private val notify = characteristic(GattAttributes.characteristicNotifyUuid, dataDescriptor)
    private val write = characteristic(GattAttributes.characteristicWriteUuid)
    private val authNotify = characteristic(GattAttributes.characteristicAuthNotifyUuid, authDescriptor)
    private val authWrite = characteristic(GattAttributes.characteristicAuthWriteUuid)

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)
        ble = Atc3BLE(aapsLogger, context, Atc3Trace(aapsLogger, preferences))
        ble.setCallback(callback)
        whenever(dataDescriptor.characteristic).thenReturn(notify)
        whenever(authDescriptor.characteristic).thenReturn(authNotify)
        gatt = stack()
    }

    @AfterEach
    fun tearDown() {
        worker.shutdownNow()
    }

    @Test
    fun `an attempt that does not come up in time is ended by the watchdog`() {
        val tasks = manualScheduler()
        start(gatt)

        tasks.single { it.second == Atc3BLE.CONNECT_TIMEOUT_MS }.first.run()

        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.IDLE)
        verify(callback, times(1)).onDisconnected()
        // The stack never answered: the next attempt waits, longer each time.
        val first = ble.backoffRemainingMs
        assertThat(first).isGreaterThan(0L)
        start(gatt)
        tasks.last { it.second == Atc3BLE.CONNECT_TIMEOUT_MS }.first.run()
        assertThat(ble.backoffRemainingMs).isGreaterThan(first)
    }

    @Test
    fun `a link ready before the watchdog fires is left alone by it`() {
        val tasks = manualScheduler()
        ready()

        tasks.single { it.second == Atc3BLE.CONNECT_TIMEOUT_MS }.first.run()

        assertThat(ble.isConnected).isTrue()
        verify(callback, never()).onDisconnected()
    }

    @Test
    fun `a write the stack never reports ends the link, so its late report counts for nothing`() {
        // The stack's report names no write: on a link kept, it would be taken for the next write's.
        ble.writeTimeoutMs = 50L
        val link = ready()

        assertThat(ble.write(byteArrayOf(1, 2))).isFalse()

        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.IDLE)
        verify(callback, times(1)).onDisconnected()
        link.onCharacteristicWrite(gatt, write, BluetoothGatt.GATT_SUCCESS)
        verify(callback, times(1)).onDisconnected()
    }

    @Test
    fun `writes the stack never reports hold the next link off longer each time`() {
        ble.writeTimeoutMs = 20L
        ready()
        ble.write(byteArrayOf(1))
        val first = ble.backoffRemainingMs
        ready()
        ble.write(byteArrayOf(1))

        assertThat(first).isGreaterThan(Atc3Backoff.RELEASE_SETTLE_MS)
        assertThat(ble.backoffRemainingMs).isGreaterThan(first)
    }

    @Test
    fun `a link that ends as it comes up is told as ended, not as up`() {
        // The end lands between the link becoming ready and its start being told.
        val logger = mock<AAPSLogger>()
        ble = Atc3BLE(logger, context, Atc3Trace(aapsLogger, preferences))
        ble.setCallback(callback)
        doAnswer { ble.disconnect() }.whenever(logger).debug(LTag.PUMPBTCOMM, "ATC3: ready")
        val link = upToServices()

        link.onDescriptorWrite(gatt, dataDescriptor, BluetoothGatt.GATT_SUCCESS)

        verify(callback, never()).onConnected()
        verify(callback, times(1)).onDisconnected()
    }

    @Test
    fun `a stack that throws holds the next attempt off`() {
        assertThat(ble.startLink { throw IllegalStateException("the stack is gone") }).isFalse()

        assertThat(ble.backoffRemainingMs).isGreaterThan(0L)
    }

    @Test
    fun `a pump with a password service is ready after the password is accepted`() {
        withPasswordService(gatt)
        val link = upToServices()
        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.AUTHORISING)
        assertThat(ble.isConnecting).isTrue()
        link.onDescriptorWrite(gatt, authDescriptor, BluetoothGatt.GATT_SUCCESS)
        link.onCharacteristicWrite(gatt, authWrite, BluetoothGatt.GATT_SUCCESS)
        link.onCharacteristicChanged(gatt, authNotify, byteArrayOf(Atc3BtPassword.ACCEPTED))
        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.SUBSCRIBING)
        link.onDescriptorWrite(gatt, dataDescriptor, BluetoothGatt.GATT_SUCCESS)

        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.READY)
        assertThat(ble.isConnected).isTrue()
        assertThat(ble.isConnecting).isFalse()
        assertThat(ble.linkProtection).isEqualTo(Atc3LinkProtection.UNPROTECTED)
        verify(callback, times(1)).onConnected()
    }

    @Test
    fun `a pump without a password service is subscribed to at once and is ready`() {
        val link = upToServices()
        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.SUBSCRIBING)
        link.onDescriptorWrite(gatt, dataDescriptor, BluetoothGatt.GATT_SUCCESS)

        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.READY)
        assertThat(ble.linkProtection).isEqualTo(Atc3LinkProtection.UNSUPPORTED)
        verify(callback, times(1)).onConnected()
    }

    @Test
    fun `a connected callback the stack repeats does not ask for the MTU again`() {
        // One connection attempt and two connected callbacks 26 ms apart, as when the phone's
        // Bluetooth is being switched off. Setup begun again from the second would ask for the MTU
        // while discovery from the first was in flight, and discovery would never be answered.
        val link = start(gatt)

        link.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        link.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        verify(gatt, times(1)).requestMtu(any())
        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.LINK_UP)
    }

    @Test
    fun `an acceptance of the password the pump repeats does not subscribe twice`() {
        withPasswordService(gatt)
        val link = upToServices()
        link.onDescriptorWrite(gatt, authDescriptor, BluetoothGatt.GATT_SUCCESS)
        link.onCharacteristicWrite(gatt, authWrite, BluetoothGatt.GATT_SUCCESS)

        link.onCharacteristicChanged(gatt, authNotify, byteArrayOf(Atc3BtPassword.ACCEPTED))
        link.onCharacteristicChanged(gatt, authNotify, byteArrayOf(Atc3BtPassword.ACCEPTED))

        @Suppress("DEPRECATION")
        verify(gatt, times(1)).writeDescriptor(dataDescriptor)
    }

    @Test
    fun `a password the pump refuses ends the link and is reported, not connected`() {
        withPasswordService(gatt)
        val link = upToServices()
        link.onDescriptorWrite(gatt, authDescriptor, BluetoothGatt.GATT_SUCCESS)
        link.onCharacteristicWrite(gatt, authWrite, BluetoothGatt.GATT_SUCCESS)

        link.onCharacteristicChanged(gatt, authNotify, byteArrayOf(0x01))

        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.IDLE)
        verify(callback).onAuthenticationRejected()
        verify(callback).onDisconnected()
        verify(callback, never()).onConnected()
    }

    @Test
    fun `the end of a link that was there is reported once`() {
        val link = upToServices()
        link.onDescriptorWrite(gatt, dataDescriptor, BluetoothGatt.GATT_SUCCESS)

        ble.disconnect()
        ble.disconnect()

        verify(callback, times(1)).onDisconnected()
        verify(gatt, times(1)).close()
        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.IDLE)
    }

    @Test
    fun `a link that ends while the stack is handing it over is closed, not left open`() {
        // The phone's Bluetooth goes off in the moment between asking for the link and the stack
        // handing it over: the link is reported ended then, and what the stack hands over after has
        // nothing to belong to. Kept, it would stay open, and a second attempt would lose it.
        val started = ble.startLink { ble.disconnect(); gatt }

        assertThat(started).isFalse()
        verify(gatt).close()
        verify(callback, times(1)).onDisconnected()
        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.IDLE)
        // What the stack handed may still be up on the radio: no new link at once.
        assertThat(ble.backoffRemainingMs).isAtLeast(Atc3Backoff.RELEASE_SETTLE_MS)
    }

    @Test
    fun `a link is no longer connected by the time it is being closed`() {
        // Nothing may find the link usable once its end has begun: a write would go to a closing link.
        val link = upToServices()
        link.onDescriptorWrite(gatt, dataDescriptor, BluetoothGatt.GATT_SUCCESS)
        var connectedWhileClosing: Boolean? = null
        doAnswer { connectedWhileClosing = ble.isConnected || ble.isConnecting; null }.whenever(gatt).close()

        ble.disconnect()

        assertThat(connectedWhileClosing).isFalse()
        assertThat(ble.write(byteArrayOf(1))).isFalse()
    }

    @Test
    fun `a late connected or disconnected of an ended link does not touch the next one`() {
        // The stack can still report on a link it has been told to close. Taken for the next link,
        // a late "connected" would set it up from the wrong client, and a late "disconnected" would end it.
        val old = start(gatt)
        ble.disconnect()
        val next = stack()
        start(next)

        old.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        old.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.CONNECTING)
        verify(gatt, never()).requestMtu(any())
        verify(next, never()).requestMtu(any())
        verify(next, never()).close()
        verify(callback, times(1)).onDisconnected()
    }

    @Test
    fun `data and a refusal that arrive for a link that has ended are not passed on`() {
        // Bytes from a closed link would be taken for the answer to the next link's request, and a
        // late refusal would count against a password the next link may be presenting right now.
        withPasswordService(gatt)
        val old = upToServices()
        ble.disconnect()
        start(stack())

        old.onCharacteristicChanged(gatt, notify, byteArrayOf(0x55, 0x01))
        old.onCharacteristicChanged(gatt, authNotify, byteArrayOf(0x01))

        verify(callback, never()).onDataReceived(any())
        verify(callback, never()).onAuthenticationRejected()
        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.CONNECTING)
    }

    @Test
    fun `a link is connecting through every setup step and connected only when ready`() {
        withPasswordService(gatt)
        val seen = mutableListOf<Pair<Boolean, Boolean>>()
        fun look() = seen.add(ble.isConnecting to ble.isConnected)
        val link = start(gatt)
        look()
        link.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        look()
        link.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        look()
        link.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        look()
        link.onDescriptorWrite(gatt, authDescriptor, BluetoothGatt.GATT_SUCCESS)
        link.onCharacteristicWrite(gatt, authWrite, BluetoothGatt.GATT_SUCCESS)
        link.onCharacteristicChanged(gatt, authNotify, byteArrayOf(Atc3BtPassword.ACCEPTED))
        look()
        link.onDescriptorWrite(gatt, dataDescriptor, BluetoothGatt.GATT_SUCCESS)
        look()

        assertThat(seen).containsExactly(
            true to false, true to false, true to false, true to false, true to false, false to true
        ).inOrder()
    }

    @Test
    fun `asking for a link while one is under way asks the stack for no second`() {
        start(gatt)
        var asked = false

        assertThat(ble.startLink { asked = true; stack() }).isTrue()

        assertThat(asked).isFalse()
    }

    @Test
    fun `a stack that throws leaves no attempt behind and no end to report`() {
        // The caller is told by the answer; an attempt left current would hold the link "connecting" until the watchdog.
        assertThat(ble.startLink { throw IllegalStateException("the stack is gone") }).isFalse()

        assertThat(ble.linkState).isEqualTo(Atc3BLE.LinkState.IDLE)
        verify(callback, never()).onDisconnected()
        start(gatt)
    }

    @Test
    fun `a write waits for the report of its own link`() {
        val link = ready()
        val result = worker.submit<Boolean> { ble.write(byteArrayOf(1, 2)) }
        @Suppress("DEPRECATION")
        verify(gatt, timeout(2_000)).writeCharacteristic(write)
        assertThat(result.isDone).isFalse()

        link.onCharacteristicWrite(gatt, write, BluetoothGatt.GATT_SUCCESS)

        assertThat(result.get(2, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a write waiting for its report fails at once when its link ends`() {
        ready()
        val result = worker.submit<Boolean> { ble.write(byteArrayOf(1, 2)) }
        @Suppress("DEPRECATION")
        verify(gatt, timeout(2_000)).writeCharacteristic(write)

        ble.disconnect()

        assertThat(result.get(2, TimeUnit.SECONDS)).isFalse()
    }

    @Test
    fun `a link the driver lets go of is not asked for again for a moment`() {
        // Asked for again at once, the stack hands back the link still up on the radio, and the attempt hangs.
        ready()

        ble.disconnect()

        assertThat(ble.backoffRemainingMs).isIn(Range.openClosed(0L, Atc3Backoff.RELEASE_SETTLE_MS))
    }

    @Test
    fun `a link the stack reports gone may be asked for again at once`() {
        val link = ready()

        link.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        assertThat(ble.backoffRemainingMs).isEqualTo(0L)
    }

    /** A link set up to ready, on a pump without a password service. */
    private fun ready(): BluetoothGattCallback {
        val link = upToServices()
        link.onDescriptorWrite(gatt, dataDescriptor, BluetoothGatt.GATT_SUCCESS)
        assertThat(ble.isConnected).isTrue()
        return link
    }

    /** Ask for a link to [stack]; what the stack calls back for that link. */
    private fun start(stack: BluetoothGatt): BluetoothGattCallback {
        var linkCallback: BluetoothGattCallback? = null
        assertThat(ble.startLink { linkCallback = it; stack }).isTrue()
        return linkCallback!!
    }

    /** From asking for the link to the services found, as the stack reports them. */
    private fun upToServices(): BluetoothGattCallback {
        val link = start(gatt)
        link.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        link.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        link.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        return link
    }

    /** Timers that run only when a test runs them: each scheduled task with its delay. */
    private fun manualScheduler(): MutableList<Pair<Runnable, Long>> {
        val tasks = mutableListOf<Pair<Runnable, Long>>()
        val scheduler = mock<ScheduledExecutorService>()
        whenever(scheduler.schedule(any<Runnable>(), any(), any())).thenAnswer {
            tasks += it.getArgument<Runnable>(0) to it.getArgument<Long>(1)
            mock<ScheduledFuture<*>>()
        }
        ble.scheduler = scheduler
        return tasks
    }

    /** A Bluetooth client that starts whatever it is asked and offers the pump's data services. */
    private fun stack(): BluetoothGatt = mock<BluetoothGatt>().also {
        whenever(it.requestMtu(any())).thenReturn(true)
        whenever(it.discoverServices()).thenReturn(true)
        @Suppress("DEPRECATION")
        whenever(it.writeDescriptor(any())).thenReturn(true)
        @Suppress("DEPRECATION")
        whenever(it.writeCharacteristic(any())).thenReturn(true)
        service(it, GattAttributes.serviceNotifyUuid, notify)
        service(it, GattAttributes.serviceWriteUuid, write)
    }

    private fun withPasswordService(stack: BluetoothGatt) = service(stack, GattAttributes.serviceAuthUuid, authNotify, authWrite)

    private fun service(stack: BluetoothGatt, uuid: UUID, vararg characteristics: BluetoothGattCharacteristic) {
        val service = mock<BluetoothGattService>()
        for (c in characteristics) {
            val id = c.uuid
            whenever(service.getCharacteristic(id)).thenReturn(c)
        }
        whenever(stack.getService(uuid)).thenReturn(service)
    }

    private fun characteristic(uuid: UUID, descriptor: BluetoothGattDescriptor? = null): BluetoothGattCharacteristic =
        mock<BluetoothGattCharacteristic>().also {
            whenever(it.uuid).thenReturn(uuid)
            if (descriptor != null) whenever(it.getDescriptor(GattAttributes.characteristicConfigDescriptor)).thenReturn(descriptor)
        }

    private fun descriptor(): BluetoothGattDescriptor = mock()
}
