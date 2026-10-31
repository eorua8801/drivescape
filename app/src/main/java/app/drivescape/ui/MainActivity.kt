package app.drivescape.ui

import android.Manifest
import android.app.Activity
import android.car.Car
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.Choreographer
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import app.drivescape.R
import app.drivescape.audio.Layer
import app.drivescape.audio.SAMPLE_RATE
import app.drivescape.audio.UiEvent
import app.drivescape.drive.DriveHub
import app.drivescape.drive.DriveState
import app.drivescape.drive.Gear
import app.drivescape.drive.Scenario
import app.drivescape.drive.SignalStatus
import app.drivescape.playback.DrivescapeService
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 메인 화면. 재생 조작은 MediaController로 서비스에 보낸다 (스티어링 휠 키와 같은 경로).
 * 주행 중(CarUxRestrictions)에는 재생·정지, 세트 넘기기만 남긴다.
 * 색은 values(낮) / values-night(밤) 팔레트를 따르므로 차량의 주야간 모드에 맞춰 바뀐다.
 */
class MainActivity : Activity() {
    private lateinit var browser: MediaBrowserCompat
    private var controller: MediaControllerCompat? = null
    private val player get() = DrivescapeService.player

    private lateinit var setName: TextView
    private lateinit var bpm: TextView
    private lateinit var chord: TextView
    private lateinit var mode: TextView
    private lateinit var beats: TextView
    private lateinit var caption: TextView
    private lateinit var scope: ScopeView
    private lateinit var meters: MeterView
    private lateinit var play: TextView
    private lateinit var scenarioBtn: TextView
    private lateinit var todAuto: TextView
    private lateinit var todDay: TextView
    private lateinit var todNight: TextView
    private lateinit var parkedPanel: View
    private lateinit var drivingNotice: View

    /** 차량 신호 카드 하나 */
    private class SignalCell(val root: View) {
        val label: TextView = root.findViewById(R.id.sig_label)
        val value: TextView = root.findViewById(R.id.sig_value)
        val tag: TextView = root.findViewById(R.id.sig_tag)
    }
    private val cells = LinkedHashMap<String, SignalCell>()

    private var chordName: String? = null
    private var beatOn = -1
    private var lampUntil = 0L
    private var arriveUntil = 0L

    private val cInk by lazy { getColor(R.color.ink) }
    private val cDim by lazy { getColor(R.color.ink_dim) }
    private val cOk by lazy { getColor(R.color.ok) }
    private val cRain by lazy { getColor(R.color.rain) }
    private val cBrake by lazy { getColor(R.color.brake) }
    private val cAmber by lazy { getColor(R.color.amber) }
    private val cAmberText by lazy { getColor(R.color.amber_text) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveHub.init(this)
        setContentView(R.layout.activity_main)

        setName = findViewById(R.id.set_name)
        bpm = findViewById(R.id.bpm)
        chord = findViewById(R.id.chord)
        mode = findViewById(R.id.mode)
        beats = findViewById(R.id.beats)
        caption = findViewById(R.id.caption)
        scope = findViewById<ScopeView>(R.id.scope).also { it.engine = player.engine }
        meters = findViewById(R.id.meters)
        play = findViewById(R.id.play)
        scenarioBtn = findViewById(R.id.scenario)
        todAuto = findViewById(R.id.tod_auto)
        todDay = findViewById(R.id.tod_day)
        todNight = findViewById(R.id.tod_night)
        parkedPanel = findViewById(R.id.parked_panel)
        drivingNotice = findViewById(R.id.driving_notice)

        val grid = findViewById<GridLayout>(R.id.signals)
        for ((key, label) in listOf("gear" to "기어", "speed" to "속도", "wiper" to "와이퍼",
                                    "turn" to "방향지시등", "brake" to "브레이크", "arrive" to "도착까지")) {
            val v = layoutInflater.inflate(R.layout.item_signal, grid, false)
            grid.addView(v)
            cells[key] = SignalCell(v).also { it.label.text = label }
        }

        play.setOnClickListener {
            val tc = controller?.transportControls ?: return@setOnClickListener
            if (player.isPlaying) tc.pause() else tc.play()
        }
        findViewById<View>(R.id.next).setOnClickListener { controller?.transportControls?.skipToNext() }
        scenarioBtn.setOnClickListener {
            if (DriveHub.scenario != null) {
                DriveHub.stopScenario()
            } else {
                DriveHub.startScenario()
                if (!player.isPlaying) controller?.transportControls?.play()
            }
        }
        todAuto.setOnClickListener { DriveHub.nightOverride = null }
        todDay.setOnClickListener { DriveHub.nightOverride = false }
        todNight.setOnClickListener { DriveHub.nightOverride = true }

        browser = MediaBrowserCompat(this, ComponentName(this, DrivescapeService::class.java), object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                controller = MediaControllerCompat(this@MainActivity, browser.sessionToken)
            }
        }, null)

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(Car.PERMISSION_SPEED, Car.PERMISSION_POWERTRAIN)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) DriveHub.onPermissionsChanged()
    }

    override fun onStart() {
        super.onStart()
        if (!browser.isConnected) browser.connect()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onStop() {
        Choreographer.getInstance().removeFrameCallback(frame)
        browser.disconnect()
        controller = null
        super.onStop()
    }

    // ---------------- 화면 갱신 (프로토타입 frame()) ----------------

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            render()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun render() {
        val running = player.isPlaying
        val st = DriveHub.current()
        val now = player.playheadFrame()

        player.drainEvents { e ->
            when (e) {
                is UiEvent.Meter -> meters.values[e.layer.ordinal] = max(meters.values[e.layer.ordinal], e.v)
                is UiEvent.Bar -> chordName = e.chord
                is UiEvent.Beat -> beatOn = e.n
                is UiEvent.Blink -> lampUntil = e.t + (e.durSec * SAMPLE_RATE).toLong()
                is UiEvent.Arrive -> arriveUntil = e.t + 2L * SAMPLE_RATE
            }
        }
        val base = FloatArray(Layer.entries.size)
        if (running) {
            base[Layer.PAD.ordinal] = 0.7f
            base[Layer.RAIN.ordinal] = floatArrayOf(0f, 0.4f, 0.7f)[st.wiper.coerceIn(0, 2)]
        }
        for (i in meters.values.indices) {
            var v = max(base[i], meters.values[i] * 0.9f)
            if (!running && v < 0.01f) v = 0f
            meters.values[i] = v
        }
        meters.invalidate()

        val set = DriveHub.currentSet
        setText(setName, set?.name ?: "")
        val bpmVal = set?.let { s ->
            if (st.gear == Gear.P) s.tempo.park else s.tempo.base + st.speed.toDouble().coerceIn(0.0, s.tempo.maxKmh) * s.tempo.perKmh
        } ?: 0.0
        setText(bpm, bpmVal.roundToInt().toString())
        val m = set?.let { if (st.night) it.night else it.day }
        setText(chord, if (running && chordName != null) chordName!! else m?.chords?.firstOrNull() ?: "")
        setText(mode, if (st.night) "밤 · 단조" else "낮 · 장조")
        setText(beats, (0 until 4).joinToString(" ") { if (running && it == beatOn) "●" else "○" })

        setText(play, getString(if (running) R.string.stop else R.string.play))
        scope.running = running
        scope.color = if (st.brake) cBrake else if (st.wiper > 0) cRain else cAmber
        scope.invalidate()

        val sc = DriveHub.scenario
        setText(caption, when {
            sc != null -> "시나리오 ${fmt(sc.elapsed)} / ${fmt(Scenario.LENGTH)} · ${sc.caption}"
            running -> levelNote(st)
            else -> "재생을 누르면 지금 주행에 맞춰 음악이 만들어집니다"
        })
        setText(scenarioBtn, getString(if (sc != null) R.string.scenario_stop else R.string.scenario_play))
        scenarioBtn.isSelected = sc != null
        val ov = DriveHub.nightOverride
        todAuto.isSelected = ov == null
        todDay.isSelected = ov == false
        todNight.isSelected = ov == true

        updateSignals(st, running && now < lampUntil, running && now < arriveUntil)

        val restricted = DriveHub.restricted
        parkedPanel.visibility = if (restricted) View.GONE else View.VISIBLE
        drivingNotice.visibility = if (restricted) View.VISIBLE else View.GONE
    }

    private fun updateSignals(st: DriveState, lampLit: Boolean, arriving: Boolean) {
        fun cell(key: String, value: String, status: SignalStatus?, active: Int? = null) {
            val c = cells.getValue(key)
            setText(c.value, value)
            c.value.setTextColor(active ?: cInk)
            val (tagText, tagColor) = when {
                status == null -> "내비" to cDim
                DriveHub.scenario != null -> "시나리오" to cRain
                status == SignalStatus.LIVE -> "차량 신호" to cOk
                status == SignalStatus.WAITING -> "대기" to cDim
                status == SignalStatus.UNSUPPORTED -> "미지원" to cDim
                else -> "권한 없음" to cDim
            }
            setText(c.tag, tagText)
            c.tag.setTextColor(tagColor)
        }
        cell("gear", if (st.gear == Gear.P) "P 주차" else "D 주행", DriveHub.gearStatus,
            if (st.gear == Gear.D) cAmberText else null)
        cell("speed", "${st.speed.roundToInt()} km/h", DriveHub.speedStatus)
        cell("wiper", arrayOf("끔", "저속", "고속")[st.wiper.coerceIn(0, 2)], DriveHub.wiperStatus,
            if (st.wiper > 0) cRain else null)
        cell("turn", when (st.turn) { -1 -> "◀ 왼쪽"; 1 -> "오른쪽 ▶"; else -> "끔" }, DriveHub.turnStatus,
            if (st.turn != 0 && lampLit) cOk else null)
        cell("brake", if (st.brake) "밟음" else "뗌", DriveHub.brakeStatus, if (st.brake) cBrake else null)
        val rem = st.remainingMin
        val remText = when {
            arriving || (rem != null && rem <= 0f) -> "도착"
            rem != null -> "%.1f분".format(rem)
            else -> "경로 없음"
        }
        cell("arrive", remText, if (DriveHub.scenario != null) SignalStatus.LIVE else null,
            if (rem != null && rem <= 5f) getColor(R.color.arp) else null)
    }

    private fun setText(v: TextView, s: String) { if (v.text.toString() != s) v.text = s }

    private fun levelNote(st: DriveState): String {
        if (st.gear == Gear.P) return "기어 P · 리듬 없이 패드와 차임만"
        val l = DriveHub.currentSet?.levels ?: return ""
        return "리듬 단계 · " + when {
            st.speed < l.slowBelow -> "정지 · 베이스만 길게"
            st.speed < l.cityBelow -> "저속 · 킥과 베이스 2분음표"
            st.speed < l.highwayBelow -> "시내 · 킥과 스네어, 하이햇 8분음표"
            else -> "고속 · 4박 킥, 하이햇 16분음표, 베이스 8분음표"
        }
    }

    private fun fmt(sec: Double): String {
        val s = max(0, sec.toInt())
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object { private const val REQ_PERMS = 1 }
}
