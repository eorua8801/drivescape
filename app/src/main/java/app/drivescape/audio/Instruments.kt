package app.drivescape.audio

import app.drivescape.music.SoundSet
import kotlin.math.min
import kotlin.math.pow

/** 화면 표시용 레이어(프로토타입 meters 키와 동일). */
enum class Layer { PAD, BASS, DRUMS, ARP, RAIN, WIPER, TURN, BELL }

/** 렌더 스레드 → UI로 보내는 표시 이벤트. t는 엔진 절대 샘플 위치. */
sealed class UiEvent(val t: Long) {
    class Meter(t: Long, val layer: Layer, val v: Float) : UiEvent(t)
    class Bar(t: Long, val chord: String, val bar: Int) : UiEvent(t)
    class Beat(t: Long, val n: Int) : UiEvent(t)
    class Blink(t: Long, val durSec: Double) : UiEvent(t)
    class Arrive(t: Long) : UiEvent(t)
}

/**
 * 프로토타입의 악기 함수들(kick, snare, hat, crash, pad, bass, pluck, bell, drop, swish, tick).
 * 기본 세트의 수치는 기준 구현과 동일하고, 세트별 음색 차이는 [style]로 준다.
 * t = 시작 샘플(절대 위치), 길이·시간 = 초.
 */
class Instruments(private val engine: SynthEngine) {
    /** 지금 연주 중인 세트의 음색 (Composer가 스텝마다 넣어 준다) */
    var style = SoundSet.Style()

    private val rng get() = engine.rng
    private fun panOf(p: Double): Double? = if (p == 0.0) null else p
    private fun env() = Auto(1e-4).set(0.0, 1e-4)
    private fun sec(seconds: Double) = (seconds * SR).toLong()

    private fun meter(t: Long, layer: Layer, v: Double) = engine.emit(UiEvent.Meter(t, layer, v.toFloat()))

    private fun wave(name: String) = when (name) {
        "sine" -> Wave.SINE
        "triangle" -> Wave.TRIANGLE
        "saw" -> Wave.SAW
        else -> Wave.SQUARE
    }

    fun kick(t: Long, vel: Double) {
        val s = style
        engine.add(Voice(t, s.kickDecay + 0.03,
            oscs = arrayOf(Osc(Wave.SINE, 1.0)),
            freq = Auto(s.kickFreq).set(0.0, s.kickFreq).exp(0.13, s.kickEnd),
            gain = env().exp(0.004, 0.85 * vel * s.kickGain).exp(s.kickDecay, 1e-4),
            send = 0.04f))
        meter(t, Layer.DRUMS, vel)
    }

    private fun noiseHit(t: Long, type: FilterType, freq: Double, q: Double, dur: Double, gain: Double,
                         pan: Double = 0.0, send: Double = 0.0) {
        engine.add(Voice(t, min(2.0, dur + 0.05),
            noise = rng,
            filter = Biquad(type, freq, q),
            gain = env().exp(0.002, gain).exp(dur, 1e-4),
            pan = panOf(pan), send = send.toFloat()))
    }

    fun snare(t: Long, vel: Double) {
        val g = style.snareGain
        noiseHit(t, FilterType.BANDPASS, style.snareFreq, 0.9, 0.22, 0.3 * vel * g, send = 0.22)
        engine.add(Voice(t, 0.15,
            oscs = arrayOf(Osc(Wave.TRIANGLE, 1.0)),
            freq = Auto(220.0).set(0.0, 220.0).exp(0.08, 160.0),
            gain = env().exp(0.003, 0.22 * vel * g).exp(0.12, 1e-4),
            send = 0.12f))
        meter(t, Layer.DRUMS, 0.8 * vel)
    }

    fun hat(t: Long, vel: Double, open: Boolean) {
        noiseHit(t, FilterType.HIGHPASS, style.hatFreq, 0.6, if (open) 0.22 else 0.045, 0.1 * vel * style.hatGain, pan = 0.18, send = 0.05)
        meter(t, Layer.DRUMS, 0.45 * vel)
    }

    fun crash(t: Long) {
        noiseHit(t, FilterType.HIGHPASS, 5200.0, 0.5, 1.8, 0.12, send = 0.5)
        meter(t, Layer.DRUMS, 1.0)
    }

    fun pad(t: Long, tones: List<Int>, dur: Double, cutoff: Double, gain: Double, send: Double) {
        val oscs = ArrayList<Osc>(tones.size * 2)
        for (m in tones) for (c in intArrayOf(-7, 7)) {
            val cents = c + (rng.next() * 4 - 2)
            oscs += Osc(Wave.SAW, mtof(m.toDouble()) * 2.0.pow(cents / 1200.0))
        }
        val att = min(1.4, dur * 0.45); val rel = 2.2
        val g = gain * style.padGain
        engine.add(Voice(t, dur + rel + 0.05,
            oscs = oscs.toTypedArray(),
            filter = Biquad(FilterType.LOWPASS, cutoff, 0.6),
            gain = env().lin(att, g).set(dur, g).lin(dur + rel, 1e-4),
            send = send.toFloat()))
        meter(t, Layer.PAD, 1.0)
    }

    /**
     * 일렉트릭 피아노(로즈 계열) 코드: 음마다 기음 + 2·3배음 + 짧은 '틴' 어택.
     * 기음은 len 동안 약 -10dB까지만 줄며 울리다가 0.6초에 걸쳐 놓이고,
     * 배음일수록 빨리 사라져서 처음엔 또렷하고 점점 둥글어진다.
     */
    fun epiano(t: Long, tones: List<Int>, vel: Double, len: Double, send: Double) {
        // [배수, 크기, 길이 비율, len 끝에서 남는 비율]
        val partials = arrayOf(
            doubleArrayOf(1.0, 1.0, 1.0, 0.32),
            doubleArrayOf(2.0, 0.28, 0.4, 0.05),
            doubleArrayOf(3.0, 0.08, 0.15, 0.01),
            doubleArrayOf(4.02, 0.05, 0.03, 0.01)
        )
        tones.forEachIndexed { i, m ->
            val base = mtof(m.toDouble())
            val pan = if (tones.size > 1) -0.25 + 0.5 * i / (tones.size - 1) else 0.0
            val spread = (i * 0.004)   // 손가락으로 누르는 것처럼 아주 조금씩 어긋나게
            for (p in partials) {
                val d = len * p[2]
                val peak = 0.045 * vel * p[1] * style.padGain
                engine.add(Voice(t + sec(spread), d + 0.65,
                    oscs = arrayOf(Osc(Wave.SINE, base * p[0])),
                    gain = env().exp(0.006, peak).exp(d, peak * p[3]).exp(d + 0.6, 1e-4),
                    pan = panOf(pan), send = send.toFloat()))
            }
        }
        meter(t, Layer.PAD, vel)
    }

    fun bass(t: Long, midi: Int, dur: Double, vel: Double) {
        val f = mtof(midi.toDouble())
        engine.add(Voice(t, dur + 0.6,
            oscs = arrayOf(Osc(Wave.TRIANGLE, f), Osc(Wave.SINE, f)),
            filter = Biquad(FilterType.LOWPASS, style.bassCutoff, 1.0),
            gain = env().exp(0.01, 0.4 * vel * style.bassGain).target(dur * 0.6, 1e-4, dur * 0.25),
            send = 0.04f))
        meter(t, Layer.BASS, vel)
    }

    fun pluck(t: Long, midi: Int, vel: Double, pan: Double) {
        val s = style
        val f = mtof(midi.toDouble())
        // arpBend: 조금 낮은 음에서 밀어 올리며 시작한다 (가야금 줄을 뜯는 느낌)
        val bent = s.arpBend != 0.0
        val from = f * 2.0.pow(-s.arpBend / 12.0)
        engine.add(Voice(t, s.arpDecay + 0.05,
            oscs = arrayOf(Osc(wave(s.arpWave), if (bent) 1.0 else f)),
            freq = if (bent) Auto(from).set(0.0, from).exp(0.06, f) else null,
            filter = Biquad(FilterType.LOWPASS, s.arpCutoff, 2.0),
            filterFreq = Auto(s.arpCutoff).set(0.0, s.arpCutoff).exp(0.25, 700.0),
            gain = env().exp(0.004, 0.06 * vel * s.arpGain).exp(s.arpDecay, 1e-4),
            pan = panOf(pan), send = 0.14f, toArpBus = true))
        meter(t, Layer.ARP, vel)
    }

    fun bell(t: Long, midi: Int, vel: Double, pan: Double, len: Double) {
        val base = mtof(midi.toDouble())
        val partials = arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(2.76, 0.35), doubleArrayOf(5.4, 0.12))
        partials.forEachIndexed { i, p ->
            val d = len / (1 + i * 1.2)
            engine.add(Voice(t, d + 0.05,
                oscs = arrayOf(Osc(Wave.SINE, base * p[0])),
                gain = env().exp(0.006, 0.11 * vel * p[1]).exp(d, 1e-4),
                pan = panOf(pan), send = 0.45f))
        }
        meter(t, Layer.BELL, vel)
    }

    fun drop(t: Long, midi: Int, pan: Double) {
        val fq = mtof(midi.toDouble())
        engine.add(Voice(t, 0.25,
            oscs = arrayOf(Osc(Wave.SINE, 1.0)),
            freq = Auto(fq * 0.8).set(0.0, fq * 0.8).exp(0.025, fq),
            gain = env().exp(0.004, 0.08).exp(0.22, 1e-4),
            pan = panOf(pan), send = 0.5f))
        meter(t, Layer.RAIN, 0.9)
    }

    fun swish(t: Long, dur: Double, dir: Int) {
        engine.add(Voice(t, min(2.0, dur + 0.05),
            noise = rng,
            filter = Biquad(FilterType.BANDPASS, 700.0, 1.4),
            filterFreq = Auto(700.0).set(0.0, 700.0).exp(dur * 0.5, 2600.0).exp(dur, 800.0),
            gain = env().lin(dur * 0.35, 0.085).lin(dur, 1e-4),
            panAuto = Auto(-0.7 * dir).set(0.0, -0.7 * dir).lin(dur, 0.7 * dir),
            send = 0.2f))
        meter(t, Layer.WIPER, 1.0)
    }

    fun tick(t: Long, tock: Boolean, pan: Double, beat: Double) {
        val g = TURN_BOOST
        engine.add(Voice(t, 0.07,
            oscs = arrayOf(Osc(Wave.TRIANGLE, if (tock) 1350.0 else 2100.0)),
            filter = Biquad(FilterType.HIGHPASS, 900.0, 1.0),
            gain = env().exp(0.002, (if (tock) 0.1 else 0.17) * g).exp(if (tock) 0.035 else 0.05, 1e-4),
            pan = panOf(pan), send = 0.08f))
        noiseHit(t, FilterType.HIGHPASS, 4000.0, 0.5, 0.012, (if (tock) 0.05 else 0.09) * g, pan = pan)
        meter(t, Layer.TURN, if (tock) 0.6 else 1.0)
        if (!tock) engine.emit(UiEvent.Blink(t, beat * 0.5))
    }

    fun offset(t: Long, seconds: Double) = t + sec(seconds)

    companion object {
        /** 깜빡이 틱톡을 프로토타입보다 +10dB (사용자 요청: 음악 위로 확실히 들리도록) */
        const val TURN_BOOST = 3.16
    }
}
