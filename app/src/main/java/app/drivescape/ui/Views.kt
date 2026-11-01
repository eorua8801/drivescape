package app.drivescape.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import app.drivescape.R
import app.drivescape.audio.Layer
import app.drivescape.audio.SynthEngine

/** 출력 파형 (프로토타입의 오실로스코프). */
class ScopeView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var engine: SynthEngine? = null
    var running = false
    var color: Int = context.getColor(R.color.amber)

    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = context.getColor(R.color.line); strokeWidth = 1f * d }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val buf = FloatArray(2048)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1 until 8) { val x = w * i / 8; canvas.drawLine(x, 0f, x, h, grid) }
        canvas.drawLine(0f, h / 2, w, h / 2, grid)
        wave.color = color
        path.reset()
        val e = engine
        if (running && e != null) {
            val size = SynthEngine.SCOPE_SIZE
            val end = e.scopeWrite
            for (i in buf.indices) buf[i] = e.scope[(end - buf.size + i + size) and (size - 1)]
            var start = 0
            for (i in 1 until buf.size / 2) if (buf[i - 1] < 0 && buf[i] >= 0) { start = i; break }
            val span = buf.size / 2
            for (i in 0 until span) {
                val x = i.toFloat() / (span - 1) * w
                val y = h / 2 - buf[start + i] * h * 0.85f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        } else {
            path.moveTo(0f, h / 2); path.lineTo(w, h / 2)
        }
        canvas.drawPath(path, wave)
    }
}

/** 레이어별 미터 (어떤 신호가 어떤 소리를 움직이는지 보여 준다). */
class MeterView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class Row(val name: String, val source: String, val color: Int)

    private val rows = mapOf(
        Layer.PAD to Row("패드", "시간대 · 코드", R.color.amber),
        Layer.BASS to Row("베이스", "속도", R.color.amber),
        Layer.DRUMS to Row("드럼", "속도 · 브레이크", R.color.amber),
        Layer.ARP to Row("아르페지오", "도착까지 남은 시간", R.color.arp),
        Layer.RAIN to Row("빗소리 · 빗방울", "와이퍼", R.color.rain),
        Layer.WIPER to Row("와이퍼 스윕", "와이퍼 주기", R.color.rain),
        Layer.TURN to Row("깜빡이 틱톡", "방향지시등", R.color.ok),
        Layer.BELL to Row("차임", "정차 · 도착", R.color.arp)
    )
    val values = FloatArray(Layer.entries.size)

    private val d = resources.displayMetrics.density
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.ink); textSize = 17 * d; isFakeBoldText = true }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.ink_dim); textSize = 13 * d }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.chip) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = rows.mapValues { context.getColor(it.value.color) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (Layer.entries.size * ROW_DP * d).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val row = ROW_DP * d
        val labelW = width * 0.42f
        val barH = 10 * d
        Layer.entries.forEachIndexed { i, l ->
            val y = i * row
            val r = rows.getValue(l)
            canvas.drawText(r.name, 0f, y + row * 0.47f, name)
            canvas.drawText(r.source, 0f, y + row * 0.82f, sub)
            val top = y + (row - barH) / 2; val bottom = top + barH
            canvas.drawRoundRect(labelW, top, width.toFloat(), bottom, barH / 2, barH / 2, track)
            val v = values[i].coerceIn(0f, 1f)
            if (v > 0.005f) {
                fill.color = colors.getValue(l)
                val right = labelW + (width - labelW) * v
                canvas.drawRoundRect(labelW, top, maxOf(right, labelW + barH), bottom, barH / 2, barH / 2, fill)
            }
        }
    }

    companion object { private const val ROW_DP = 46f }
}

/** 칩처럼 짧은 항목을 가로로 늘어놓고, 넘치면 다음 줄로 넘긴다. */
class FlowLayout(context: Context, attrs: AttributeSet? = null) : android.view.ViewGroup(context, attrs) {
    private val gap = (10 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0; var usedW = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            usedW = maxOf(usedW, x - gap)
            rowH = maxOf(rowH, c.measuredHeight)
        }
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec)
                else usedW + paddingLeft + paddingRight
        setMeasuredDimension(w, y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** 세트 커버 그림 (drawable `cover_<이름>`, 없으면 기본 세트 커버). */
object Covers {
    fun res(context: Context, cover: String): Int {
        val id = if (cover.isEmpty()) 0 else context.resources.getIdentifier("cover_$cover", "drawable", context.packageName)
        return if (id != 0) id else R.drawable.cover_ambient
    }

    /** 미디어 위젯 앨범 그림용 비트맵 */
    fun bitmap(context: Context, cover: String, px: Int): android.graphics.Bitmap {
        val d = context.getDrawable(res(context, cover))!!
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        return bmp
    }
}
