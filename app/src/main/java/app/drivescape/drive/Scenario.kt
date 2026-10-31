package app.drivescape.drive

import android.os.SystemClock
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 데모 시나리오: 12분 거리를 86초로 압축한 주행 (프로토타입 SCENE과 같은 대본).
 * 차량 신호 대신 이 대본이 주행 상태를 만든다. 정차 중에만 시작할 수 있다.
 */
class Scenario(
    private val night: Boolean,
    /** 경과 시간(초). 기본은 실제 시계, 오프라인 렌더링 테스트에서는 오디오 시간을 넣는다. */
    private val clock: () -> Double = { SystemClock.elapsedRealtime() / 1000.0 }
) {
    private class Ev(
        val t: Double,
        val gear: Gear? = null, val speed: Float? = null, val wiper: Int? = null,
        val turn: Int? = null, val brake: Boolean? = null,
        val ramp: Triple<Float, Float, Double>? = null,
        val cap: String? = null, val end: Boolean = false
    )

    private val script = listOf(
        Ev(0.0, gear = Gear.P, speed = 0f, wiper = 0, turn = 0, brake = false, cap = "주차 중 · 정차 앰비언트"),
        Ev(5.0, gear = Gear.D, cap = "기어 D · 출발"),
        Ev(6.0, ramp = Triple(0f, 35f, 7.0)),
        Ev(14.0, ramp = Triple(35f, 70f, 6.0), cap = "시내 주행"),
        Ev(20.0, wiper = 1, cap = "비가 오기 시작 · 와이퍼 저속"),
        Ev(27.0, wiper = 2, ramp = Triple(70f, 92f, 5.0), cap = "폭우 속 고속도로 · 와이퍼 고속"),
        Ev(34.0, turn = 1, cap = "오른쪽으로 차선 변경"),
        Ev(40.0, turn = 0),
        Ev(43.0, brake = true, ramp = Triple(92f, 45f, 3.0), cap = "앞차 감속 · 브레이크"),
        Ev(46.5, brake = false),
        Ev(47.0, wiper = 1, cap = "비가 잦아듦 · 도착 빌드업 시작"),
        Ev(53.0, wiper = 0, cap = "비 그침 · 목적지가 가까워짐"),
        Ev(58.0, turn = -1, ramp = Triple(45f, 25f, 4.0), cap = "좌회전 준비"),
        Ev(64.0, turn = 0),
        Ev(66.0, ramp = Triple(25f, 0f, 5.0), cap = "감속"),
        Ev(70.0, cap = "도착 · 차임"),
        Ev(74.0, gear = Gear.P, cap = "기어 P · 주차"),
        Ev(LENGTH, end = true)
    )

    private class ActiveRamp(val from: Float, val to: Float, val t0: Double, val dur: Double)

    private val t0 = clock()
    private var i = 0
    private val ramps = ArrayList<ActiveRamp>()
    private var state = DriveState(gear = Gear.P, speed = 0f, remainingMin = 12f, night = night)

    @Volatile var caption: String = script[0].cap!!; private set
    @Volatile var finished = false; private set

    val elapsed: Double get() = clock() - t0

    private fun ease(p: Double) = if (p < 0.5) 2 * p * p else 1 - (-2 * p + 2).pow(2) / 2

    /** 현재 시점의 주행 상태 (렌더 스레드에서 블록마다 호출). */
    @Synchronized
    fun state(): DriveState {
        val sec = elapsed
        var s = state
        while (i < script.size && script[i].t <= sec) {
            val e = script[i++]
            s = s.copy(
                gear = e.gear ?: s.gear, speed = e.speed ?: s.speed, wiper = e.wiper ?: s.wiper,
                turn = e.turn ?: s.turn, brake = e.brake ?: s.brake
            )
            e.ramp?.let { ramps += ActiveRamp(it.first, it.second, e.t, it.third) }
            e.cap?.let { caption = it }
            if (e.end) finished = true
        }
        val it = ramps.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val p = ((sec - r.t0) / r.dur).coerceIn(0.0, 1.0)
            s = s.copy(speed = (r.from + (r.to - r.from) * ease(p)).roundToInt().toFloat())
            if (p >= 1.0) it.remove()
        }
        val rem = (12 * (1 - sec / ARRIVE_AT) * 10).roundToInt() / 10f
        s = s.copy(remainingMin = maxOf(0f, rem))
        state = s
        return s
    }

    companion object {
        const val LENGTH = 86.0
        const val ARRIVE_AT = 70.0
    }
}
