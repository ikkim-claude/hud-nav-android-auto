package com.hudnav.tmap

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 휴대폰 화면(WebView 속 HTML)과 Android Auto 차 화면을 잇는 다리.
 * 안내 로직(TMAP 호출, 정체 판단 등)은 모두 HTML 쪽에서 돌고, 여기서는 상태만 주고받는다.
 */
object NavBridge {
    interface Listener {
        fun onState() {}
        fun onRoute() {}
        fun onSearch(r: JSONObject) {}
        fun onRoutes(r: JSONObject) {}
        fun onDecide() {}
    }

    class RouteGeom(val id: Int, val lat: DoubleArray, val lon: DoubleArray, val jams: List<IntArray>)

    private const val TAG = "HudNav"
    private const val URL = "https://appassets.androidplatform.net/assets/index.html"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private lateinit var app: Context

    var webView: WebView? = null; private set
    var state: JSONObject = JSONObject(); private set
    var route: RouteGeom? = null; private set
    var decision: JSONObject? = null; private set
    var carConnected = false
    /** 휴대폰 화면 아래에 보여줄 진단 메시지 */
    var onStatus: ((String) -> Unit)? = null
    private fun status(t: String) { main.post { onStatus?.invoke(t) } }
    var activityAlive = false
    val navigating: Boolean get() = state.optBoolean("nav")

    fun add(l: Listener) = listeners.add(l)
    fun remove(l: Listener) = listeners.remove(l)

    /** 화면에 붙지 않은 상태에서도 돌아가는 WebView 하나를 앱 전체에서 공유 */
    @SuppressLint("SetJavaScriptEnabled")
    fun ensure(ctx: Context): WebView {
        webView?.let { return it }
        app = ctx.applicationContext
        WebView.setWebContentsDebuggingEnabled(true)
        val wv = WebView(MutableContextWrapper(if (ctx is Activity) ctx else app))
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
        }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
            .build()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView, url: String) { status("화면 불러옴") }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status("화면 불러오기 실패: ${error.description} (${request.url})")
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "JS ${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                    status("오류: ${m.message()} (${m.lineNumber()}줄)")
                return true
            }
        }
        wv.addJavascriptInterface(CarJs(), "AndroidCar")
        layoutHeadless(wv)
        wv.loadUrl(URL)
        webView = wv
        return wv
    }

    // 화면에 안 붙어 있어도 지도(Leaflet)가 크기를 알 수 있게
    private fun layoutHeadless(wv: WebView) {
        val w = 1080; val h = 1920
        wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        wv.layout(0, 0, w, h)
    }

    fun attach(activity: Activity): WebView {
        // 차 화면용으로 먼저 만들어진(화면에 안 붙은) WebView는 새로 만들어 휴대폰 화면에 확실히 그려지게
        webView?.let { old ->
            if (old.parent == null && !navigating) { old.destroy(); webView = null }
        }
        val wv = ensure(activity)
        (wv.context as MutableContextWrapper).baseContext = activity
        (wv.parent as? ViewGroup)?.removeView(wv)
        return wv
    }

    fun detach(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        (wv.context as MutableContextWrapper).baseContext = app
        layoutHeadless(wv)
    }

    fun js(code: String) {
        main.post { webView?.evaluateJavascript(code, null) }
    }

    fun pushLocation(l: Location) {
        val spd = if (l.hasSpeed()) l.speed else -1f
        val brg = if (l.hasBearing() && l.hasSpeed() && l.speed > 1.5f) l.bearing else -1f
        js("window.__pos&&__pos(${l.latitude},${l.longitude},${l.accuracy},$spd,$brg,${l.time})")
    }

    fun pushLocationError(msg: String) = js("window.__posErr&&__posErr(${JSONObject.quote(msg)})")

    /** HTML(JS)에서 부르는 함수들. JS 스레드에서 불리므로 메인 스레드로 넘긴다. */
    class CarJs {
        @JavascriptInterface fun startGps() { main.post { LocationService.start(app) } }
        @JavascriptInterface fun speak(text: String) { main.post { Voice.speak(text) } }
        @JavascriptInterface fun hush() { main.post { Voice.stop() } }

        @JavascriptInterface fun state(json: String) {
            val s = parse(json) ?: return
            main.post { state = s; listeners.forEach { it.onState() } }
        }

        @JavascriptInterface fun setRoute(json: String) {
            val j = parse(json) ?: return
            val pts = j.getJSONArray("pts")
            val n = pts.length() / 2
            val lat = DoubleArray(n) { pts.getDouble(it * 2) }
            val lon = DoubleArray(n) { pts.getDouble(it * 2 + 1) }
            val ja = j.getJSONArray("jams")
            val jams = (0 until ja.length()).map { k ->
                val a = ja.getJSONArray(k); intArrayOf(a.getInt(0), a.getInt(1), a.getInt(2))
            }
            val g = RouteGeom(j.getInt("id"), lat, lon, jams)
            main.post { route = g; listeners.forEach { it.onRoute() } }
        }

        @JavascriptInterface fun search(json: String) {
            val j = parse(json) ?: return
            main.post { listeners.forEach { it.onSearch(j) } }
        }

        @JavascriptInterface fun routes(json: String) {
            val j = parse(json) ?: return
            main.post { listeners.forEach { it.onRoutes(j) } }
        }

        @JavascriptInterface fun decide(json: String) {
            val j = if (json == "null") null else parse(json)
            main.post { decision = j; listeners.forEach { it.onDecide() } }
        }

        private fun parse(s: String): JSONObject? =
            try { JSONObject(s) } catch (e: Exception) { Log.w(TAG, "bad json", e); null }
    }
}
