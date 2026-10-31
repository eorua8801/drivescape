package app.drivescape.drive

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs

/**
 * 차량 신호 수집 계층.
 * - Pleos Vehicle SDK: 와이퍼, 방향지시등, 브레이크 페달
 * - AAOS 표준 차량 속성(CarPropertyManager): 속도, 기어
 *   (Pleos Vehicle SDK의 Gear API는 "Google GEAR_SELECTION 속성을 쓰라"며 deprecated)
 * - CarUxRestrictionsManager: 주행 중 UI 제한
 *
 * 지원하지 않는 신호는 상태를 UNSUPPORTED로 두고 해당 레이어만 꺼진 채로 동작한다.
 */
class VehicleSignals(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null

    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    @Volatile private var wiperOn = false

    fun start() {
        startPleos()
        startCar()
    }

    // ---------------- Pleos Vehicle SDK ----------------

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) { onWiperState(state) }
        override fun onFailed(e: Exception) { fail("wiper", e); DriveHub.wiperStatus = SignalStatus.UNSUPPORTED }
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) { onTurn(state) }
        override fun onFailed(e: Exception) { fail("turn", e); DriveHub.turnStatus = SignalStatus.UNSUPPORTED }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { onBrake(status) }
        override fun onFailed(e: Exception) { fail("brake", e); DriveHub.brakeStatus = SignalStatus.UNSUPPORTED }
    }

    private fun startPleos() {
        val v = try {
            Vehicle(context).also { it.initialize() }
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos Vehicle SDK 초기화 실패", e)
            DriveHub.wiperStatus = SignalStatus.UNSUPPORTED
            DriveHub.turnStatus = SignalStatus.UNSUPPORTED
            DriveHub.brakeStatus = SignalStatus.UNSUPPORTED
            return
        }
        vehicle = v

        guard("wiper") {
            val w = v.getWiper()
            w.registerWiperState(windshield, wiperListener)
            w.getCurrentWiperState(windshield, ::onWiperState) { e -> fail("wiper get", e) }
        }
        guard("turn") {
            val t = v.getTurnSignal()
            t.registerTurnSignalState(turnListener)
            t.getCurrentTurnSignalState(::onTurn) { e -> fail("turn get", e) }
        }
        guard("brake") {
            val b = v.getBrake()
            b.registerBrakePedalStatus(brakeListener)
            b.getCurrentBrakePedalStatus(::onBrake) { e -> fail("brake get", e) }
        }
    }

    private fun onWiperState(state: WiperState?) {
        DriveHub.wiperStatus = SignalStatus.LIVE
        wiperOn = state == WiperState.ON
        if (!wiperOn) {
            DriveHub.update { it.copy(wiper = 0) }
        } else {
            DriveHub.update { if (it.wiper == 0) it.copy(wiper = 1) else it }
            pollWiperSwitch()
        }
    }

    /** 와이퍼 단수(저속/고속)는 변경 알림이 없어 켜져 있는 동안 주기적으로 조회한다. */
    private val wiperPoll = object : Runnable {
        override fun run() { pollWiperSwitch() }
    }

    private fun pollWiperSwitch() {
        main.removeCallbacks(wiperPoll)
        if (!wiperOn) return
        guard("wiper switch") {
            vehicle?.getWiper()?.getCurrentWiperSwitch(windshield, { sw ->
                val level = wiperLevel(sw)
                if (wiperOn) DriveHub.update { it.copy(wiper = level) }
            }) { e -> fail("wiper switch get", e) }
        }
        main.postDelayed(wiperPoll, 1500)
    }

    private fun wiperLevel(sw: WiperSwitch?): Int = when (sw) {
        null, WiperSwitch.OFF, WiperSwitch.OTHER, WiperSwitch.SERVICE -> 1
        WiperSwitch.CONTINUOUS_LEVEL_2, WiperSwitch.CONTINUOUS_LEVEL_3,
        WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5 -> 2
        else -> 1
    }

    private fun onTurn(state: TurnSignalValue?) {
        DriveHub.turnStatus = SignalStatus.LIVE
        val v = when (state) { TurnSignalValue.LEFT -> -1; TurnSignalValue.RIGHT -> 1; else -> 0 }
        DriveHub.update { it.copy(turn = v) }
    }

    private fun onBrake(status: Boolean?) {
        DriveHub.brakeStatus = SignalStatus.LIVE
        DriveHub.update { it.copy(brake = status == true) }
    }

    // ---------------- AAOS Car API ----------------

    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> onSpeed(value.value as? Float)
                VehiclePropertyIds.GEAR_SELECTION -> onGear(value.value as? Int)
            }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "car property error $propertyId")
        }
    }

    private fun startCar() {
        val c = try { Car.createCar(context) } catch (e: Throwable) { Log.w(TAG, "Car API 없음", e); null }
        if (c == null) {
            DriveHub.speedStatus = SignalStatus.UNSUPPORTED
            DriveHub.gearStatus = SignalStatus.UNSUPPORTED
            return
        }
        car = c
        props = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        ux?.let { m ->
            guard("ux") {
                DriveHub.restricted = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
                m.registerListener { r -> DriveHub.restricted = r.isRequiresDistractionOptimization }
            }
        }
        restartCarProperties()
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // registerCallback은 API 35에서 deprecated지만, Pleos 에뮬레이터(Android 14, API 34)에는
    // 대체 API(subscribePropertyEvents)가 없어 그대로 쓴다.
    @Suppress("DEPRECATION")
    fun restartCarProperties() {
        val pm = props ?: return
        guard("unregister") { pm.unregisterCallback(propCallback) }

        if (!granted(Car.PERMISSION_SPEED)) {
            DriveHub.speedStatus = SignalStatus.NO_PERMISSION
        } else guard("speed") {
            val ok = pm.registerCallback(propCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)
            DriveHub.speedStatus = if (ok) SignalStatus.LIVE else SignalStatus.UNSUPPORTED
            if (ok) guard("speed get") {
                onSpeed(pm.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)?.value)
            }
        }

        if (!granted(Car.PERMISSION_POWERTRAIN)) {
            DriveHub.gearStatus = SignalStatus.NO_PERMISSION
        } else guard("gear") {
            val ok = pm.registerCallback(propCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            DriveHub.gearStatus = if (ok) SignalStatus.LIVE else SignalStatus.UNSUPPORTED
            if (ok) guard("gear get") {
                onGear(pm.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)?.value)
            }
        }
    }

    private fun onSpeed(metersPerSec: Float?) {
        if (metersPerSec == null) return
        DriveHub.speedStatus = SignalStatus.LIVE
        DriveHub.update { it.copy(speed = abs(metersPerSec) * 3.6f) }
    }

    private fun onGear(gear: Int?) {
        if (gear == null) return
        DriveHub.gearStatus = SignalStatus.LIVE
        DriveHub.update { it.copy(gear = if (gear == VehicleGear.GEAR_PARK) Gear.P else Gear.D) }
    }

    // ---------------- util ----------------

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.w(TAG, "$what 실패", e) }
    }

    private fun fail(what: String, e: Exception) { Log.w(TAG, "$what: ${e.message}") }

    companion object { private const val TAG = "VehicleSignals" }
}
