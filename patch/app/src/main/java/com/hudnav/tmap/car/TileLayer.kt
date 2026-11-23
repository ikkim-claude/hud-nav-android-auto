package com.hudnav.tmap.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** 차 화면 배경지도: 어두운 지도 타일(© OpenStreetMap © CARTO)을 받아 그림 */
class TileLayer(private val onLoaded: () -> Unit) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = Collections.synchronizedSet(HashSet<String>())
    private val failedAt = HashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 235 }
    private val dst = RectF()
    private val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    fun release() { pool.shutdownNow() }

    /**
     * (cx, cy) 화면 점에 (lat0, lon0)을 두고, pxPerMeter 축척, rotDeg만큼 돌려서(진행 방향 위) 그림
     */
    fun draw(cv: Canvas, w: Int, h: Int, cx: Float, cy: Float, lat0: Double, lon0: Double,
             pxPerMeter: Double, rotDeg: Float) {
        val cosLat = cos(Math.toRadians(lat0))
        val earth = 40075016.686 * cosLat
        val z = (log2(pxPerMeter * earth / 256.0)).roundToInt().coerceIn(3, 19)
        val tilePxPerMeter = 2.0.pow(z) * 256.0 / earth
        val s = (pxPerMeter / tilePxPerMeter).toFloat()          // 화면 px / 타일 px
        val n = 2.0.pow(z)
        val gx = (lon0 + 180.0) / 360.0 * 256.0 * n
        val latR = Math.toRadians(lat0)
        val gy = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * 256.0 * n
        val r = hypot(w.toDouble(), h.toDouble()) * 0.8 / s           // 돌려도 화면을 덮을 만큼(타일 px)
        val tx0 = floor((gx - r) / 256).toInt(); val tx1 = floor((gx + r) / 256).toInt()
        val ty0 = floor((gy - r) / 256).toInt().coerceAtLeast(0)
        val ty1 = floor((gy + r) / 256).toInt().coerceAtMost(n.toInt() - 1)
        cv.save()
        cv.translate(cx, cy)
        cv.rotate(rotDeg)
        for (ty in ty0..ty1) for (txRaw in tx0..tx1) {
            val tx = ((txRaw % n.toInt()) + n.toInt()) % n.toInt()
            val bmp = tile(z, tx, ty) ?: continue
            val left = ((txRaw * 256.0 - gx) * s).toFloat()
            val top = ((ty * 256.0 - gy) * s).toFloat()
            dst.set(left, top, left + 256 * s + 0.6f, top + 256 * s + 0.6f)
            cv.drawBitmap(bmp, null, dst, paint)
        }
        cv.restore()
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return it }
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < 15000) return null
        if (!pending.add(key)) return null
        pool.execute {
            try {
                val sub = "abcd"[(x + y) % 4]
                val c = URL("https://$sub.basemaps.cartocdn.com/dark_all/$key.png").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "HudNav/1.0 (personal Android Auto app)")
                c.connectTimeout = 8000; c.readTimeout = 8000
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
                c.disconnect()
                main.post {
                    pending.remove(key)
                    if (bmp != null) { cache.put(key, bmp); onLoaded() } else failedAt[key] = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                main.post { pending.remove(key); failedAt[key] = System.currentTimeMillis() }
            }
        }
        return null
    }
}
