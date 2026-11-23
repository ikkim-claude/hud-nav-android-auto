package com.hudnav.tmap

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** 휴대폰 화면: 기존 HUD 내비 HTML을 그대로 띄움 */
class MainActivity : AppCompatActivity() {
    private var web: WebView? = null

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            LocationService.start(this)
        else NavBridge.pushLocationError("위치 권한을 허용해야 안내할 수 있어요")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        NavBridge.activityAlive = true
        val frame = FrameLayout(this)
        setContentView(frame)
        val wv = NavBridge.attach(this)
        frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = wv

        // 진단 표시줄: 불러오기 실패나 오류가 있으면 화면 아래에 글자로 보여줌
        val info = TextView(this).apply {
            setTextColor(Color.YELLOW); setBackgroundColor(Color.argb(200, 0, 0, 0))
            textSize = 13f; setPadding(24, 16, 24, 16); text = "화면 불러오는 중…"
        }
        frame.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        NavBridge.onStatus = { t ->
            info.text = t; info.visibility = android.view.View.VISIBLE
            if (t == "화면 불러옴") info.postDelayed({ if (info.text == t) info.visibility = android.view.View.GONE }, 3000)
        }

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        askPerms.launch(perms.toTypedArray())
    }

    override fun onDestroy() {
        NavBridge.onStatus = null
        web?.let { NavBridge.detach(it) }
        web = null
        NavBridge.activityAlive = false
        // 차에 연결돼 있지 않고 안내 중도 아니면 GPS 끄기
        if (isFinishing && !NavBridge.carConnected && !NavBridge.navigating) LocationService.stop(this)
        super.onDestroy()
    }
}
