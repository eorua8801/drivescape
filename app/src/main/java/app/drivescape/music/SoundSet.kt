package app.drivescape.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 사운드 세트 = 음악 규칙 데이터. assets/sets 폴더의 JSON 파일에서 읽는다.
 * 엔진(음색·믹스)은 코드, 화성·템포·패턴은 데이터로 분리한다.
 */
class SoundSet(
    val id: String,
    val name: String,
    val subtitle: String,
    val day: Mode,
    val night: Mode,
    val tempo: Tempo,
    val levels: Levels,
    val roots: Roots,
    /** 리듬 단계(0~3)별 베이스가 울리는 16분음표 위치 */
    val bassPattern: Array<IntArray>,
    /** 리듬 단계(1~3)별 킥 위치. 인덱스 0은 쓰지 않는다 */
    val kickPattern: Array<IntArray>,
    /** 음색·질감. 생략한 값은 기본 세트(웹 프로토타입)와 같은 소리 */
    val style: Style = Style()
) {
    /**
     * 세트별 음색 설정. 기본값 = 웹 프로토타입 수치.
     * 차량 신호에 대한 반응(빗소리·와이퍼·깜빡이·브레이크)은 모든 세트에 공통이다.
     */
    class Style(
        /** "saw" = 신스 패드(마디 전체), "epiano" = 일렉트릭 피아노 코드 컴핑 */
        val padStyle: String = "saw",
        /** 코드 구성음(스케일 도수 오프셋). [0,2,4,6] = 7화음, [0,2,4,6,8] = 9화음 */
        val padVoicing: IntArray = intArrayOf(0, 2, 4, 6),
        val padGain: Double = 1.0,
        /** epiano 세트에서 밑에 깔 어두운 신스 패드의 크기 (0 = 없음). 마디 사이가 비지 않게 한다 */
        val bed: Double = 0.0,
        /** 홀수 16분음표를 16분음표 길이의 몇 배만큼 늦출지 (0 = 스윙 없음, 0.33 ≈ 셋잇단 느낌) */
        val swing: Double = 0.0,
        val kickFreq: Double = 150.0,
        val kickEnd: Double = 46.0,
        val kickDecay: Double = 0.42,
        val kickGain: Double = 1.0,
        val snareFreq: Double = 1900.0,
        val snareGain: Double = 1.0,
        val hatFreq: Double = 7600.0,
        val hatGain: Double = 1.0,
        /** "square" | "sine" | "triangle" */
        val arpWave: String = "square",
        val arpGain: Double = 1.0,
        val arpCutoff: Double = 3800.0,
        val bassCutoff: Double = 700.0,
        val bassGain: Double = 1.0,
        /** LP 레코드 잡음(0~1) */
        val vinyl: Double = 0.0,
        /** 전체 음색의 고역 한계(Hz). 브레이크 로우패스와 같은 필터를 쓴다 */
        val toneCutoff: Double = 18000.0,
        /** 테이프 흔들림 깊이(ms) */
        val wobbleMs: Double = 0.0,
        /** 컴프레서 앞 출력 크기. 세트 사이 체감 음량을 맞춘다 */
        val outputGain: Double = 1.0
    )

    class Mode(val scale: IntArray, val progression: IntArray, val chords: Array<String>)
    class Tempo(val park: Double, val base: Double, val perKmh: Double, val maxKmh: Double)
    class Levels(val slowBelow: Double, val cityBelow: Double, val highwayBelow: Double)
    class Roots(val pad: Int, val bass: Int, val arp: Int, val drop: Int)

    companion object {
        fun loadAll(context: Context): List<SoundSet> {
            val dir = "sets"
            val files = context.assets.list(dir)?.filter { it.endsWith(".json") }?.sorted() ?: emptyList()
            return files.map { f ->
                context.assets.open("$dir/$f").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
            }
        }

        fun parse(text: String): SoundSet {
            val o = JSONObject(text)
            fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
            fun mode(m: JSONObject) = Mode(
                ints(m.getJSONArray("scale")),
                ints(m.getJSONArray("progression")),
                Array(m.getJSONArray("chords").length()) { m.getJSONArray("chords").getString(it) }
            )
            fun pattern(p: JSONObject) = Array(4) { lv -> p.optJSONArray(lv.toString())?.let(::ints) ?: IntArray(0) }
            val t = o.getJSONObject("tempo")
            val lv = o.getJSONObject("levels")
            val r = o.getJSONObject("roots")
            val modes = o.getJSONObject("modes")
            val pat = o.getJSONObject("patterns")
            return SoundSet(
                id = o.getString("id"),
                name = o.getString("name"),
                subtitle = o.optString("subtitle"),
                day = mode(modes.getJSONObject("day")),
                night = mode(modes.getJSONObject("night")),
                tempo = Tempo(t.getDouble("park"), t.getDouble("base"), t.getDouble("perKmh"), t.getDouble("maxKmh")),
                levels = Levels(lv.getDouble("slowBelow"), lv.getDouble("cityBelow"), lv.getDouble("highwayBelow")),
                roots = Roots(r.getInt("pad"), r.getInt("bass"), r.getInt("arp"), r.getInt("drop")),
                bassPattern = pattern(pat.getJSONObject("bass")),
                kickPattern = pattern(pat.getJSONObject("kick")),
                style = o.optJSONObject("style")?.let(::style) ?: Style()
            )
        }

        private fun style(j: JSONObject): Style {
            val d = Style()
            fun num(k: String, def: Double) = if (j.has(k)) j.getDouble(k) else def
            fun str(k: String, def: String) = if (j.has(k)) j.getString(k) else def
            val voicing = j.optJSONArray("padVoicing")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: d.padVoicing
            return Style(
                padStyle = str("padStyle", d.padStyle), padVoicing = voicing, padGain = num("padGain", d.padGain),
                bed = num("bed", d.bed),
                swing = num("swing", d.swing),
                kickFreq = num("kickFreq", d.kickFreq), kickEnd = num("kickEnd", d.kickEnd),
                kickDecay = num("kickDecay", d.kickDecay), kickGain = num("kickGain", d.kickGain),
                snareFreq = num("snareFreq", d.snareFreq), snareGain = num("snareGain", d.snareGain),
                hatFreq = num("hatFreq", d.hatFreq), hatGain = num("hatGain", d.hatGain),
                arpWave = str("arpWave", d.arpWave), arpGain = num("arpGain", d.arpGain), arpCutoff = num("arpCutoff", d.arpCutoff),
                bassCutoff = num("bassCutoff", d.bassCutoff), bassGain = num("bassGain", d.bassGain),
                vinyl = num("vinyl", d.vinyl), toneCutoff = num("toneCutoff", d.toneCutoff), wobbleMs = num("wobbleMs", d.wobbleMs),
                outputGain = num("outputGain", d.outputGain)
            )
        }
    }
}
