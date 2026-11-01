package app.drivescape.audio

import app.drivescape.drive.DriveState
import app.drivescape.drive.Gear
import app.drivescape.music.Composer
import app.drivescape.music.SoundSet
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/**
 * 사운드 엔진: 16분음표 시퀀서 + 신스 + 믹스 체인.
 *
 * 믹스 체인 (프로토타입과 동일):
 *   dry + 리버브(revOut) → 브레이크 로우패스(18kHz↔650Hz) → 마스터(0.85) → 컴프레서 → 출력
 *   아르페지오 버스 → dry + 점8분 딜레이(피드백 0.36, LP 3kHz, wet 0.45 → dry·리버브)
 *   빗소리(핑크 노이즈 HP450·LP4200) → dry + 리버브 0.35
 *
 * 렌더 스레드 하나에서만 호출한다. 주행 상태는 [stateProvider]로 블록마다 읽는다.
 */
class SynthEngine(private val stateProvider: () -> DriveState) {
    val rng = Rng(0x5EED1234)
    private val voices = ArrayList<Voice>(256)
    val events = ConcurrentLinkedQueue<UiEvent>()

    private val instruments = Instruments(this)
    val composer = Composer(instruments, ::emit)

    @Volatile var soundSet: SoundSet? = null

    /** 지금까지 렌더한 프레임 수 (엔진 절대 시간) */
    var frame = 0L; private set
    private var nextStep = 0.0
    private var running = false

    // ---- 마스터 체인 ----
    private val master = Ramp(0.0)
    private val comp = Compressor()
    private val brakeL = Biquad(FilterType.LOWPASS, 18000.0, 0.9)
    private val brakeR = Biquad(FilterType.LOWPASS, 18000.0, 0.9)
    private val brakeFreq = Smoothed(18000.0)
    private val reverb = Reverb.calibrated()
    private val revOut = Smoothed(0.7)

    private val delay = DelayLine(2.0)
    private val delayTime = Smoothed(0.5)
    private val dLpL = Biquad(FilterType.LOWPASS, 3000.0, 1.0)
    private val dLpR = Biquad(FilterType.LOWPASS, 3000.0, 1.0)

    private val pinkL = PinkNoise(Rng(0x13579BDF)); private val pinkR = PinkNoise(Rng(0x2468ACE0))
    private val rainHpL = Biquad(FilterType.HIGHPASS, 450.0, 1.0); private val rainHpR = Biquad(FilterType.HIGHPASS, 450.0, 1.0)
    private val rainLpL = Biquad(FilterType.LOWPASS, 4200.0, 1.0); private val rainLpR = Biquad(FilterType.LOWPASS, 4200.0, 1.0)
    private val rainLpFreq = Smoothed(4200.0)
    private val rainGain = Smoothed(0.0)

    // ---- 세트 질감: LP 레코드 잡음, 테이프 흔들림 ----
    private val vinylAmt = Smoothed(0.0)
    private val vinylRng = Rng(0x0BADCAFE)
    private val popL = Biquad(FilterType.BANDPASS, 2400.0, 0.8); private val popR = Biquad(FilterType.BANDPASS, 2400.0, 0.8)
    private val hiss = PinkNoise(Rng(0x7A7A7A7A))
    private val hissHp = Biquad(FilterType.HIGHPASS, 2000.0, 1.0)
    private val wobble = DelayLine(0.05)
    private val wobbleDepth = Smoothed(0.0)
    private val setGain = Smoothed(1.0)
    private var wobblePhase = 0.0
    private var flutterPhase = 0.0

    private val mix = Mix(BLOCK)

    /** 화면의 오실로스코프용 최근 출력 (모노) */
    val scope = FloatArray(SCOPE_SIZE)
    @Volatile var scopeWrite = 0; private set

    private var lastControls: Controls? = null
    private data class Controls(
        val wiper: Int, val brake: Boolean, val night: Boolean, val park: Boolean,
        val tone: Double, val vinyl: Double, val wobbleMs: Double, val outputGain: Double
    )

    fun add(v: Voice) { voices += v }
    fun emit(e: UiEvent) { events += e }

    fun start() {
        composer.set = soundSet
        composer.reset()
        running = true
        nextStep = frame + 0.08 * SR
        master.rampTo(0.85, 0.6)
        applyControls(stateProvider(), force = true)
    }

    /** 0.35초 페이드아웃. 끝나면 [isSilent]가 true. */
    fun fadeOut() {
        running = false
        events.clear()
        master.rampTo(0.0, 0.35)
    }

    val isSilent get() = !running && master.isIdle && master.value <= 0.0

    /** 프로토타입 applyControls: 와이퍼·브레이크·시간대·기어가 바뀌면 연속 파라미터 목표값을 옮긴다. */
    private fun applyControls(st: DriveState, force: Boolean = false) {
        // 지금 연주 중인 세트의 음색 (새 세트는 다음 마디에 composer로 넘어가면서 같이 바뀐다)
        val style = (composer.set ?: soundSet)?.style ?: SoundSet.Style()
        val c = Controls(st.wiper, st.brake, st.night, st.gear == Gear.P, style.toneCutoff, style.vinyl, style.wobbleMs, style.outputGain)
        if (!force && c == lastControls) return
        val prev = lastControls
        lastControls = c
        rainGain.setTarget(doubleArrayOf(0.0, 0.11, 0.2)[st.wiper.coerceIn(0, 2)], 0.8)
        rainLpFreq.setTarget(if (st.wiper == 2) 6500.0 else 4200.0, 0.5)
        if (force || prev?.brake != c.brake || prev?.tone != c.tone) {
            // 브레이크를 떼면 세트의 음색 한계(기본 18kHz)로 돌아간다
            brakeFreq.setTarget(if (st.brake) 650.0 else style.toneCutoff, if (st.brake) 0.07 else 0.35)
        }
        vinylAmt.setTarget(style.vinyl, 0.5)
        wobbleDepth.setTarget(style.wobbleMs / 1000.0, 0.5)
        if (force && prev == null) setGain.jump(style.outputGain) else setGain.setTarget(style.outputGain, 0.5)
        revOut.setTarget((if (st.night) 0.85 else 0.65) + st.wiper * 0.12 + (if (c.park) 0.2 else 0.0), 0.6)
    }

    /** out: 인터리브드 스테레오 float, frames 프레임 */
    fun render(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            renderBlock(out, done, n)
            done += n
        }
    }

    private fun renderBlock(out: FloatArray, offset: Int, n: Int) {
        val st = stateProvider()
        val set = soundSet
        if (running && set != null) {
            applyControls(st)   // 세트가 바뀌면 음색 한계·잡음·흔들림 목표값도 여기서 옮겨 간다
            val blockEnd = frame + n
            while (nextStep < blockEnd) {
                // 세트 전환은 마디 첫 박에서: 진행 중인 마디가 끊기지 않게 한다
                if (composer.set !== set && composer.atBarStart) composer.set = set
                val sec16 = 60.0 / composer.bpm(st) / 4
                composer.scheduleStep(max(frame, nextStep.toLong()), sec16, st) { delayTime.setTarget(it, 0.05) }
                nextStep += sec16 * SR
            }
            if (composer.checkArrival(st)) {
                composer.chime(max(nextStep.toLong(), frame + (0.05 * SR).toLong()), st)
            }
        }

        mix.clear()
        val it = voices.iterator()
        while (it.hasNext()) if (!it.next().render(mix, frame, n)) it.remove()

        var w = scopeWrite
        for (i in 0 until n) {
            if ((i and 31) == 0) {
                brakeL.setFreq(brakeFreq.value); brakeR.setFreq(brakeFreq.value)
                rainLpL.setFreq(rainLpFreq.value); rainLpR.setFreq(rainLpFreq.value)
            }
            // 아르페지오 버스 + 점8분 딜레이
            delay.read(delayTime.next())
            val yL = dLpL.process(delay.outL); val yR = dLpR.process(delay.outR)
            delay.write(mix.arpL[i] + yL * 0.36f, mix.arpR[i] + yR * 0.36f)
            var dryL = mix.dryL[i] + mix.arpL[i] + yL * 0.45f
            var dryR = mix.dryR[i] + mix.arpR[i] + yR * 0.45f
            var revL = mix.revL[i] + yL * 0.45f
            var revR = mix.revR[i] + yR * 0.45f

            // 빗소리
            rainLpFreq.next()
            val rg = rainGain.next().toFloat()
            if (rg > 1e-5f) {
                val pl = rainLpL.process(rainHpL.process(pinkL.next())) * rg
                val pr = rainLpR.process(rainHpR.process(pinkR.next())) * rg
                dryL += pl; dryR += pr; revL += pl * 0.35f; revR += pr * 0.35f
            }

            // LP 레코드 잡음: 드문드문 튀는 소리 + 아주 작은 히스 (세트가 켤 때만)
            val va = vinylAmt.next()
            if (va > 1e-4) {
                var pl = 0f; var pr = 0f
                if (vinylRng.next() < 7.0 / SR) {
                    val r = vinylRng.next()
                    val amp = (0.15 + 0.85 * r * r * r).toFloat()
                    val pan = vinylRng.next().toFloat()
                    pl = amp * (1 - pan); pr = amp * pan
                }
                val h = hissHp.process(hiss.next()) * 0.012f
                dryL += (popL.process(pl) * 0.09f + h) * va.toFloat()
                dryR += (popR.process(pr) * 0.09f + h) * va.toFloat()
            }

            reverb.process(revL, revR)
            val ro = revOut.next().toFloat()
            brakeFreq.next()
            val m = master.next().toFloat()
            var sL = brakeL.process(dryL + reverb.outL * ro) * m
            var sR = brakeR.process(dryR + reverb.outR * ro) * m

            // 테이프 흔들림: 짧은 딜레이를 천천히(0.6Hz)·빠르게(5.3Hz) 흔들어 음정이 살짝 출렁인다.
            // 기본 세트는 깊이 0 → 6ms 고정 지연만 생기고 소리는 그대로.
            wobblePhase += 0.6 / SR; if (wobblePhase >= 1) wobblePhase -= 1
            flutterPhase += 5.3 / SR; if (flutterPhase >= 1) flutterPhase -= 1
            val wd = wobbleDepth.next()
            wobble.read(0.006 + wd * SineTable.at(wobblePhase) + wd * 0.15 * SineTable.at(flutterPhase))
            wobble.write(sL, sR)
            sL = wobble.outL; sR = wobble.outR

            val sg = setGain.next().toFloat()
            comp.process(sL * sg, sR * sg)
            val oL = safety(comp.outL * TRIM); val oR = safety(comp.outR * TRIM)
            out[(offset + i) * 2] = oL
            out[(offset + i) * 2 + 1] = oR
            scope[w] = (oL + oR) * 0.5f
            w = (w + 1) and (SCOPE_SIZE - 1)
        }
        scopeWrite = w
        frame += n
    }

    fun currentBpm(): Double {
        val set = soundSet ?: return 0.0
        if (composer.set == null) composer.set = set
        return composer.bpm(stateProvider())
    }

    companion object {
        const val BLOCK = 128
        const val SCOPE_SIZE = 4096

        /** 웹 프로토타입(Chrome 오프라인 렌더링)과 86초 시나리오 평균 음량을 맞추는 보정 (-0.45dB) */
        private const val TRIM = 0.95f

        /** 0.9를 넘는 드문 피크만 부드럽게 누르는 안전 리미터 (디지털 클리핑 방지) */
        private fun safety(x: Float): Float {
            val a = abs(x)
            if (a <= 0.9f) return x
            val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
            return if (x < 0) -y else y
        }
    }
}
