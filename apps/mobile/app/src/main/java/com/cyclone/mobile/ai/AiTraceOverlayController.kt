package com.cyclone.mobile.ai

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.cyclone.mobile.CycloneAccessibilityService

/**
 * User-only live decision overlay.
 *
 * This uses TYPE_ACCESSIBILITY_OVERLAY because Cyclone already runs an AccessibilityService.
 * It is NOT focusable or touchable, so it does not become the active app window and the agent's
 * normal rootInActiveWindow observation keeps targeting the underlying app. The overlay receives
 * only AiTraceBus summaries; it is never inserted into the model's prompt/history.
 */
object AiTraceOverlayRuntime {
    private var controller: AiTraceOverlayController? = null

    @Synchronized
    fun enable(service: CycloneAccessibilityService) {
        if (controller != null) return
        controller = AiTraceOverlayController(service).also { it.show() }
    }

    @Synchronized
    fun disable() {
        controller?.dismiss()
        controller = null
    }

    fun isVisible(): Boolean = controller != null
}

class AiTraceOverlayController(private val service: CycloneAccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var status: TextView? = null
    private var unsubscribe: (() -> Unit)? = null

    fun show() {
        if (root != null) return
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 24, 24, 32))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            elevation = dp(12).toFloat()
        }
        container.addView(TextView(service).apply {
            text = "✦ Cyclone AI · live decision stream"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        status = TextView(service).apply {
            text = AiTraceBus.latest?.displayText ?: "Waiting for the next AI decision…"
            setTextColor(Color.rgb(224, 224, 232))
            textSize = 12f
            maxLines = 4
            setPadding(0, dp(4), 0, 0)
        }
        container.addView(status)

        val params = WindowManager.LayoutParams(
            dp(330),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        root = container
        wm.addView(container, params)
        unsubscribe = AiTraceBus.subscribe { event ->
            main.post {
                status?.text = event.displayText
            }
        }
    }

    fun dismiss() {
        unsubscribe?.invoke()
        unsubscribe = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        status = null
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
