package com.hudnav.tmap.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.hudnav.tmap.NavBridge
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 차 화면 지도 영역 그리기: 검은 바탕 위에 진행 방향을 위로 한 경로선(HUD 느낌).
 * - 갈 길: 흰 테두리 + 파란 선, 지나온 길: 회색, 서행·지체·정체: 노랑·주황·빨강
 * - 다음 회전 지점 핀, 내 차 화살표, 현재 도로·정체 상태·속도
 */
class MapRenderer(private val ctx: CarContext) : SurfaceCallback {
    private var container: SurfaceContainer? = null
    private var visible: Rect? = null
    private var state = JSONObject()
    private var route: NavBridge.RouteGeom? = null
    private var preview: List<DoubleArray>? = null
    private var previewSel = 0

    // 부드러운 이동 (GPS 1초 간격 사이를 이어서 그림)
    private var fLat = Double.NaN; private var fLon = 0.0; private var fHead = 0.0
    private var tLat = Double.NaN; private var tLon = 0.0; private var tHead = 0.0
    private var animStart = 0L
    private val main = Handler(Looper.getMainLooper())
    private val tiles = TileLayer { if (SystemClock.uptimeMillis() - animStart >= ANIM) draw() }
    private val tick = object : Runnable {
        override fun run() {
            draw()
            if (SystemClock.uptimeMillis() - animStart < ANIM) main.postDelayed(this, 33)
        }
    }

    override fun onSurfaceAvailable(c: SurfaceContainer) { container = c; draw() }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { container = null }
    override fun onVisibleAreaChanged(r: Rect) { visible = Rect(r); draw() }
    override fun onStableAreaChanged(r: Rect) {}

    fun release() { main.removeCallbacks(tick); tiles.release(); container = null }

    fun setRoute(r: NavBridge.RouteGeom?) { route = r; draw() }

    fun setPreview(lines: List<DoubleArray>?, sel: Int) { preview = lines; previewSel = sel; draw() }

    fun setState(s: JSONObject) {
        state = s
        val p = s.optJSONObject("me") ?: s.optJSONObject("here")
        if (p == null) { draw(); return }
        val head = if (s.isNull("heading")) tHead else s.optDouble("heading", tHead)
        if (tLat.isNaN()) {
            fLat = p.getDouble("lat"); fLon = p.getDouble("lon"); fHead = head
        } else {
            val now = current(); fLat = now[0]; fLon = now[1]; fHead = now[2]
        }
        tLat = p.getDouble("lat"); tLon = p.getDouble("lon"); tHead = head
        animStart = SystemClock.uptimeMillis()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun current(): DoubleArray {
        val f = min(1.0, (SystemClock.uptimeMillis() - animStart) / ANIM.toDouble())
        val dh = ((tHead - fHead + 540) % 360) - 180
        return doubleArrayOf(fLat + (tLat - fLat) * f, fLon + (tLon - fLon) * f, (fHead + dh * f + 360) % 360)
    }

    private fun draw() {
        val c = container ?: return
        val sf = c.surface ?: return
        if (!sf.isValid || c.width <= 0) return
        val canvas = try { sf.lockCanvas(null) } catch (e: Exception) { null } ?: return
        try { render(canvas, c.width, c.height, c.dpi) } finally { sf.unlockCanvasAndPost(canvas) }
    }

    // ---------- 그리기 ----------
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }

    private fun render(cv: Canvas, w: Int, h: Int, dpi: Int) {
        cv.drawColor(Color.BLACK)
        val dp = max(1f, dpi / 160f)
        val area = visible?.takeIf { !it.isEmpty } ?: Rect(0, 0, w, h)
        preview?.takeIf { it.isNotEmpty() }?.let { drawPreview(cv, area, dp, it); return }

        if (tLat.isNaN()) {
            text.textSize = 20 * dp; text.textAlign = Paint.Align.CENTER; text.color = Color.LTGRAY
            cv.drawText("GPS 신호를 찾는 중…", area.exactCenterX(), area.exactCenterY(), text)
            return
        }
        val now = current()
        val lat0 = now[0]; val lon0 = now[1]; val head = now[2]
        val r = route
        val nav = state.optBoolean("nav") && r != null && r.id == state.optInt("routeId", -1) && !state.optBoolean("arrived")
        val ax = area.exactCenterX()
        val ay = area.top + area.height() * (if (nav) 0.72f else 0.55f)
        val dNext = state.optDouble("dNext", 99999.0)
        val kph = state.optDouble("kph", 0.0)
        val mPerDp = when {
            !nav -> 2.0
            dNext < 300 -> 1.0
            dNext < 1000 -> 2.0
            kph > 70 -> 4.5
            else -> 2.6
        }
        val k = dp / mPerDp
        val kx = cos(Math.toRadians(lat0)) * 111320.0; val ky = 110540.0
        val hr = Math.toRadians(head); val ch = cos(hr); val sh = sin(hr)
        val out = FloatArray(2)
        fun proj(lat: Double, lon: Double) {
            val x = (lon - lon0) * kx; val y = (lat - lat0) * ky
            out[0] = (ax + (x * ch - y * sh) * k).toFloat()
            out[1] = (ay - (x * sh + y * ch) * k).toFloat()
        }
        fun path(from: Int, to: Int, startAtMe: Boolean, endAtMe: Boolean): Path {
            val p = Path()
            var first = true
            fun add(lat: Double, lon: Double) {
                proj(lat, lon)
                if (first) { p.moveTo(out[0], out[1]); first = false } else p.lineTo(out[0], out[1])
            }
            if (startAtMe) add(lat0, lon0)
            for (i in max(0, from)..min(to, r!!.lat.size - 1)) add(r.lat[i], r.lon[i])
            if (endAtMe) add(lat0, lon0)
            return p
        }

        tiles.draw(cv, w, h, ax, ay, lat0, lon0, k, -head.toFloat())
        drawAttribution(cv, area, dp)

        if (nav) {
            val seg = state.optInt("seg", 0)
            val last = r!!.lat.size - 1
            // 지나온 길
            stroke.color = Color.rgb(0x60, 0x60, 0x60); stroke.strokeWidth = 11 * dp
            cv.drawPath(path(0, seg, false, true), stroke)
            // 갈 길
            val ahead = path(seg + 1, last, true, false)
            stroke.color = Color.WHITE; stroke.strokeWidth = 17 * dp; cv.drawPath(ahead, stroke)
            stroke.color = Color.rgb(0x0a, 0x5c, 0xff); stroke.strokeWidth = 12 * dp; cv.drawPath(ahead, stroke)
            // 서행·지체·정체
            stroke.strokeWidth = 9 * dp
            for (j in r.jams) {
                if (j[1] <= seg) continue
                stroke.color = when { j[2] >= 4 -> Color.rgb(0xe5, 0x26, 0x1b); j[2] == 3 -> Color.rgb(0xff, 0x7a, 0x1a); else -> Color.rgb(0xff, 0xc4, 0) }
                cv.drawPath(path(max(j[0], seg + 1), j[1], j[0] <= seg, false), stroke)
            }
            // 다음 회전 지점
            state.optJSONObject("turnPt")?.let { tp ->
                proj(tp.getDouble("lat"), tp.getDouble("lon"))
                fill.color = Color.WHITE; cv.drawCircle(out[0], out[1], 12 * dp, fill)
                fill.color = parseColor(state.optString("color"), Color.rgb(0, 0xff, 0x88))
                cv.drawCircle(out[0], out[1], 8.5f * dp, fill)
            }
        }
        drawCar(cv, ax, ay, dp, !state.isNull("heading") && state.has("heading"))
        drawInfo(cv, area, dp, nav)
    }

    private fun drawCar(cv: Canvas, x: Float, y: Float, dp: Float, hasHeading: Boolean) {
        if (!hasHeading) {
            fill.color = Color.WHITE; cv.drawCircle(x, y, 14 * dp, fill)
            fill.color = Color.rgb(0xff, 0x3b, 0x30); cv.drawCircle(x, y, 10 * dp, fill)
            return
        }
        val p = Path().apply {
            moveTo(x, y - 24 * dp); lineTo(x + 17 * dp, y + 20 * dp); lineTo(x, y + 11 * dp)
            lineTo(x - 17 * dp, y + 20 * dp); close()
        }
        fill.color = Color.rgb(0xff, 0x3b, 0x30); cv.drawPath(p, fill)
        stroke.color = Color.WHITE; stroke.strokeWidth = 3.5f * dp; cv.drawPath(p, stroke)
    }

    private fun drawInfo(cv: Canvas, area: Rect, dp: Float, nav: Boolean) {
        val pad = 14 * dp
        // 속도 (왼쪽 아래 원)
        val cx = area.left + pad + 30 * dp; val cy = area.bottom - pad - 30 * dp
        fill.color = Color.argb(220, 20, 20, 20); cv.drawCircle(cx, cy, 30 * dp, fill)
        stroke.color = Color.rgb(0x44, 0x44, 0x44); stroke.strokeWidth = 2 * dp; cv.drawCircle(cx, cy, 30 * dp, stroke)
        text.textAlign = Paint.Align.CENTER; text.color = Color.WHITE
        text.textSize = 22 * dp; cv.drawText(state.optInt("kph").toString(), cx, cy + 4 * dp, text)
        text.textSize = 10 * dp; text.color = Color.GRAY; cv.drawText("km/h", cx, cy + 17 * dp, text)
        if (!nav) return

        // 지금 달리는 도로 + 정체 상태 (아래 가운데)
        val road = state.optString("road").ifEmpty { "도로" }
        val st = state.optString("roadState")
        val level = state.optInt("roadLevel", 1)
        val stColor = when { level >= 3 -> Color.rgb(0xff, 0x6b, 0x61); level == 2 -> Color.rgb(0xff, 0xc4, 0); else -> Color.rgb(0x7d, 0xff, 0x9c) }
        text.textSize = 17 * dp; text.textAlign = Paint.Align.LEFT; text.color = Color.WHITE
        val rw = text.measureText(road)
        val small = Paint(text).apply { textSize = 13 * dp; color = stColor }
        val sw = small.measureText(st)
        val left = area.left + pad + 70 * dp
        val maxW = area.right - pad - left
        val bw = min(maxW, rw + sw + 34 * dp)
        val box = RectF(left, cy - 18 * dp, left + bw, cy + 18 * dp)
        fill.color = if (level >= 3) Color.rgb(0x2a, 0x0c, 0x0c) else Color.argb(230, 27, 27, 27)
        cv.drawRoundRect(box, 18 * dp, 18 * dp, fill)
        stroke.color = if (level >= 3) Color.rgb(0xff, 0x3b, 0x30) else if (level == 2) Color.rgb(0xff, 0xc4, 0) else Color.rgb(0x33, 0x33, 0x33)
        stroke.strokeWidth = 2 * dp; cv.drawRoundRect(box, 18 * dp, 18 * dp, stroke)
        cv.save(); cv.clipRect(box)
        cv.drawText(road, left + 14 * dp, cy + 6 * dp, text)
        cv.drawText(st, left + 14 * dp + rw + 8 * dp, cy + 5 * dp, small)
        cv.restore()

        // 정체·주의 알림 (위쪽)
        val alert = state.optString("alert")
        if (alert.isNotEmpty()) {
            text.textSize = 16 * dp; text.textAlign = Paint.Align.CENTER; text.color = Color.rgb(0xff, 0xcc, 0)
            val aw = min(area.width() - 2 * pad, text.measureText(alert) + 28 * dp)
            val top = area.top + pad
            val ab = RectF(area.exactCenterX() - aw / 2, top, area.exactCenterX() + aw / 2, top + 32 * dp)
            fill.color = Color.argb(225, 12, 12, 12); cv.drawRoundRect(ab, 16 * dp, 16 * dp, fill)
            cv.save(); cv.clipRect(ab); cv.drawText(alert, area.exactCenterX(), top + 22 * dp, text); cv.restore()
        }
    }

    // 경로 미리보기: 북쪽 위, 모든 후보 경로가 한 화면에
    private fun drawPreview(cv: Canvas, area: Rect, dp: Float, lines: List<DoubleArray>) {
        var minLa = 90.0; var maxLa = -90.0; var minLo = 180.0; var maxLo = -180.0
        for (l in lines) for (i in l.indices step 2) {
            minLa = min(minLa, l[i]); maxLa = max(maxLa, l[i]); minLo = min(minLo, l[i + 1]); maxLo = max(maxLo, l[i + 1])
        }
        val la0 = (minLa + maxLa) / 2; val lo0 = (minLo + maxLo) / 2
        val kx = cos(Math.toRadians(la0)) * 111320.0; val ky = 110540.0
        val pad = 36 * dp
        val wM = max(50.0, (maxLo - minLo) * kx); val hM = max(50.0, (maxLa - minLa) * ky)
        val k = min((area.width() - 2 * pad) / wM, (area.height() - 2 * pad) / hM)
        val cx = area.exactCenterX(); val cy = area.exactCenterY()
        tiles.draw(cv, area.right, area.bottom, cx, cy, la0, lo0, k, 0f)
        drawAttribution(cv, area, dp)
        fun path(l: DoubleArray) = Path().apply {
            for (i in l.indices step 2) {
                val x = (cx + (l[i + 1] - lo0) * kx * k).toFloat(); val y = (cy - (l[i] - la0) * ky * k).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        lines.forEachIndexed { i, l ->
            if (i == previewSel) return@forEachIndexed
            stroke.color = Color.rgb(0x55, 0x66, 0x88); stroke.strokeWidth = 6 * dp; cv.drawPath(path(l), stroke)
        }
        lines.getOrNull(previewSel)?.let { l ->
            val p = path(l)
            stroke.color = Color.WHITE; stroke.strokeWidth = 13 * dp; cv.drawPath(p, stroke)
            stroke.color = Color.rgb(0x2f, 0x7c, 0xf6); stroke.strokeWidth = 9 * dp; cv.drawPath(p, stroke)
            fun dot(i: Int, color: Int) {
                val x = (cx + (l[i + 1] - lo0) * kx * k).toFloat(); val y = (cy - (l[i] - la0) * ky * k).toFloat()
                fill.color = Color.WHITE; cv.drawCircle(x, y, 10 * dp, fill)
                fill.color = color; cv.drawCircle(x, y, 7 * dp, fill)
            }
            dot(0, Color.rgb(0x00, 0xe6, 0x76))
            dot(l.size - 2, Color.rgb(0xe7, 0x4c, 0x3c))
        }
    }

    private fun drawAttribution(cv: Canvas, area: Rect, dp: Float) {
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 200, 200, 200); textSize = 9 * dp; textAlign = Paint.Align.RIGHT }
        cv.drawText("© OpenStreetMap © CARTO", area.right - 6 * dp, area.bottom - 5 * dp, t)
    }

    private fun parseColor(s: String, def: Int) = try { Color.parseColor(s) } catch (e: Exception) { def }

    companion object { private const val ANIM = 950L }
}
