package app.drivescape.ui

import android.Manifest
import android.app.Activity
import android.car.Car
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import app.drivescape.music.SoundSet
import app.drivescape.playback.DrivescapeService
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 메인 화면. 재생 조작은 MediaController로 서비스에 보낸다 (스티어링 휠 키와 같은 경로).
 * 세 가지 보기 중 하나만 보인다: 재생 전 첫 화면 / 재생 중 / 세트 고르기(정차 중).
 * 주행 중(CarUxRestrictions)에는 재생·정지, 세트 넘기기와 큰 반응 자막만 남긴다.
 * 바탕색은 values(낮) / values-night(밤) 팔레트, 강조색과 커버는 지금 세트의 look을 따른다.
 */
class MainActivity : Activity() {
    private lateinit var browser: MediaBrowserCompat
    private var controller: MediaControllerCompat? = null
    private val player get() = DrivescapeService.player

    // 머리줄
    private lateinit var setChip: View
    private lateinit var setThumb: ImageView
    private lateinit var setName: TextView
    private lateinit var statusPill: TextView
    private lateinit var mode: TextView

    // 재생 전 첫 화면
    private lateinit var idleView: View
    private lateinit var heroCard: View
    private lateinit var heroCover: ImageView
    private lateinit var heroLabel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroChips: ViewGroup
    private lateinit var idlePlay: TextView
    private lateinit var idlePicker: View
    private lateinit var idleMenu: View
    private lateinit var connDot: View
    private lateinit var connText: TextView
    private val reactIcons = ArrayList<ImageView>()

    // 재생 중
    private lateinit var liveView: View
    private lateinit var liveRight: View
    private lateinit var bpm: TextView
    private lateinit var chord: TextView
    private lateinit var beats: BeatView
    private lateinit var caption: TextView
    private lateinit var driveCaption: View
    private lateinit var driveCaptionLabel: TextView
    private lateinit var driveCaptionText: TextView
    private lateinit var driveStrip: View
    private lateinit var scope: ScopeView
    private lateinit var meters: MeterView
    private lateinit var play: TextView
    private lateinit var demoOverlay: View
    private lateinit var demoTitle: TextView
    private lateinit var demoCaption: TextView

    // 세트 고르기
    private lateinit var pickerView: View
    private lateinit var pickerThumb: ImageView
    private lateinit var pickerName: TextView
    private lateinit var pickerGo: TextView
    private var pickerOpen = false
    private var pickedId: String? = null
    private val pickerCards = ArrayList<PickerCard>()

    /** 정차 중 메뉴는 재생 전·재생 중 화면에 하나씩 있다 */
    private class ParkedMenu(root: View) {
        val scenario: TextView = root.findViewById(R.id.scenario)
        val auto: TextView = root.findViewById(R.id.tod_auto)
        val day: TextView = root.findViewById(R.id.tod_day)
        val night: TextView = root.findViewById(R.id.tod_night)
    }
    private val menus = ArrayList<ParkedMenu>()

    private var lastSetId: String? = null
    private var setNoticeUntil = 0L
    private var styledFor: String? = null
    private var scopeAccent = 0

    /** 차량 신호 칸 하나 (값 + 출처 꼬리표) */
    private class SignalCell(val root: View) {
        val label: TextView = root.findViewById(R.id.sig_label)
        val value: TextView = root.findViewById(R.id.sig_value)
        val tag: TextView? = root.findViewById(R.id.sig_tag)
    }
    /** 재생 중(정차) 오른쪽 6칸 */
    private val cells = LinkedHashMap<String, SignalCell>()
    /** 재생 전 "지금 차 상태" 4칸 */
    private val stateCells = LinkedHashMap<String, SignalCell>()
    /** 주행 중 아래 4칸 */
    private val stripCells = LinkedHashMap<String, SignalCell>()

    /** 세트 고르기 카드 */
    private class PickerCard(val root: View, val id: String, val look: SoundSet.Look) {
        val badge: TextView = root.findViewById(R.id.card_badge)
        val tempoView: TextView = root.findViewById(R.id.card_tempo)
        val chips: ViewGroup = root.findViewById(R.id.card_chips)
    }

    private var chordName: String? = null
    private var beatOn = -1
    private var lampUntil = 0L
    private var arriveUntil = 0L

    private val cInk by lazy { getColor(R.color.ink) }
    private val cDim by lazy { getColor(R.color.ink_dim) }
    private val cOk by lazy { getColor(R.color.ok) }
    private val cRain by lazy { getColor(R.color.rain) }
    private val cBrake by lazy { getColor(R.color.brake) }
    private val cPanel by lazy { getColor(R.color.panel) }
    private val cLine by lazy { getColor(R.color.line) }
    private val cBg by lazy { getColor(R.color.bg) }
    private val cGlass by lazy { getColor(R.color.glass) }
    private val cGlassLine by lazy { getColor(R.color.glass_line) }
    private lateinit var root: View
    private val uiNight get() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveHub.init(this)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)

        setChip = findViewById(R.id.set_chip)
        setThumb = findViewById<ImageView>(R.id.set_thumb).also { it.clipToOutline = true }
        setName = findViewById(R.id.set_name)
        statusPill = findViewById(R.id.status_pill)
        mode = findViewById(R.id.mode)

        idleView = findViewById(R.id.idle_view)
        heroCard = findViewById(R.id.hero_card)
        heroCover = findViewById<ImageView>(R.id.hero_cover).also { it.clipToOutline = true }
        heroLabel = findViewById(R.id.hero_label)
        heroTitle = findViewById(R.id.hero_title)
        heroSub = findViewById(R.id.hero_sub)
        heroChips = findViewById(R.id.hero_chips)
        idlePlay = findViewById(R.id.idle_play)
        idlePicker = findViewById(R.id.idle_picker)
        idleMenu = findViewById(R.id.idle_menu)
        connDot = findViewById(R.id.conn_dot)
        connText = findViewById(R.id.conn_text)

        liveView = findViewById(R.id.live_view)
        liveRight = findViewById(R.id.live_right)
        bpm = findViewById(R.id.bpm)
        chord = findViewById(R.id.chord)
        beats = findViewById(R.id.beats)
        caption = findViewById(R.id.caption)
        driveCaption = findViewById(R.id.drive_caption)
        driveCaptionLabel = findViewById(R.id.drive_caption_label)
        driveCaptionText = findViewById(R.id.drive_caption_text)
        driveStrip = findViewById(R.id.drive_strip)
        scope = findViewById<ScopeView>(R.id.scope).also { it.engine = player.engine }
        meters = findViewById(R.id.meters)
        play = findViewById(R.id.play)
        demoOverlay = findViewById(R.id.demo_overlay)
        demoTitle = findViewById(R.id.demo_title)
        demoCaption = findViewById(R.id.demo_caption)

        pickerView = findViewById(R.id.picker_view)
        pickerThumb = findViewById<ImageView>(R.id.picker_thumb).also { it.clipToOutline = true }
        pickerName = findViewById(R.id.picker_name)
        pickerGo = findViewById(R.id.picker_go)

        // 제목을 길게 누르면 시연 자막을 켜고 끈다 (발표·영상 촬영용, 일반 사용자는 볼 일 없음)
        findViewById<View>(R.id.app_title).setOnLongClickListener {
            DriveHub.demoCaptions = !DriveHub.demoCaptions; true
        }

        val grid = findViewById<GridLayout>(R.id.signals)
        for ((key, label) in SIGNALS) {
            val v = layoutInflater.inflate(R.layout.item_signal, grid, false)
            grid.addView(v)
            cells[key] = SignalCell(v).also { it.label.text = label }
        }
        val stateGrid = findViewById<GridLayout>(R.id.state_grid)
        for (key in listOf("gear", "speed", "wiper", "arrive")) {
            val v = layoutInflater.inflate(R.layout.item_state, stateGrid, false)
            stateGrid.addView(v)
            stateCells[key] = SignalCell(v).also { it.label.text = SIGNALS.getValue(key) }
        }
        val strip = driveStrip as LinearLayout
        for ((i, key) in listOf("speed", "wiper", "turn", "arrive").withIndex()) {
            val v = layoutInflater.inflate(R.layout.item_signal, strip, false)
            v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(16)
            }
            strip.addView(v)
            stripCells[key] = SignalCell(v).also {
                it.label.text = SIGNALS.getValue(key)
                it.value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                it.tag?.visibility = View.GONE
            }
        }
        buildReactList()
        buildPickerCards()

        val togglePlay = View.OnClickListener {
            val tc = controller?.transportControls ?: return@OnClickListener
            if (player.isPlaying) tc.pause() else tc.play()
        }
        play.setOnClickListener(togglePlay)
        idlePlay.setOnClickListener(togglePlay)
        findViewById<View>(R.id.next).setOnClickListener { controller?.transportControls?.skipToNext() }
        idlePicker.setOnClickListener { openPicker() }
        findViewById<View>(R.id.live_picker).setOnClickListener { openPicker() }
        findViewById<View>(R.id.picker_close).setOnClickListener { pickerOpen = false }
        pickerGo.setOnClickListener {
            // 재생 중이면 세트만 바꾸고(다음 마디부터), 멈춰 있으면 그 세트로 재생을 시작한다
            pickedId?.let { controller?.transportControls?.playFromMediaId(it, null) }
            pickerOpen = false
        }

        for (id in intArrayOf(R.id.idle_menu, R.id.live_menu)) {
            val m = ParkedMenu(findViewById(id))
            menus += m
            m.scenario.setOnClickListener {
                if (DriveHub.scenario != null) {
                    DriveHub.stopScenario()
                } else {
                    DriveHub.startScenario()
                    if (!player.isPlaying) controller?.transportControls?.play()
                }
            }
            m.auto.setOnClickListener { DriveHub.nightOverride = null }
            m.day.setOnClickListener { DriveHub.nightOverride = false }
            m.night.setOnClickListener { DriveHub.nightOverride = true }
        }

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

    @Deprecated("Activity 기본 뒤로 가기")
    override fun onBackPressed() {
        if (pickerOpen) pickerOpen = false else super.onBackPressed()
    }

    // ---------------- 화면 만들기 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildReactList() {
        val list = findViewById<LinearLayout>(R.id.react_list)
        for ((icon, title, desc) in REACTIONS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, 0)
            }
            val iv = ImageView(this).apply { setImageResource(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            row.addView(iv, LinearLayout.LayoutParams(dp(28), dp(28)))
            reactIcons += iv
            row.addView(TextView(this).apply {
                text = title; setTextColor(cInk); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); paint.isFakeBoldText = true
            }, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })
            row.addView(TextView(this).apply {
                text = desc; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            })
            list.addView(row)
        }
    }

    private fun chip(parent: ViewGroup, text: String, fg: Int, bg: Int, sizeSp: Float) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            paint.isFakeBoldText = true
            setBackgroundResource(R.drawable.bg_tag)
            backgroundTintList = ColorStateList.valueOf(bg)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        })
    }

    private fun buildPickerCards() {
        val row = findViewById<LinearLayout>(R.id.picker_cards)
        for ((i, set) in DriveHub.sets.withIndex()) {
            val look = set.look
            val t = set.tempo
            val tempo = "${t.base.roundToInt()}–${(t.base + t.maxKmh * t.perKmh).roundToInt()} BPM"
            val v = layoutInflater.inflate(R.layout.item_set_card, row, false)
            (v.layoutParams as LinearLayout.LayoutParams).apply { if (i > 0) marginStart = dp(20) }
            row.addView(v)
            v.findViewById<ImageView>(R.id.card_cover).apply { clipToOutline = true; setImageResource(Covers.res(this@MainActivity, look.cover)) }
            v.findViewById<TextView>(R.id.card_title).text = look.title
            v.findViewById<TextView>(R.id.card_sub).text = look.sub
            val card = PickerCard(v, set.id, look)
            card.tempoView.text = tempo
            pickerCards += card
            v.setOnClickListener { pickedId = set.id; styledFor = null }
        }
    }

    private fun openPicker() {
        if (DriveHub.restricted) return
        pickedId = DriveHub.currentSet?.id
        pickerOpen = true
        styledFor = null
    }

    /** 세트·주야간이 바뀌었을 때만 색과 커버를 다시 칠한다 */
    private fun applyLook(set: SoundSet?) {
        val night = uiNight
        val restricted = DriveHub.restricted
        val key = "${set?.id}/$night/$pickedId/$pickerOpen/$restricted"
        if (key == styledFor) return
        styledFor = key
        val look = set?.look ?: SoundSet.Look("Drivescape", "")
        val label = look.label(night)
        val cover = Covers.res(this, look.cover)

        // 첫 화면 큰 카드: 세트 색이 왼쪽 위에서 옅게 번진다
        // 바탕: 정차 중에는 세트 색이 은은하게 번지고, 주행 중에는 단색 (글자가 또렷하게)
        root.background = if (restricted) ColorDrawable(cBg) else backdrop(look.accent, night)
        heroCard.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(withAlpha(look.tint(night), 0xD0), cGlass, cGlass)).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), cGlassLine)
        }
        heroCover.setImageResource(cover)
        setThumb.setImageResource(cover)
        heroLabel.setTextColor(label)
        setText(heroTitle, look.title)
        setText(heroSub, look.sub)
        setText(setName, if (look.sub.isEmpty()) look.title else "${look.title} · ${look.sub}")
        heroChips.removeAllViews()
        for (c in look.chips) chip(heroChips, c, label, look.tint(night), 19f)

        for (b in listOf(idlePlay, play)) paintPrimary(b, look)
        scopeAccent = look.accent
        beats.color = look.accent
        driveCaptionLabel.setTextColor(look.labelNight)
        for (iv in reactIcons) iv.imageTintList = ColorStateList.valueOf(label)

        // 세트 고르기 카드
        for (c in pickerCards) {
            val selected = c.id == pickedId
            val tint = c.look.tint(night)
            val shape = GradientDrawable().apply {
                setColor(if (selected) tint else cGlass)
                cornerRadius = dp(28).toFloat()
                if (selected) setStroke(dp(4), c.look.accent) else setStroke(dp(1), cGlassLine)
            }
            c.root.background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape, shape)
            val cl = c.look.label(night)
            c.tempoView.setTextColor(cl)
            c.chips.removeAllViews()
            // 카드에는 템포 줄이 따로 있으므로 BPM 칩은 뺀다
            for (t in c.look.chips) if (!t.endsWith("BPM")) chip(c.chips, t, cl, if (selected) cPanel else tint, 16f)
            if (selected) badge(c.badge, "선택됨", c.look.onAccent, c.look.accent) else c.badge.visibility = View.GONE
        }
        val picked = pickerCards.firstOrNull { it.id == pickedId }?.look ?: look
        pickerThumb.setImageResource(Covers.res(this, picked.cover))
        setText(pickerName, "${picked.title} · ${picked.sub}")
        paintPrimary(pickerGo, picked)
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    /**
     * 유리 카드 뒤에 깔리는 바탕: 단색 위에 세트 색 빛 두 덩이(왼쪽 위, 오른쪽 아래).
     * 정지된 그림이라 흐림 효과 없이도 반투명 카드에 색이 비쳐 보인다.
     */
    private fun backdrop(accent: Int, night: Boolean): Drawable {
        fun glow(cx: Float, cy: Float, radiusDp: Int, alpha: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(cx, cy)
            gradientRadius = dp(radiusDp).toFloat()
            colors = intArrayOf(withAlpha(accent, alpha), withAlpha(accent, 0))
        }
        return LayerDrawable(arrayOf(
            ColorDrawable(cBg),
            glow(0.08f, 0.05f, 1300, if (night) 0x50 else 0x40),
            glow(0.96f, 1.0f, 1000, if (night) 0x34 else 0x28)
        ))
    }

    private fun paintPrimary(b: TextView, look: SoundSet.Look) {
        b.backgroundTintList = ColorStateList.valueOf(look.accent)
        b.setTextColor(look.onAccent)
        b.compoundDrawableTintList = ColorStateList.valueOf(look.onAccent)
    }

    private fun badge(v: TextView, text: String, fg: Int, bg: Int) {
        v.visibility = View.VISIBLE
        v.text = text
        v.setTextColor(fg)
        v.backgroundTintList = ColorStateList.valueOf(bg)
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
        val restricted = DriveHub.restricted
        if (restricted) pickerOpen = false

        player.drainEvents { e ->
            when (e) {
                is UiEvent.Meter -> meters.values[e.layer.ordinal] = max(meters.values[e.layer.ordinal], e.v)
                is UiEvent.Bar -> chordName = e.chord
                is UiEvent.Beat -> beatOn = e.n
                is UiEvent.Blink -> lampUntil = e.t + (e.durSec * SAMPLE_RATE).toLong()
                is UiEvent.Arrive -> arriveUntil = e.t + 2L * SAMPLE_RATE
            }
        }

        val set = DriveHub.currentSet
        applyLook(set)
        val sc = DriveHub.scenario
        val live = running || sc != null

        // 어떤 보기를 보일지
        show(pickerView, pickerOpen)
        show(liveView, !pickerOpen && live)
        show(idleView, !pickerOpen && !live)
        setChip.visibility = if (!pickerOpen && live) View.VISIBLE else View.GONE
        setText(statusPill, when {
            restricted -> "주행 중 · 간단 화면"
            st.gear == Gear.P -> "정차 중"
            else -> "주행 중"
        })
        setText(mode, if (st.night) "밤 · 단조" else "낮 · 장조")

        val ov = DriveHub.nightOverride
        for (m in menus) {
            setText(m.scenario, getString(if (sc != null) R.string.scenario_stop else R.string.scenario_play))
            m.scenario.isSelected = sc != null
            m.auto.isSelected = ov == null
            m.day.isSelected = ov == false
            m.night.isSelected = ov == true
        }

        val lampLit = running && now < lampUntil
        val arriving = running && now < arriveUntil
        if (idleView.visibility == View.VISIBLE) {
            idlePicker.visibility = if (restricted) View.GONE else View.VISIBLE
            idleMenu.visibility = if (restricted) View.GONE else View.VISIBLE
            val anyLive = listOf(DriveHub.gearStatus, DriveHub.speedStatus, DriveHub.wiperStatus, DriveHub.turnStatus, DriveHub.brakeStatus)
                .any { it == SignalStatus.LIVE }
            setText(connText, if (anyLive) "차량 신호 연결됨" else "차량 신호 기다리는 중")
            connDot.backgroundTintList = ColorStateList.valueOf(if (anyLive) cOk else cDim)
            for ((k, c) in stateCells) setText(c.value, valueOf(k, st, lampLit, arriving))
        }
        if (liveView.visibility == View.VISIBLE) renderLive(st, running, now, restricted, lampLit, arriving)
    }

    /** 보기를 바꿀 때 새 화면이 살짝 떠오르며 나타난다 */
    private fun show(v: View, on: Boolean) {
        val want = if (on) View.VISIBLE else View.GONE
        if (v.visibility == want) return
        v.animate().cancel()
        v.visibility = want
        if (on) {
            v.alpha = 0f
            v.translationY = dp(14).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    private fun renderLive(st: DriveState, running: Boolean, now: Long, restricted: Boolean, lampLit: Boolean, arriving: Boolean) {
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
        if (!restricted) meters.invalidate()

        val set = DriveHub.currentSet
        val bpmVal = set?.let { s ->
            if (st.gear == Gear.P) s.tempo.park else s.tempo.base + st.speed.toDouble().coerceIn(0.0, s.tempo.maxKmh) * s.tempo.perKmh
        } ?: 0.0
        setText(bpm, bpmVal.roundToInt().toString())
        val m = set?.let { if (st.night) it.night else it.day }
        setText(chord, if (running && chordName != null) chordName!! else m?.chords?.firstOrNull() ?: "")
        beats.beat = if (running) beatOn else -1

        setText(play, getString(if (running) R.string.stop else R.string.play))
        scope.running = running
        scope.color = if (st.brake) cBrake else if (st.wiper > 0) cRain else scopeAccent
        scope.invalidate()

        val sc = DriveHub.scenario
        val reaction = reactionText(st, arriving)
        setText(caption, when {
            sc != null -> "시나리오 ${fmt(sc.elapsed)} / ${fmt(Scenario.LENGTH)} · ${sc.caption}"
            running -> reaction
            else -> getString(R.string.idle_hint).substringBefore('\n')
        })

        // 세트가 바뀌면 3초 동안 자막 제목에 새 세트 이름을 띄운다
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (set != null && set.id != lastSetId) {
            if (lastSetId != null) setNoticeUntil = nowMs + 3000
            lastSetId = set.id
        }
        val captionTitle = if (nowMs < setNoticeUntil) "세트 → ${set?.look?.title ?: ""}" else "지금 음악이 반응하는 것"

        liveRight.visibility = if (restricted) View.GONE else View.VISIBLE
        caption.visibility = if (restricted) View.GONE else View.VISIBLE
        driveCaption.visibility = if (restricted) View.VISIBLE else View.GONE
        driveStrip.visibility = if (restricted) View.VISIBLE else View.GONE
        if (restricted) {
            setText(driveCaptionLabel, captionTitle)
            setText(driveCaptionText, reaction.replace("  ·  ", "\n"))
            for ((k, c) in stripCells) setText(c.value, valueOf(k, st, lampLit, arriving))
        }

        // 시연 자막 (주행 중엔 큰 자막이 이미 있으므로 숨김)
        val showDemo = DriveHub.demoCaptions && running && !restricted
        demoOverlay.visibility = if (showDemo) View.VISIBLE else View.GONE
        if (showDemo) {
            setText(demoTitle, captionTitle)
            setText(demoCaption, reaction.replace("  ·  ", "\n"))
        }

        if (!restricted) updateSignals(st, lampLit, arriving)
    }

    /** 신호 칸에 쓰는 값 (세 화면 공통) */
    private fun valueOf(key: String, st: DriveState, lampLit: Boolean, arriving: Boolean): String = when (key) {
        "gear" -> if (st.gear == Gear.P) "P 주차" else "D 주행"
        "speed" -> "${st.speed.roundToInt()} km/h"
        "wiper" -> arrayOf("끔", "저속", "고속")[st.wiper.coerceIn(0, 2)]
        "turn" -> when (st.turn) { -1 -> "◀ 왼쪽"; 1 -> "오른쪽 ▶"; else -> "끔" }
        "brake" -> if (st.brake) "밟음" else "뗌"
        else -> {
            val rem = st.remainingMin
            when {
                arriving || (rem != null && rem <= 0f) -> "도착"
                rem != null -> "%.1f분".format(rem)
                else -> "경로 없음"
            }
        }
    }

    private fun updateSignals(st: DriveState, lampLit: Boolean, arriving: Boolean) {
        fun cell(key: String, status: SignalStatus, active: Int? = null,
                 liveLabel: String = "차량 신호", waitLabel: String = "대기") {
            val c = cells.getValue(key)
            setText(c.value, valueOf(key, st, lampLit, arriving))
            c.value.setTextColor(active ?: cInk)
            val tag = c.tag ?: return
            val (tagText, tagColor) = when {
                DriveHub.scenario != null -> "시나리오" to cRain
                status == SignalStatus.LIVE -> liveLabel to cOk
                status == SignalStatus.WAITING -> waitLabel to cDim
                status == SignalStatus.UNSUPPORTED -> "미지원" to cDim
                else -> "권한 없음" to cDim
            }
            setText(tag, tagText)
            tag.setTextColor(tagColor)
        }
        cell("gear", DriveHub.gearStatus, if (st.gear == Gear.D) scopeAccent else null)
        cell("speed", DriveHub.speedStatus)
        cell("wiper", DriveHub.wiperStatus, if (st.wiper > 0) cRain else null)
        cell("turn", DriveHub.turnStatus, if (st.turn != 0 && lampLit) cOk else null)
        cell("brake", DriveHub.brakeStatus, if (st.brake) cBrake else null)
        val rem = st.remainingMin
        cell("arrive", DriveHub.naviStatus,
            if (rem != null && rem <= 5f) getColor(R.color.arp) else null,
            liveLabel = "내비 연동", waitLabel = "내비")
    }

    private fun setText(v: TextView, s: String) { if (v.text.toString() != s) v.text = s }

    /**
     * 지금 음악이 무엇에 반응하고 있는지 (세트와 무관하게 맞는 쉬운 말).
     * 예: "고속 주행 → 달리는 리듬  ·  와이퍼 고속 → 굵은 빗소리"
     */
    private fun reactionText(st: DriveState, arriving: Boolean): String {
        val parts = ArrayList<String>()
        val l = DriveHub.currentSet?.levels
        parts += when {
            st.gear == Gear.P -> "주차 → 잔잔한 앰비언트"
            l == null -> ""
            st.speed < l.slowBelow -> "정지 → 베이스만 길게"
            st.speed < l.cityBelow -> "저속 → 가벼운 리듬"
            st.speed < l.highwayBelow -> "시내 주행 → 경쾌한 리듬"
            else -> "고속 주행 → 달리는 리듬"
        }
        if (st.brake) parts += "브레이크 → 소리를 줄이고 리듬을 덜어 냄"
        when (st.wiper) {
            1 -> parts += "와이퍼 → 빗소리"
            2 -> parts += "와이퍼 고속 → 굵은 빗소리"
        }
        when (st.turn) {
            -1 -> parts += "왼쪽 깜빡이 → 왼쪽에서 틱톡"
            1 -> parts += "오른쪽 깜빡이 → 오른쪽에서 틱톡"
        }
        val rem = st.remainingMin
        if (arriving || (rem != null && rem <= 0f && st.gear == Gear.D)) parts += "도착 → 차임"
        else if (rem != null && rem <= 5f && st.gear == Gear.D) parts += "도착 ${ceil(rem).toInt()}분 전 → 음악이 고조"
        return parts.filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    private fun fmt(sec: Double): String {
        val s = max(0, sec.toInt())
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val REQ_PERMS = 1

        private val SIGNALS = linkedMapOf(
            "gear" to "기어", "speed" to "속도", "wiper" to "와이퍼",
            "turn" to "방향지시등", "brake" to "브레이크", "arrive" to "도착까지"
        )

        private val REACTIONS = listOf(
            Triple(R.drawable.ic_r_speed, "속도", "템포와 리듬이 빨라져요"),
            Triple(R.drawable.ic_r_rain, "와이퍼", "빗소리와 빗방울 음"),
            Triple(R.drawable.ic_r_turn, "깜빡이", "켠 쪽에서 틱톡"),
            Triple(R.drawable.ic_r_brake, "브레이크", "소리가 부드럽게 가라앉아요"),
            Triple(R.drawable.ic_r_flag, "도착", "5분 전부터 고조, 도착하면 차임")
        )

    }
}
