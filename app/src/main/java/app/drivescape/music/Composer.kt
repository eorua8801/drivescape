package app.drivescape.music

import app.drivescape.audio.Instruments
import app.drivescape.audio.SR
import app.drivescape.audio.UiEvent
import app.drivescape.drive.DriveState
import app.drivescape.drive.Gear
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 음악 규칙 엔진. 주행 상태를 16분음표 단위의 음표로 바꾼다.
 * 기준 구현(Drivescape_프로토타입.html)의 scheduleStep / scheduleChime 과 같은 규칙.
 */
class Composer(private val inst: Instruments, private val emit: (UiEvent) -> Unit) {
    var set: SoundSet? = null

    private var step = 0
    /** 다음에 만들 16분음표가 마디 첫 박인지 (세트는 마디 경계에서만 바꾼다) */
    val atBarStart get() = step % 16 == 0
    private var barCount = -1
    private var mode: SoundSet.Mode? = null
    private var chordDeg = 0
    private var arpIdx = 0
    private var arrived = false
    /** 전체 시간이 흐르는 동안 쓰는 난수 (Math.random 대응) */
    private val rnd = app.drivescape.audio.Rng(0x1F2E3D4C)

    fun reset() {
        step = 0; barCount = -1; arpIdx = 0
    }

    private val s get() = set!!

    fun bpm(st: DriveState): Double =
        if (st.gear == Gear.P) s.tempo.park
        else s.tempo.base + st.speed.toDouble().coerceIn(0.0, s.tempo.maxKmh) * s.tempo.perKmh

    /** -1 주차, 0 정지, 1 저속, 2 시내, 3 고속 */
    fun level(st: DriveState): Int {
        if (st.gear == Gear.P) return -1
        val v = st.speed
        val l = s.levels
        return if (v < l.slowBelow) 0 else if (v < l.cityBelow) 1 else if (v < l.highwayBelow) 2 else 3
    }

    fun build(st: DriveState): Double {
        val rem = st.remainingMin ?: return 0.0
        return if (st.gear == Gear.D) ((5 - rem) / 5.0).coerceIn(0.0, 1.0) else 0.0
    }

    private fun midiOf(deg: Int, scale: IntArray, base: Int): Int {
        val n = scale.size
        val o = floor(deg.toDouble() / n).toInt()
        val i = ((deg % n) + n) % n
        return base + 12 * o + scale[i]
    }

    private fun fitRange(m: Int, lo: Int, hi: Int): Int {
        var x = m
        while (x < lo) x += 12
        while (x > hi) x -= 12
        return x
    }

    private fun curMode(st: DriveState) = mode ?: (if (st.night) s.night else s.day)

    /** 코드 구성음 (세트의 보이싱, lo~hi 음역으로 접고 겹치는 음은 뺀다) */
    private fun chordTones(deg: Int, m: SoundSet.Mode, style: SoundSet.Style, lo: Int, hi: Int): List<Int> =
        style.padVoicing.map { fitRange(midiOf(deg + it, m.scale, s.roots.pad), lo, hi) }.distinct()

    private fun bassMidi(deg: Int, st: DriveState) = fitRange(midiOf(deg, curMode(st).scale, s.roots.bass), 36, 47)

    private fun arpTones(deg: Int, st: DriveState): List<Int> {
        val sc = curMode(st).scale
        return intArrayOf(0, 2, 4, 6, 7).map { fitRange(midiOf(deg + it, sc, s.roots.arp), 67, 86) }.distinct().sorted()
    }

    /** 16분음표 하나를 예약한다. stepStart = 시작 샘플, sec16 = 16분음표 길이(초). */
    fun scheduleStep(stepStart: Long, sec16: Double, st: DriveState, setDelay: (Double) -> Unit) {
        val s16 = step % 16
        val lv = level(st)
        val b = build(st)
        val beat = sec16 * 4
        val style = s.style
        inst.style = style
        // 스윙: 홀수 16분음표(뒷박)를 조금 늦춘다. 마디 시작 같은 짝수 박은 그대로.
        val t = if (s16 % 2 == 1 && style.swing > 0) stepStart + (style.swing * sec16 * SR).toLong() else stepStart

        if (style.padStyle == "epiano" && s16 != 0) {
            val tones = chordTones(chordDeg, curMode(st), style, 55, 74)
            val steps = style.compSteps
            if (steps == null) {
                // 기본 컴핑: 2박 뒤(6)와 4박 앞 꾸밈(11)에 같은 코드를 가볍게 다시 친다
                if (lv >= 1 && s16 == 6) inst.epiano(t, tones, if (st.brake) 0.35 else 0.55, sec16 * 6, 0.3)
                if (lv >= 2 && s16 == 11 && !st.brake) inst.epiano(t, tones, 0.35, sec16 * 3, 0.3)
            } else if (lv >= 1 && s16 in steps) {
                // 세트가 정한 위치에 짧게 끊어 치는 컴핑. 저속에선 절반만, 브레이크 땐 약하게
                if (lv >= 2 || s16 < 8) {
                    inst.epiano(t, tones, style.compVel * (if (st.brake) 0.6 else 1.0), sec16 * style.compLen, 0.25)
                }
            }
        }

        if (s16 == 0) {
            barCount++
            val m = if (st.night) s.night else s.day
            mode = m
            val prog = m.progression
            val ci = ((barCount % prog.size) + prog.size) % prog.size
            chordDeg = prog[ci]
            val barDur = sec16 * 16
            val cutoff = (if (st.night) 650.0 else 950.0) + (if (st.gear == Gear.D) st.speed * 11.0 else 0.0) + b * 1800
            if (style.padStyle == "epiano") {
                val tones = chordTones(chordDeg, m, style, 55, 74).toMutableList()
                if (b >= 0.8) tones += fitRange(midiOf(chordDeg + 4, m.scale, s.roots.pad), 74, 86)
                val park = st.gear == Gear.P
                inst.epiano(t, tones, if (park) 0.8 else 1.0, barDur * (if (park) 1.4 else 0.9), if (park) 0.5 else 0.3)
                if (style.bed > 0) {
                    // 건반 아래에 마디 내내 이어지는 어둡고 부드러운 패드
                    val bedTones = chordTones(chordDeg, m, style, 55, 71)
                    inst.pad(t, bedTones, barDur, minOf(cutoff, 900.0),
                        gain = (if (park) 0.05 else 0.042) * style.bed,
                        send = if (park) 0.55 else 0.35)
                }
            } else {
                val tones = chordTones(chordDeg, m, style, 55, 71).toMutableList()
                if (b >= 0.8) tones += fitRange(midiOf(chordDeg + 4, m.scale, s.roots.pad), 74, 86)
                inst.pad(t, tones, barDur, cutoff,
                    gain = if (st.gear == Gear.P) 0.05 else 0.042,
                    send = if (st.gear == Gear.P) 0.55 else 0.35)
            }
            setDelay(beat * 0.75)
            emit(UiEvent.Bar(t, m.chords[ci % m.chords.size], barCount + 1))
            if (b >= 0.8 && lv >= 0) inst.crash(t)
            if (lv == -1) inst.bass(t, bassMidi(chordDeg, st), barDur * 0.9, 0.35)
        }
        if (s16 % 4 == 0) emit(UiEvent.Beat(t, s16 / 4))
        val root = bassMidi(chordDeg, st)

        if (lv >= 0) {
            val bl = if (st.brake) min(lv, 1) else lv
            if (s16 in s.bassPattern[bl]) {
                val oct = if (bl == 3 && s16 % 4 == 2) 12 else 0
                val len = when (bl) { 0 -> sec16 * 16; 1 -> sec16 * 8; else -> sec16 * 2 }
                inst.bass(t, root + oct, len, if (bl == 0) 0.55 else 0.85)
            }
        }
        if (lv >= 1) {
            if (s16 in s.kickPattern[lv] && !(st.brake && s16 != 0)) inst.kick(t, if (s16 == 0) 1.0 else 0.8)
            if (!st.brake) {
                if (lv >= 2 && (s16 == 4 || s16 == 12)) inst.snare(t, 0.9)
                if (lv == 1 && s16 == 12) inst.snare(t, 0.35)
                if (lv == 2 && s16 % 2 == 0) inst.hat(t, if (s16 % 4 == 2) 1.0 else 0.55, s16 == 14 && barCount % 2 == 1)
                if (lv == 3) inst.hat(t, if (s16 % 4 == 2) 1.0 else if (s16 % 2 == 0) 0.6 else 0.35, s16 == 14)
                if (lv >= 2 && barCount % 4 == 3 && s16 >= 13) inst.snare(t, 0.4 + 0.15 * (s16 - 13))
            }
        }

        var rate = 0
        if (lv >= 2) rate = 1
        if (lv == 3) rate = 2
        if (b > 0) rate = max(rate, if (b >= 0.75) 3 else 2)
        if (st.brake) rate = min(rate, 1)
        val every = when (rate) { 3 -> 1; 2 -> 2; 1 -> 4; else -> 0 }
        if (every != 0 && s16 % every == 0) {
            val tones = arpTones(chordDeg, st)
            val n = tones[arpIdx % tones.size]; arpIdx++
            inst.pluck(t, n, if (s16 % 4 == 0) 1.0 else 0.65, if (arpIdx % 2 == 1) -0.35 else 0.35)
        }

        if (lv == -1 && (s16 == 0 || s16 == 8) && rnd.next() < 0.4) {
            val tones = arpTones(chordDeg, st)
            inst.bell(t, tones[floor(rnd.next() * tones.size).toInt()], 0.55, rnd.next() * 1.2 - 0.6, 3.2)
        }
        if (st.wiper > 0 && rnd.next() < (if (st.wiper == 1) 0.16 else 0.32)) {
            val at = inst.offset(t, rnd.next() * sec16 * 0.6)
            inst.drop(at, midiOf(floor(rnd.next() * 10).toInt(), curMode(st).scale, s.roots.drop), rnd.next() * 1.6 - 0.8)
        }
        if (st.wiper == 1 && s16 % 8 == 0) inst.swish(t, beat * 1.6, if ((step / 8) % 2 == 1) 1 else -1)
        if (st.wiper == 2 && s16 % 4 == 0) inst.swish(t, beat * 0.85, if ((step / 4) % 2 == 1) 1 else -1)
        if (st.turn != 0 && s16 % 2 == 0) inst.tick(t, s16 % 4 == 2, st.turn * 0.75, beat)

        step++
    }

    /** 도착 판정 (프로토타입 pump의 끝부분). 도착 차임을 울려야 하면 true. */
    fun checkArrival(st: DriveState): Boolean {
        val rem = st.remainingMin ?: return false
        if (st.gear == Gear.D && rem <= 0f && !arrived) { arrived = true; return true }
        if (rem > 0f) arrived = false
        return false
    }

    fun chime(t: Long, st: DriveState) {
        val sec16 = 60.0 / bpm(st) / 4
        val tones = arpTones(chordDeg, st)
        tones.forEachIndexed { i, m -> inst.bell(t + (i * sec16 * 2 * SR).toLong(), m, 0.7, -0.5 + i * 0.25, 3.0) }
        inst.bell(t + (tones.size * sec16 * 2 * SR).toLong(), tones[0] + 12, 0.9, 0.0, 4.5)
        inst.crash(t)
        emit(UiEvent.Arrive(t))
    }
}
