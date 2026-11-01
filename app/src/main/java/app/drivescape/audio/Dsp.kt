package app.drivescape.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 웹 프로토타입(Web Audio)의 노드를 같은 수식으로 옮긴 DSP 부품들.
 * 기준 구현: Drivescape_프로토타입.html
 */

const val SAMPLE_RATE = 48_000
const val SR = SAMPLE_RATE.toDouble()

fun mtof(m: Double): Double = 440.0 * 2.0.pow((m - 69.0) / 12.0)

/** 단일 스레드(렌더 스레드) 전용 xorshift 난수. */
class Rng(seed: Int = 0x2545F491) {
    private var s = if (seed == 0) 1 else seed
    fun nextInt(): Int {
        var x = s
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        s = x
        return x
    }
    /** [0, 1) */
    fun next(): Double = (nextInt().toLong() and 0xFFFFFFFFL) / 4294967296.0
    /** [-1, 1) 화이트 노이즈 */
    fun noise(): Float = (next() * 2.0 - 1.0).toFloat()
}

object SineTable {
    private const val SIZE = 4096
    private val table = FloatArray(SIZE + 1) { sin(2.0 * PI * it / SIZE).toFloat() }
    /** phase: 0..1 */
    fun at(phase: Double): Float {
        val p = phase * SIZE
        val i = p.toInt()
        val f = (p - i).toFloat()
        val a = table[i and (SIZE - 1)]
        val b = table[(i and (SIZE - 1)) + 1]
        return a + (b - a) * f
    }
}

enum class Wave { SINE, TRIANGLE, SAW, SQUARE }

private fun polyBlep(t: Double, dt: Double): Double = when {
    t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
    t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
    else -> 0.0
}

/** 대역 제한(PolyBLEP) 오실레이터. Web Audio 오실레이터처럼 위상 0에서 시작. */
class Osc(val wave: Wave, val baseFreq: Double) {
    private var phase = 0.0
    fun next(freqMul: Double): Float {
        val dt = min(0.5, baseFreq * freqMul / SR)
        val t = phase
        val v = when (wave) {
            Wave.SINE -> SineTable.at(t).toDouble()
            Wave.TRIANGLE -> if (t < 0.25) 4 * t else if (t < 0.75) 2 - 4 * t else 4 * t - 4
            Wave.SAW -> (2 * t - 1) - polyBlep(t, dt)
            Wave.SQUARE -> {
                var s = if (t < 0.5) 1.0 else -1.0
                s += polyBlep(t, dt)
                s -= polyBlep((t + 0.5) % 1.0, dt)
                s
            }
        }
        phase += dt
        if (phase >= 1.0) phase -= 1.0
        return v.toFloat()
    }
}

enum class FilterType { LOWPASS, HIGHPASS, BANDPASS }

/**
 * Web Audio BiquadFilterNode와 같은 계수식.
 * lowpass/highpass의 Q는 dB 단위, bandpass의 Q는 선형 값 (Web Audio 명세와 동일).
 */
class Biquad(private val type: FilterType, freq: Double, private val q: Double) {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    init { setFreq(freq) }

    fun setFreq(freqHz: Double) {
        val f = min(max(freqHz, 10.0), SR * 0.49)
        val w0 = 2 * PI * f / SR
        val cw = cos(w0); val sw = sin(w0)
        val alpha = when (type) {
            FilterType.BANDPASS -> sw / (2 * max(q, 1e-4))
            else -> sw / (2 * 10.0.pow(q / 20.0))
        }
        val a0 = 1 + alpha
        val nb0: Double; val nb1: Double; val nb2: Double
        when (type) {
            FilterType.LOWPASS -> { nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2 }
            FilterType.HIGHPASS -> { nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2 }
            FilterType.BANDPASS -> { nb0 = alpha; nb1 = 0.0; nb2 = -alpha }
        }
        b0 = (nb0 / a0).toFloat(); b1 = (nb1 / a0).toFloat(); b2 = (nb2 / a0).toFloat()
        a1 = (-2 * cw / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = if (abs(y) < 1e-25f) 0f else y
        return y1
    }
}

/**
 * AudioParam 자동화(setValueAtTime / linearRamp / exponentialRamp / setTargetAtTime)를
 * 샘플 단위로 따라가는 값. 시간은 보이스 시작 기준 초.
 * 이벤트는 시간 순서대로 추가해야 한다.
 */
class Auto(private var v: Double) {
    private companion object { const val SET = 0; const val LIN = 1; const val EXP = 2; const val TARGET = 3 }
    private var types = IntArray(6); private var times = LongArray(6)
    private var vals = DoubleArray(6); private var taus = DoubleArray(6)
    private var count = 0; private var idx = 0; private var n = 0L
    private var mode = SET; private var k = 0.0; private var tgt = 0.0

    fun set(t: Double, value: Double) = add(SET, t, value, 0.0)
    fun lin(t: Double, value: Double) = add(LIN, t, value, 0.0)
    fun exp(t: Double, value: Double) = add(EXP, t, value, 0.0)
    fun target(t: Double, value: Double, tau: Double) = add(TARGET, t, value, tau)

    private fun add(type: Int, t: Double, value: Double, tau: Double): Auto {
        if (count == types.size) {
            types = types.copyOf(count * 2); times = times.copyOf(count * 2)
            vals = vals.copyOf(count * 2); taus = taus.copyOf(count * 2)
        }
        types[count] = type; times[count] = kotlin.math.ceil(t * SR).toLong()
        vals[count] = value; taus[count] = tau
        count++
        return this
    }

    fun next(): Double {
        while (idx < count && times[idx] <= n) {
            when (types[idx]) {
                SET, LIN, EXP -> { v = vals[idx]; mode = SET }
                TARGET -> { mode = TARGET; tgt = vals[idx]; k = 1 - exp(-1.0 / (max(taus[idx], 1e-5) * SR)) }
            }
            idx++
            if (idx < count && (types[idx] == LIN || types[idx] == EXP)) {
                val len = (times[idx] - times[idx - 1]).coerceAtLeast(1)
                if (types[idx] == LIN) { mode = LIN; k = (vals[idx] - v) / len }
                else { mode = EXP; k = (vals[idx] / v).pow(1.0 / len) }
            }
        }
        val out = v
        when (mode) {
            LIN -> v += k
            EXP -> v *= k
            TARGET -> v += (tgt - v) * k
        }
        n++
        return out
    }
}

/** setTargetAtTime으로 움직이는 연속 파라미터 (마스터 체인용). */
class Smoothed(var value: Double) {
    private var target = value
    private var k = 1.0
    fun setTarget(t: Double, tau: Double) { target = t; k = 1 - exp(-1.0 / (max(tau, 1e-5) * SR)) }
    fun jump(t: Double) { value = t; target = t }
    fun next(): Double { value += (target - value) * k; return value }
}

/** 선형 램프 (마스터 페이드 인·아웃). */
class Ramp(var value: Double) {
    private var step = 0.0; private var remain = 0L; private var target = value
    fun rampTo(t: Double, sec: Double) {
        target = t; remain = max(1L, (sec * SR).toLong()); step = (t - value) / remain
    }
    fun next(): Double {
        if (remain > 0) { value += step; remain--; if (remain == 0L) value = target }
        return value
    }
    val isIdle get() = remain == 0L
}

/** Paul Kellet 핑크 노이즈 (프로토타입 makePink와 같은 계수). */
class PinkNoise(private val rng: Rng) {
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var b3 = 0.0
    private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
    fun next(): Float {
        val w = rng.noise().toDouble()
        b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.969 * b2 + w * 0.153852
        b3 = 0.8665 * b3 + w * 0.3104856; b4 = 0.55 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.016898
        val d = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11
        b6 = w * 0.115926
        return d.toFloat()
    }
}

/** 분수 지연(선형 보간) 스테레오 딜레이 라인. */
class DelayLine(maxSec: Double) {
    private val size = (maxSec * SR).toInt() + 4
    private val l = FloatArray(size); private val r = FloatArray(size)
    private var w = 0
    var outL = 0f; private set
    var outR = 0f; private set

    fun read(delaySec: Double) {
        val d = min(max(delaySec * SR, 1.0), (size - 3).toDouble())
        var p = w - d
        if (p < 0) p += size
        // p가 0보다 아주 조금 작으면(-1e-13 등) size를 더한 값이 반올림으로 size와 같아질 수 있다 → 배열 밖
        if (p >= size) p -= size
        val i = p.toInt(); val f = (p - i).toFloat()
        val j = if (i + 1 >= size) 0 else i + 1
        outL = l[i] + (l[j] - l[i]) * f
        outR = r[i] + (r[j] - r[i]) * f
    }

    fun write(inL: Float, inR: Float) {
        l[w] = inL; r[w] = inR
        w++; if (w >= size) w = 0
    }
}

/**
 * 컨볼루션 리버브(2.8초 노이즈 IR) 대신 쓰는 알고리즘 리버브(Freeverb 구조).
 * 차량용 CPU에서 13만 탭 컨볼루션은 부담이 커서 바꿨다.
 * 잔향 길이와 전체 에너지는 원본 IR(Web Audio normalize 적용 후)에 맞춰 보정한다.
 */
class Reverb(t60: Double = 2.6, private val damp: Float = 0.12f) {
    private class Comb(size: Int, val fb: Float) {
        private val buf = FloatArray(size); private var i = 0; private var store = 0f
        fun process(x: Float, damp: Float): Float {
            val y = buf[i]
            store = y * (1 - damp) + store * damp
            buf[i] = x + store * fb
            i++; if (i >= buf.size) i = 0
            return y
        }
    }
    private class AllPass(size: Int) {
        private val buf = FloatArray(size); private var i = 0
        fun process(x: Float): Float {
            val b = buf[i]
            buf[i] = x + b * 0.5f
            i++; if (i >= buf.size) i = 0
            return b - x
        }
    }

    private val combL: Array<Comb>; private val combR: Array<Comb>
    private val apL: Array<AllPass>; private val apR: Array<AllPass>
    private var gain = 1f
    var outL = 0f; private set
    var outR = 0f; private set

    init {
        val s = SR / 44100.0
        val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val aps = intArrayOf(556, 441, 341, 225)
        fun fb(len: Int) = 10.0.pow(-3.0 * len / (t60 * SR)).toFloat()
        combL = Array(8) { val n = (combs[it] * s).toInt(); Comb(n, fb(n)) }
        combR = Array(8) { val n = ((combs[it] + 23) * s).toInt(); Comb(n, fb(n)) }
        apL = Array(4) { AllPass((aps[it] * s).toInt()) }
        apR = Array(4) { AllPass(((aps[it] + 23) * s).toInt()) }
    }

    fun process(inL: Float, inR: Float) {
        val x = (inL + inR) * 0.5f
        var l = 0f; var r = 0f
        for (c in combL) l += c.process(x, damp)
        for (c in combR) r += c.process(x, damp)
        for (a in apL) l = a.process(l)
        for (a in apR) r = a.process(r)
        outL = l * gain; outR = r * gain
    }

    companion object {
        /**
         * Web Audio ConvolverNode(normalize=true)는 IR을 RMS 기준으로 정규화하고 -58dB 보정을 한다.
         * 프로토타입 IR(2.8초) 기준으로 채널당 임펄스 응답 에너지 ≈ 0.18.
         */
        private const val TARGET_ENERGY = 0.18

        fun calibrated(): Reverb {
            val probe = Reverb()
            var e = 0.0
            val n = (3.5 * SR).toInt()
            for (i in 0 until n) {
                val x = if (i == 0) 1f else 0f
                probe.process(x, x)
                e += probe.outL.toDouble() * probe.outL
            }
            return Reverb().also { it.gain = sqrt(TARGET_ENERGY / max(e, 1e-12)).toFloat() }
        }
    }
}

/**
 * DynamicsCompressorNode 근사 (threshold -16dB, knee 12dB, ratio 3.5, attack 8ms, release 250ms).
 * Chromium처럼 0dBFS 기준 자동 메이크업 게인(감쇠량의 0.6제곱)과 6ms 룩어헤드를 적용한다.
 */
class Compressor(
    private val threshold: Double = -16.0,
    private val knee: Double = 12.0,
    private val ratio: Double = 3.5,
    attack: Double = 0.008,
    release: Double = 0.25,
    lookahead: Double = 0.006
) {
    private val laSize = (lookahead * SR).toInt().coerceAtLeast(1)
    private val laL = FloatArray(laSize); private val laR = FloatArray(laSize)
    private var laIdx = 0
    private val aCoef = exp(-1.0 / (attack * SR))
    private val rCoef = exp(-1.0 / (release * SR))
    private var gr = 0.0
    private val makeupDb = -curve(0.0) * 0.6

    private fun curve(x: Double): Double {
        val kneeEnd = threshold + knee
        return when {
            x <= threshold -> 0.0
            x < kneeEnd -> { val d = x - threshold; (1.0 / ratio - 1.0) * d * d / (2 * knee) }
            else -> {
                val atKnee = (1.0 / ratio - 1.0) * knee / 2
                atKnee + (x - kneeEnd) * (1.0 / ratio - 1.0)
            }
        }
    }

    var outL = 0f; private set
    var outR = 0f; private set

    fun process(l: Float, r: Float) {
        val peak = max(abs(l), abs(r)).toDouble()
        val xDb = if (peak < 1e-6) -120.0 else 20 * log10(peak)
        val target = curve(xDb)
        val c = if (target < gr) aCoef else rCoef
        gr = target + (gr - target) * c
        val g = 10.0.pow((gr + makeupDb) / 20.0).toFloat()
        // 검출은 지금 샘플로, 게인은 6ms 늦은 소리에 적용 → 타격음 앞부분이 튀지 않는다
        val dl = laL[laIdx]; val dr = laR[laIdx]
        laL[laIdx] = l; laR[laIdx] = r
        laIdx++; if (laIdx >= laSize) laIdx = 0
        outL = dl * g; outR = dr * g
    }
}

/** Web Audio StereoPannerNode(모노 입력)의 equal-power 게인. */
fun panGains(pan: Double, out: FloatArray) {
    val x = (min(1.0, max(-1.0, pan)) + 1) / 2
    out[0] = cos(x * PI / 2).toFloat()
    out[1] = sin(x * PI / 2).toFloat()
}
