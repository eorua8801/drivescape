package app.drivescape.audio

import app.drivescape.drive.Scenario
import app.drivescape.music.SoundSet
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 86초 데모 시나리오를 오프라인으로 렌더링해 WAV로 저장하고,
 * 클리핑·NaN·처리 속도를 검사한다. 결과: app/build/drivescape_scenario.wav
 */
class OfflineRenderTest {
    private val noteStarts = ArrayList<Pair<Long, Layer>>()

    @Test
    fun renderScenario() {
        val set = SoundSet.parse(File("src/main/assets/sets/01_ambient_pop.json").readText())
        var audioSec = 0.0
        val scenario = Scenario(night = false) { audioSec }
        val engine = SynthEngine { scenario.state() }
        engine.soundSet = set
        engine.start()

        val total = (Scenario.LENGTH * SAMPLE_RATE).toInt()
        val pcm = ShortArray(total * 2)
        val block = 256
        val buf = FloatArray(block * 2)
        var peak = 0f
        var bad = 0
        var clipped = 0
        val segRms = DoubleArray(Scenario.LENGTH.toInt() + 1)
        val t0 = System.nanoTime()
        var done = 0
        while (done < total) {
            val n = minOf(block, total - done)
            engine.render(buf, n)
            for (i in 0 until n * 2) {
                val v = buf[i]
                if (v.isNaN() || v.isInfinite()) bad++
                peak = max(peak, abs(v))
                if (abs(v) >= 0.999f) clipped++
                pcm[done * 2 + i] = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                segRms[(done + i / 2) / SAMPLE_RATE] += (v * v).toDouble()
            }
            done += n
            audioSec = done.toDouble() / SAMPLE_RATE
            while (true) {
                val e = engine.events.poll() ?: break
                if (e is UiEvent.Meter) noteStarts += e.t to e.layer
            }
        }
        val elapsed = (System.nanoTime() - t0) / 1e9

        writeWav(File("build/drivescape_scenario.wav"), pcm)
        reportClicks(pcm)

        println("렌더링 %.1f초 분량 → %.2f초 소요 (실시간의 %.1f배 빠름)".format(Scenario.LENGTH, elapsed, Scenario.LENGTH / elapsed))
        println("peak = %.3f (%.1f dBFS), clipped = %d, NaN/Inf = %d".format(peak, 20 * log10(peak.toDouble()), clipped, bad))
        val line = segRms.indices.joinToString(" ") { s ->
            val rms = sqrt(segRms[s] / (SAMPLE_RATE * 2))
            "%d:%.0f".format(s, if (rms > 0) 20 * log10(rms) else -99.0)
        }
        println("초별 RMS(dBFS): $line")

        assertTrue("NaN/Inf 샘플", bad == 0)
        assertTrue("소리가 너무 작음", peak > 0.05f)
    }

    /**
     * 클릭(파형이 갑자기 꺾이는 지점) 검사: 2차 차분이 주변 10ms 평균보다 12배 이상 튀는 샘플을 센다.
     * 하이햇·빗소리 같은 노이즈는 주변도 같이 크므로 걸리지 않고, 고립된 불연속만 걸린다.
     */
    private fun reportClicks(pcm: ShortArray) {
        val n = pcm.size / 2
        val d = FloatArray(n)
        for (i in 2 until n) {
            val x0 = pcm[i * 2] / 32768f; val x1 = pcm[(i - 1) * 2] / 32768f; val x2 = pcm[(i - 2) * 2] / 32768f
            d[i] = abs(x0 - 2 * x1 + x2)
        }
        val win = 480
        var sum = 0.0
        val hits = ArrayList<Double>()
        for (i in 0 until n) {
            sum += d[i]
            if (i >= win) sum -= d[i - win]
            if (i >= win) {
                val mean = sum / win
                if (d[i] > 0.02f && d[i] > 12 * mean) {
                    val t = i.toDouble() / SAMPLE_RATE
                    if (hits.isEmpty() || t - hits.last() > 0.05) hits += t
                }
            }
        }
        println("클릭 의심 지점 ${hits.size}개: " + hits.take(30).joinToString(" ") { "%.2f".format(it) })
        // 각 클릭 직전 0.5초 안에 시작한 음이 무엇인지 집계 (가장 가까운 것)
        val blame = HashMap<String, Int>()
        for (t in hits) {
            val s = (t * SAMPLE_RATE).toLong()
            val near = noteStarts.filter { it.first in (s - SAMPLE_RATE / 2)..s }.maxByOrNull { it.first }
            val key = near?.let { "${it.second} +%.0fms".format((s - it.first) * 1000.0 / SAMPLE_RATE) } ?: "없음"
            blame[key] = (blame[key] ?: 0) + 1
        }
        println("클릭 직전 시작한 음: " + blame.entries.sortedByDescending { it.value }.take(12).joinToString(", ") { "${it.key}×${it.value}" })
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        f.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(f).buffered()).use { o ->
            fun i32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
            fun i16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
            val dataBytes = pcm.size * 2
            o.writeBytes("RIFF"); i32(36 + dataBytes); o.writeBytes("WAVE")
            o.writeBytes("fmt "); i32(16); i16(1); i16(2); i32(SAMPLE_RATE); i32(SAMPLE_RATE * 4); i16(4); i16(16)
            o.writeBytes("data"); i32(dataBytes)
            for (s in pcm) i16(s.toInt())
        }
    }
}
