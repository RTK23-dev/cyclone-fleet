package com.cyclone.mobile.applearner

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.MainActivity
import kotlin.math.abs

/** Persistent learning controls over the selected app, implemented as an Accessibility overlay. */
class AppLearnerOverlayController(private val service: CycloneAccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var status: TextView? = null
    private val refresh = object : Runnable {
        override fun run() {
            update()
            if (bubble != null) main.postDelayed(this, 700)
        }
    }

    fun show() {
        if (bubble != null) return
        bubbleParams = params(dp(68), dp(68), false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14); y = dp(270)
        }
        bubble = TextView(service).apply {
            text = "◎\nLEARN"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            background = rounded(Color.rgb(88, 78, 238), dp(34).toFloat())
            elevation = dp(12).toFloat()
            setOnTouchListener(BubbleDrag())
        }
        wm.addView(bubble, bubbleParams)
        main.post(refresh)
    }

    fun dismiss() {
        main.removeCallbacks(refresh)
        panel?.let { runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        panel = null; bubble = null; status = null; bubbleParams = null
    }

    private fun togglePanel() { if (panel == null) showPanel() else hidePanel() }

    private fun showPanel() {
        val progress = AppLearnerRuntime.progress()
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(248, 248, 252), dp(22).toFloat())
            elevation = dp(14).toFloat()
        }
        root.addView(TextView(service).apply {
            text = "Cyclone App Learner · BETA"
            textSize = 18f; setTextColor(Color.rgb(24, 23, 33)); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(service).apply {
            text = "Cyclone is mapping only ${progress.appLabel ?: "the selected app"}. High-risk actions are mapped but not pressed."
            textSize = 12f; setTextColor(Color.rgb(78, 76, 88)); setPadding(0, dp(5), 0, dp(6))
        })
        status = TextView(service).apply { textSize = 12f; setTextColor(Color.rgb(50, 49, 60)); setPadding(0, dp(4), 0, dp(8)) }
        root.addView(status)
        val controls = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button(if (progress.state == LearnerSessionState.PAUSED) "Resume" else "Pause") {
            if (AppLearnerRuntime.progress().state == LearnerSessionState.PAUSED) AppLearnerRuntime.resume() else AppLearnerRuntime.pause()
            rebuildPanel()
        }, weight())
        controls.addView(button(if (progress.state == LearnerSessionState.WAITING_FOR_HUMAN) "Return" else "Take over") {
            if (AppLearnerRuntime.progress().state == LearnerSessionState.WAITING_FOR_HUMAN) AppLearnerRuntime.returnFromTakeover() else AppLearnerRuntime.takeOver()
            rebuildPanel()
        }, weight())
        controls.addView(button("Stop") {
            AppLearnerRuntime.stop(); dismiss()
        }, weight())
        root.addView(controls)
        root.addView(button("Open Cyclone V2.5") {
            service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })
        panel = root
        wm.addView(root, params(dp(342), WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(88); y = dp(170)
        })
        update()
    }

    private fun rebuildPanel() { hidePanel(); showPanel() }
    private fun hidePanel() { panel?.let { runCatching { wm.removeView(it) } }; panel = null; status = null }

    private fun update() {
        val p = AppLearnerRuntime.progress()
        bubble?.text = when (p.state) {
            LearnerSessionState.COMPLETE -> "✓\nDONE"
            LearnerSessionState.PAUSED -> "Ⅱ\nPAUSE"
            LearnerSessionState.WAITING_FOR_HUMAN -> "✋\nYOU"
            LearnerSessionState.FAILED -> "!\nLEARN"
            else -> "${p.screens}\nLEARN"
        }
        status?.text = buildString {
            appendLine("${p.currentActivity}")
            appendLine("Screen: ${p.currentScreen ?: "observing"}")
            append("${p.screens} screens · ${p.actions} actions · ${p.transitions} paths")
            if (p.approvalBoundaries > 0) append(" · ${p.approvalBoundaries} approval boundaries")
            p.message?.let { append("\n$it") }
        }
    }

    private fun button(text: String, action: () -> Unit) = Button(service).apply {
        this.text = text; isAllCaps = false; textSize = 11f; setOnClickListener { action() }
    }
    private fun weight() = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    private fun params(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, android.graphics.PixelFormat.TRANSLUCENT)
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    private inner class BubbleDrag : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var moved = false
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; moved = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > dp(8)) moved = true
                    p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); wm.updateViewLayout(v, p); return true
                }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(); return true }
            }
            return false
        }
    }
}
