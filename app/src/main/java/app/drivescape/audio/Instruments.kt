package app.drivescape.audio

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
 * 수치는 기준 구현과 동일하다. t = 시작 샘플(절대 위치), 길이·시간 = 초.
 */
class Instruments(private val engine: SynthEngine) {
    private val rng get() = engine.rng
    private fun panOf(p: Double): Double? = if (p == 0.0) null else p
    private fun env() = Auto(1e-4).set(0.0, 1e-4)
    private fun sec(seconds: Double) = (seconds * SR).toLong()

    private fun meter(t: Long, layer: Layer, v: Double) = engine.emit(UiEvent.Meter(t, layer, v.toFloat()))

    fun kick(t: Long, vel: Double) {
        engine.add(Voice(t, 0.45,
            oscs = arrayOf(Osc(Wave.SINE, 1.0)),
            freq = Auto(150.0).set(0.0, 150.0).exp(0.13, 46.0),
            gain = env().exp(0.004, 0.85 * vel).exp(0.42, 1e-4),
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
        noiseHit(t, FilterType.BANDPASS, 1900.0, 0.9, 0.22, 0.3 * vel, send = 0.22)
        engine.add(Voice(t, 0.15,
            oscs = arrayOf(Osc(Wave.TRIANGLE, 1.0)),
            freq = Auto(220.0).set(0.0, 220.0).exp(0.08, 160.0),
            gain = env().exp(0.003, 0.22 * vel).exp(0.12, 1e-4),
            send = 0.12f))
        meter(t, Layer.DRUMS, 0.8 * vel)
    }

    fun hat(t: Long, vel: Double, open: Boolean) {
        noiseHit(t, FilterType.HIGHPASS, 7600.0, 0.6, if (open) 0.22 else 0.045, 0.1 * vel, pan = 0.18, send = 0.05)
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
        engine.add(Voice(t, dur + rel + 0.05,
            oscs = oscs.toTypedArray(),
            filter = Biquad(FilterType.LOWPASS, cutoff, 0.6),
            gain = env().lin(att, gain).set(dur, gain).lin(dur + rel, 1e-4),
            send = send.toFloat()))
        meter(t, Layer.PAD, 1.0)
    }

    fun bass(t: Long, midi: Int, dur: Double, vel: Double) {
        val f = mtof(midi.toDouble())
        engine.add(Voice(t, dur + 0.6,
            oscs = arrayOf(Osc(Wave.TRIANGLE, f), Osc(Wave.SINE, f)),
            filter = Biquad(FilterType.LOWPASS, 700.0, 1.0),
            gain = env().exp(0.01, 0.4 * vel).target(dur * 0.6, 1e-4, dur * 0.25),
            send = 0.04f))
        meter(t, Layer.BASS, vel)
    }

    fun pluck(t: Long, midi: Int, vel: Double, pan: Double) {
        engine.add(Voice(t, 0.4,
            oscs = arrayOf(Osc(Wave.SQUARE, mtof(midi.toDouble()))),
            filter = Biquad(FilterType.LOWPASS, 3800.0, 2.0),
            filterFreq = Auto(3800.0).set(0.0, 3800.0).exp(0.25, 700.0),
            gain = env().exp(0.004, 0.06 * vel).exp(0.35, 1e-4),
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
        engine.add(Voice(t, 0.07,
            oscs = arrayOf(Osc(Wave.TRIANGLE, if (tock) 1350.0 else 2100.0)),
            filter = Biquad(FilterType.HIGHPASS, 900.0, 1.0),
            gain = env().exp(0.002, if (tock) 0.1 else 0.17).exp(if (tock) 0.035 else 0.05, 1e-4),
            pan = panOf(pan), send = 0.08f))
        noiseHit(t, FilterType.HIGHPASS, 4000.0, 0.5, 0.012, if (tock) 0.05 else 0.09, pan = pan)
        meter(t, Layer.TURN, if (tock) 0.6 else 1.0)
        if (!tock) engine.emit(UiEvent.Blink(t, beat * 0.5))
    }

    fun offset(t: Long, seconds: Double) = t + sec(seconds)
}
