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
    val style: Style = Style(),
    /** 화면에 보이는 모습: 이름·커버·대표색 */
    val look: Look = Look.from(name)
) {
    /**
     * 세트의 얼굴. 색은 0xAARRGGBB.
     * [cover]는 앱에 그려 둔 커버 그림 이름(drawable `cover_<이름>`). 없으면 대표색으로 칠한 기본 커버를 쓴다.
     */
    class Look(
        val title: String,
        val sub: String,
        val cover: String = "",
        val chips: List<String> = emptyList(),
        val accent: Int = 0xFFF08A24.toInt(),
        val onAccent: Int = 0xFF1C1206.toInt(),
        val labelDay: Int = 0xFFA9530C.toInt(),
        val labelNight: Int = 0xFFF5A25A.toInt(),
        val tintDay: Int = 0xFFFFF1E2.toInt(),
        val tintNight: Int = 0xFF3A2614.toInt()
    ) {
        fun label(night: Boolean) = if (night) labelNight else labelDay
        fun tint(night: Boolean) = if (night) tintNight else tintDay

        companion object {
            /** "시티팝 · 밤의 고속도로" → 제목 "시티팝", 부제 "밤의 고속도로" */
            fun from(name: String): Look {
                val parts = name.split(" · ", limit = 2)
                return Look(parts[0], parts.getOrElse(1) { "" })
            }
        }
    }

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
        /**
         * epiano 컴핑 위치(16분음표). null이면 기본 컴핑(6번째 + 시내 이상에서 11번째).
         * 예: [2, 6, 10, 14] = 매 8분 뒷박에 짧게 끊어 치는 펑키한 컴핑
         */
        val compSteps: IntArray? = null,
        /** 컴핑 한 번의 길이(16분음표 단위)와 세기 */
        val compLen: Double = 1.5,
        val compVel: Double = 0.45,
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
        /** 아르페지오 한 음이 사라지는 시간(초). 짧으면 마림바, 길면 가야금처럼 울린다 */
        val arpDecay: Double = 0.35,
        /** 아르페지오 음을 이만큼(반음) 아래에서 밀어 올리며 시작 (0 = 없음). 가야금 줄을 뜯는 느낌 */
        val arpBend: Double = 0.0,
        /** 아르페지오가 나오기 시작하는 리듬 단계 (1 = 저속부터, 2 = 시내부터) */
        val arpFrom: Int = 2,
        /** 주행 중 2·4박에 높은 벨이 울릴 확률 (0 = 없음) */
        val sparkle: Double = 0.0,
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
            val name = o.getString("name")
            return SoundSet(
                id = o.getString("id"),
                name = name,
                subtitle = o.optString("subtitle"),
                day = mode(modes.getJSONObject("day")),
                night = mode(modes.getJSONObject("night")),
                tempo = Tempo(t.getDouble("park"), t.getDouble("base"), t.getDouble("perKmh"), t.getDouble("maxKmh")),
                levels = Levels(lv.getDouble("slowBelow"), lv.getDouble("cityBelow"), lv.getDouble("highwayBelow")),
                roots = Roots(r.getInt("pad"), r.getInt("bass"), r.getInt("arp"), r.getInt("drop")),
                bassPattern = pattern(pat.getJSONObject("bass")),
                kickPattern = pattern(pat.getJSONObject("kick")),
                style = o.optJSONObject("style")?.let(::style) ?: Style(),
                look = o.optJSONObject("look")?.let { look(it, name) } ?: Look.from(name)
            )
        }

        private fun look(j: JSONObject, name: String): Look {
            val d = Look.from(name)
            // android.graphics.Color은 단위 테스트(JVM)에서 쓸 수 없어서 직접 읽는다: "#RRGGBB" 또는 "#AARRGGBB"
            fun color(k: String, def: Int): Int {
                val s = j.optString(k).removePrefix("#")
                if (s.isEmpty()) return def
                val v = s.toLong(16)
                return (if (s.length <= 6) v or 0xFF000000 else v).toInt()
            }
            val chips = j.optJSONArray("chips")?.let { a -> List(a.length()) { a.getString(it) } } ?: d.chips
            return Look(
                title = j.optString("title", d.title), sub = j.optString("sub", d.sub),
                cover = j.optString("cover", d.cover), chips = chips,
                accent = color("accent", d.accent), onAccent = color("onAccent", d.onAccent),
                labelDay = color("labelDay", d.labelDay), labelNight = color("labelNight", d.labelNight),
                tintDay = color("tintDay", d.tintDay), tintNight = color("tintNight", d.tintNight)
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
                compSteps = j.optJSONArray("compSteps")?.let { a -> IntArray(a.length()) { a.getInt(it) } },
                compLen = num("compLen", d.compLen), compVel = num("compVel", d.compVel),
                swing = num("swing", d.swing),
                kickFreq = num("kickFreq", d.kickFreq), kickEnd = num("kickEnd", d.kickEnd),
                kickDecay = num("kickDecay", d.kickDecay), kickGain = num("kickGain", d.kickGain),
                snareFreq = num("snareFreq", d.snareFreq), snareGain = num("snareGain", d.snareGain),
                hatFreq = num("hatFreq", d.hatFreq), hatGain = num("hatGain", d.hatGain),
                arpWave = str("arpWave", d.arpWave), arpGain = num("arpGain", d.arpGain), arpCutoff = num("arpCutoff", d.arpCutoff),
                arpDecay = num("arpDecay", d.arpDecay), arpBend = num("arpBend", d.arpBend),
                arpFrom = if (j.has("arpFrom")) j.getInt("arpFrom") else d.arpFrom, sparkle = num("sparkle", d.sparkle),
                bassCutoff = num("bassCutoff", d.bassCutoff), bassGain = num("bassGain", d.bassGain),
                vinyl = num("vinyl", d.vinyl), toneCutoff = num("toneCutoff", d.toneCutoff), wobbleMs = num("wobbleMs", d.wobbleMs),
                outputGain = num("outputGain", d.outputGain)
            )
        }
    }
}
