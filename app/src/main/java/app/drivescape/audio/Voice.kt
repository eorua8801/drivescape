package app.drivescape.audio

/** 한 블록 동안 보이스들이 더해 넣는 버스들. 프로토타입의 N.dry / N.revIn / N.arpBus. */
class Mix(val size: Int) {
    val dryL = FloatArray(size); val dryR = FloatArray(size)
    val revL = FloatArray(size); val revR = FloatArray(size)
    val arpL = FloatArray(size); val arpR = FloatArray(size)
    fun clear() {
        dryL.fill(0f); dryR.fill(0f); revL.fill(0f); revR.fill(0f); arpL.fill(0f); arpR.fill(0f)
    }
}

/**
 * 하나의 음(노트). 오실레이터 묶음 또는 노이즈 → (필터) → 게인 → (패너) → 버스 + 리버브 센드.
 * 프로토타입의 out(node, {pan, send, bus}) 연결 규칙을 그대로 따른다:
 * pan이 null(=0)이면 패너 없이 모노가 양쪽 채널에 그대로, 있으면 equal-power 패닝.
 */
class Voice(
    /** 엔진 절대 샘플 기준 시작 위치 */
    val start: Long,
    lengthSec: Double,
    private val oscs: Array<Osc>? = null,
    private val freq: Auto? = null,
    private val noise: Rng? = null,
    private val filter: Biquad? = null,
    private val filterFreq: Auto? = null,
    private val gain: Auto,
    private val pan: Double? = null,
    private val panAuto: Auto? = null,
    private val send: Float = 0f,
    private val toArpBus: Boolean = false
) {
    private val length = (lengthSec * SR).toLong()
    private var pos = 0L
    private val g = FloatArray(2)

    init {
        if (pan != null) panGains(pan, g) else { g[0] = 1f; g[1] = 1f }
    }

    /** @return 아직 소리가 남아 있으면 true */
    fun render(mix: Mix, blockStart: Long, n: Int): Boolean {
        var k = (start - blockStart).toInt()
        if (k >= n) return true
        if (k < 0) k = 0
        val outL = if (toArpBus) mix.arpL else mix.dryL
        val outR = if (toArpBus) mix.arpR else mix.dryR
        while (k < n) {
            if (pos >= length) return false
            var s = 0f
            if (oscs != null) {
                val fm = freq?.next() ?: 1.0
                for (o in oscs) s += o.next(fm)
            }
            if (noise != null) s += noise.noise()
            if (filterFreq != null) {
                val f = filterFreq.next()
                if ((pos and 15L) == 0L) filter!!.setFreq(f)
            }
            if (filter != null) s = filter.process(s)
            s *= gain.next().toFloat()
            if (panAuto != null) {
                val p = panAuto.next()
                if ((pos and 15L) == 0L) panGains(p, g)
            }
            val l = s * g[0]; val r = s * g[1]
            outL[k] += l; outR[k] += r
            if (send > 0f) { mix.revL[k] += l * send; mix.revR[k] += r * send }
            pos++; k++
        }
        return pos < length
    }
}
