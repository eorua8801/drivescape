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
    val kickPattern: Array<IntArray>
) {
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
                kickPattern = pattern(pat.getJSONObject("kick"))
            )
        }
    }
}
