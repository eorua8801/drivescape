package app.drivescape.drive

import android.content.Context
import app.drivescape.music.SoundSet
import java.util.Calendar

/** 신호 하나의 출처·지원 상태 (화면 표시용). */
enum class SignalStatus { WAITING, LIVE, UNSUPPORTED, NO_PERMISSION }

/**
 * 앱 전체가 공유하는 주행 상태 허브.
 * - 차량 신호(VehicleSignals)가 [vehicle]을 갱신한다.
 * - 데모 시나리오가 켜져 있으면 시나리오 상태가 우선한다.
 * - 렌더 스레드는 [current]만 읽는다.
 */
object DriveHub {
    @Volatile var vehicle = DriveState(); internal set

    @Volatile var speedStatus = SignalStatus.WAITING; internal set
    @Volatile var gearStatus = SignalStatus.WAITING; internal set
    @Volatile var wiperStatus = SignalStatus.WAITING; internal set
    @Volatile var turnStatus = SignalStatus.WAITING; internal set
    @Volatile var brakeStatus = SignalStatus.WAITING; internal set
    /** 내비 연동: WAITING = 연결됨·경로 없음, LIVE = 경로 안내 중 */
    @Volatile var naviStatus = SignalStatus.WAITING; internal set

    /** 주행 제한(CarUxRestrictions)으로 UI를 줄여야 하는지 */
    @Volatile var restricted = false; internal set

    /** 시연 모드: 화면에 큰 자막으로 "지금 무엇에 반응하는지"를 보여 준다 (영상 촬영용) */
    @Volatile var demoCaptions = false

    /** null = 시계로 자동, true/false = 정차 중 수동 지정 */
    @Volatile var nightOverride: Boolean? = null

    @Volatile var scenario: Scenario? = null; private set

    var sets: List<SoundSet> = emptyList(); private set
    @Volatile var setIndex = 0; private set
    val currentSet: SoundSet? get() = sets.getOrNull(setIndex)

    private var signals: VehicleSignals? = null
    var navi: NaviSignals? = null; private set

    @Synchronized
    fun init(context: Context) {
        if (signals != null) return
        val app = context.applicationContext
        sets = SoundSet.loadAll(app)
        signals = VehicleSignals(app).also { it.start() }
        navi = NaviSignals(app).also { it.start() }
    }

    /** 권한을 새로 받은 뒤 차량 속성 구독을 다시 시도한다. */
    fun onPermissionsChanged() { signals?.restartCarProperties() }

    fun nightByClock(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 19 || h < 6
    }

    fun isNight() = nightOverride ?: nightByClock()

    fun current(): DriveState {
        scenario?.let { sc ->
            val s = sc.state()
            if (sc.finished) scenario = null
            return s
        }
        return vehicle.copy(night = isNight())
    }

    fun startScenario() { scenario = Scenario(isNight()) }
    fun stopScenario() { scenario = null }

    fun nextSet(delta: Int = 1) {
        if (sets.isEmpty()) return
        setIndex = ((setIndex + delta) % sets.size + sets.size) % sets.size
    }

    fun selectSet(id: String): Boolean {
        val i = sets.indexOfFirst { it.id == id }
        if (i < 0) return false
        setIndex = i
        return true
    }

    internal fun update(block: (DriveState) -> DriveState) {
        synchronized(this) { vehicle = block(vehicle) }
    }
}
