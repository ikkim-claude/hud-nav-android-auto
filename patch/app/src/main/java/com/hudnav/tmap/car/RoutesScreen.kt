package com.hudnav.tmap.car

import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.hudnav.tmap.NavBridge
import org.json.JSONObject

/** 경로 고르기 (추천·최소 시간·무료·최단, 실시간 교통 반영). pick = 검색 결과 번호, -1 = 최근 목적지 */
class RoutesScreen(ctx: CarContext, private val map: MapRenderer, private val pick: Int) :
    Screen(ctx), NavBridge.Listener {
    private var result: JSONObject? = null
    private var sel = 0
    private var lines: List<DoubleArray> = emptyList()
    private var progress = ""
    private var timedOut = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (result == null) { timedOut = true; invalidate() } }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                NavBridge.add(this@RoutesScreen)
                request()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacks(timeout)
                NavBridge.remove(this@RoutesScreen)
                map.setPreview(null, 0)
            }
        })
    }

    // 휴대폰 쪽에 경로 요청. 25초 안에 답이 없으면 멈춘 이유와 "다시 시도"를 보여줌
    private fun request() {
        result = null; timedOut = false; progress = ""
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 25000)
        NavBridge.js("window.carPick ? carPick($pick) : AndroidCar.routes(JSON.stringify({error:" +
            "'휴대폰 HUD 내비 화면이 아직 준비되지 않았어요. 휴대폰에서 앱을 한 번 열어 주세요'}))")
        invalidate()
    }

    override fun onProgress(t: String) { progress = t }

    override fun onRoutes(r: JSONObject) {
        handler.removeCallbacks(timeout)
        timedOut = false
        result = r; sel = 0
        val arr = r.optJSONArray("routes")
        lines = (0 until (arr?.length() ?: 0)).map { i ->
            val p = arr!!.getJSONObject(i).getJSONArray("pts")
            DoubleArray(p.length()) { p.getDouble(it) }
        }
        map.setPreview(lines, 0)
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val r = result
        val retry = Action.Builder().setTitle("다시 시도").setOnClickListener { request() }.build()
        if (r == null && timedOut) {
            val why = if (progress.isEmpty())
                "휴대폰의 HUD 내비가 응답하지 않아요. 휴대폰이 앱을 절전으로 멈춘 것 같아요.\n휴대폰에서 HUD 내비를 열어 두고 다시 시도하세요."
            else "마지막 상태: $progress\n휴대폰 인터넷 연결을 확인하고 다시 시도하세요."
            return MessageTemplate.Builder(why).setTitle("경로를 아직 받지 못했어요")
                .setHeaderAction(Action.BACK).addAction(retry).build()
        }
        if (r != null && r.has("error"))
            return MessageTemplate.Builder(r.optString("error")).setTitle("경로를 찾지 못했어요")
                .setHeaderAction(Action.BACK).addAction(retry).build()
        val b = RoutePreviewNavigationTemplate.Builder()
            .setTitle(r?.optString("name") ?: "실시간 교통으로 경로 찾는 중")
            .setHeaderAction(Action.BACK)
        if (r == null) return b.setLoading(true).build()

        val arr = r.getJSONArray("routes")
        val list = ItemList.Builder()
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            val title = SpannableString("  ${x.optString("label")}")
            title.setSpan(DurationSpan.create(x.optLong("duration")), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val fare = x.optInt("fare")
            val jam = x.optDouble("jam"); val slow = x.optDouble("slow")
            val traffic = when {
                jam >= 100 -> "정체 ${(jam / 100).toInt() / 10.0}km"
                slow >= 300 -> "서행 ${(slow / 100).toInt() / 10.0}km"
                else -> "원활"
            }
            val text = SpannableString("  · ${if (fare > 0) "통행료 %,d원".format(fare) else "무료"} · $traffic")
            text.setSpan(DistanceSpan.create(distanceOf(x.optDouble("distance"))), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val row = Row.Builder().setTitle(title).addText(text)
            x.optString("via").takeIf { it.isNotEmpty() }?.let { row.addText("주요 도로: $it") }
            list.addItem(row.build())
        }
        list.setSelectedIndex(sel)
        list.setOnSelectedListener { i ->
            sel = i
            map.setPreview(lines, i)
            NavBridge.js("carSelect($i)")
        }
        return b.setItemList(list.build())
            .setNavigateAction(Action.Builder().setTitle("안내 시작").setOnClickListener {
                NavBridge.js("carStart($sel)")
                screenManager.popToRoot()
            }.build())
            .build()
    }
}
