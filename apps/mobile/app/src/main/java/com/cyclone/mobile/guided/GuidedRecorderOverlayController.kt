package com.cyclone.mobile.guided

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Floating teacher controls built as an Accessibility overlay: no SYSTEM_ALERT_WINDOW permission.
 * The full-screen placement canvas is only present while the user is explicitly teaching one step.
 */
class GuidedRecorderOverlayController(
    private val service: CycloneAccessibilityService,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val engine = GuidedRecorderEngine(service)
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var capture: View? = null
    private var status: TextView? = null
    private var nameField: EditText? = null
    private var aiCheck: CheckBox? = null
    private var busy = false

    fun show() {
        if (bubble != null) return
        bubbleParams = overlayParams(dp(64), dp(64), focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(180)
        }
        bubble = TextView(service).apply {
            text = "●\nREC"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            background = rounded(Color.rgb(205, 42, 58), dp(32).toFloat())
            elevation = dp(10).toFloat()
            setOnTouchListener(BubbleDragListener())
        }
        wm.addView(bubble, bubbleParams)
        DeviceState.addLog("Guided recorder bubble opened")
    }

    fun dismiss() {
        capture?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        capture = null; panel = null; bubble = null
        panelParams = null; bubbleParams = null
        if (engine.isRecording()) engine.cancel()
    }

    private fun togglePanel() {
        if (panel != null) hidePanel() else showPanel()
    }

    private fun showPanel() {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(248, 248, 252), dp(22).toFloat())
            elevation = dp(14).toFloat()
        }
        root.addView(TextView(service).apply {
            text = "Teach Cyclone"
            setTextColor(Color.rgb(25, 24, 34)); textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(service).apply {
            text = "Record a real routine. Choose Tap, Hold, Swipe or Check, then place it on the app. Cyclone performs the step, saves before/after screenshots and the full UI tree, and learns a reusable selector. Add waits when a page needs time, then Save."
            setTextColor(Color.rgb(73, 72, 83)); textSize = 12.5f
            setPadding(0, dp(5), 0, dp(9))
        })
        nameField = EditText(service).apply {
            hint = "Workflow name"
            setText(engine.currentName())
            isSingleLine = true
        }
        root.addView(nameField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        status = TextView(service).apply {
            setTextColor(Color.rgb(80, 78, 91)); textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(status)

        if (!engine.isRecording()) {
            root.addView(actionButton("● Start recording") {
                val name = nameField?.text?.toString().orEmpty()
                engine.start(name)
                updateUi()
                rebuildPanel()
            })
        } else {
            root.addView(toolRow(
                actionButton("Tap") { place(GuidedActionKind.TAP, "Tap the exact control Cyclone should press") },
                actionButton("Hold") { place(GuidedActionKind.HOLD, "Tap where Cyclone should press and hold") },
                actionButton("Swipe ↗") { place(GuidedActionKind.SWIPE, "Drag the arrow from start to end") },
            ))
            root.addView(toolRow(
                actionButton("Check ✓") { place(GuidedActionKind.ASSERT, "Tap an element that must exist before continuing") },
                actionButton("Back") { recordSystem(GuidedActionKind.BACK) },
                actionButton("Home") { recordSystem(GuidedActionKind.HOME) },
            ))
            root.addView(TextView(service).apply {
                text = "Wait timer"
                setTextColor(Color.rgb(48, 47, 57)); textSize = 12f
                setPadding(0, dp(8), 0, dp(2))
            })
            root.addView(toolRow(
                actionButton("1s") { recordWait(1_000) },
                actionButton("3s") { recordWait(3_000) },
                actionButton("5s") { recordWait(5_000) },
                actionButton("10s") { recordWait(10_000) },
            ))

            aiCheck = CheckBox(service).apply {
                val hasKey = OpenRouterSecretStore.hasKey(service)
                text = if (hasKey) "Also optimize with selected OpenRouter model" else "AI optimization unavailable — add OpenRouter key in Cyclone"
                isEnabled = hasKey
                isChecked = hasKey
                setTextColor(Color.rgb(65, 64, 75)); textSize = 12f
                setPadding(0, dp(6), 0, 0)
            }
            root.addView(aiCheck)
            root.addView(toolRow(
                actionButton("Undo") {
                    engine.undo(); updateUi(); rebuildPanel()
                },
                actionButton("Save routine") { saveRoutine() },
                actionButton("Cancel") {
                    engine.cancel(); dismiss()
                },
            ))
        }

        panelParams = overlayParams(dp(340), WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(88)
            y = dp(90)
        }
        panel = root
        wm.addView(root, panelParams)
        updateUi()
    }

    private fun rebuildPanel() {
        hidePanel()
        showPanel()
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null; panelParams = null; status = null; nameField = null; aiCheck = null
    }

    private fun updateUi(message: String? = null) {
        bubble?.text = if (engine.isRecording()) "${engine.stepCount()}\nREC" else "●\nREC"
        status?.text = message ?: if (engine.isRecording()) {
            "Recording · ${engine.stepCount()} steps · normal AI control paused while you teach"
        } else "Ready to teach"
    }

    private fun place(kind: GuidedActionKind, hint: String) {
        if (busy) return
        hidePanel()
        val canvas = PlacementView(service, kind, hint) { placement ->
            capture?.let { runCatching { wm.removeView(it) } }
            capture = null
            setBubbleVisible(false)
            busy = true
            engine.recordPlacement(kind, placement) { result ->
                busy = false
                setBubbleVisible(true)
                showPanel()
                updateUi(result.fold(
                    onSuccess = { "Captured ${kind.name.lowercase()} · ${engine.stepCount()} steps" },
                    onFailure = { "Could not capture step: ${it.message}" },
                ))
            }
        }
        capture = canvas
        wm.addView(canvas, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
        })
    }

    private fun recordWait(ms: Long) {
        if (busy) return
        hidePanel(); setBubbleVisible(false); busy = true
        engine.recordWait(ms) { result ->
            busy = false; setBubbleVisible(true); showPanel()
            updateUi(result.fold({ "Wait ${ms / 1000}s captured · ${engine.stepCount()} steps" }, { "Wait failed: ${it.message}" }))
        }
    }

    private fun recordSystem(kind: GuidedActionKind) {
        if (busy) return
        hidePanel(); setBubbleVisible(false); busy = true
        engine.recordSystem(kind) { result ->
            busy = false; setBubbleVisible(true); showPanel()
            updateUi(result.fold({ "${kind.name.lowercase()} captured · ${engine.stepCount()} steps" }, { "Step failed: ${it.message}" }))
        }
    }

    private fun saveRoutine() {
        if (busy || !engine.isRecording()) return
        val name = nameField?.text?.toString().orEmpty().ifBlank { "Guided workflow" }
        val optimize = aiCheck?.isChecked == true
        val selectedModel = service.getSharedPreferences("cyclone_ai", android.content.Context.MODE_PRIVATE)
            .getString("openrouter_model", OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id)
        hidePanel(); setBubbleVisible(true); busy = true
        bubble?.text = "…\nSAVE"
        engine.finish(name, optimize, selectedModel) { result ->
            busy = false
            result.onSuccess { finished ->
                DeviceState.addLog(
                    "Guided save complete copied=${finished.copiedWorkflow.id} optimized=${finished.optimizedProposal?.id ?: "none"}",
                )
            }
            dismissWithoutCancel()
        }
    }

    private fun dismissWithoutCancel() {
        capture?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        capture = null; panel = null; bubble = null
        panelParams = null; bubbleParams = null
    }

    private fun setBubbleVisible(visible: Boolean) { bubble?.visibility = if (visible) View.VISIBLE else View.INVISIBLE }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(service).apply {
        text = label
        textSize = 11f
        setOnClickListener { action() }
        isAllCaps = false
    }

    private fun toolRow(vararg views: View): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { view -> addView(view, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
    }

    private fun overlayParams(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private inner class BubbleDragListener : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var moved = false
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > dp(8)) moved = true
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                    wm.updateViewLayout(v, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    return true
                }
            }
            return false
        }
    }
}

private class PlacementView(
    context: android.content.Context,
    private val kind: GuidedActionKind,
    private val hint: String,
    private val done: (GuidedPlacement) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(98, 86, 245); strokeWidth = 8f; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 25, 24, 34); style = Paint.Style.FILL; textSize = 40f }
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f; private var dragging = false

    init { setBackgroundColor(Color.argb(22, 0, 0, 0)) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.x; sy = event.y; ex = sx; ey = sy; dragging = true; invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                ex = event.x; ey = event.y; invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                ex = event.x; ey = event.y; dragging = false; invalidate()
                val placement = when (kind) {
                    GuidedActionKind.SWIPE -> GuidedPlacement(sx.toInt(), sy.toInt(), ex.toInt(), ey.toInt(), 350L)
                    GuidedActionKind.HOLD -> GuidedPlacement(ex.toInt(), ey.toInt(), durationMs = 750L)
                    else -> GuidedPlacement(ex.toInt(), ey.toInt())
                }
                done(placement)
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(24f, 36f, width - 24f, 110f, 24f, 24f, fill)
        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f }
        canvas.drawText(hint.take(55), 48f, 84f, hintPaint)
        if (!dragging) return
        if (kind == GuidedActionKind.SWIPE) drawArrow(canvas, sx, sy, ex, ey)
        else {
            canvas.drawCircle(ex, ey, if (kind == GuidedActionKind.HOLD) 44f else 32f, paint)
            canvas.drawCircle(ex, ey, 8f, Paint(paint).apply { style = Paint.Style.FILL })
        }
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        canvas.drawLine(x1, y1, x2, y2, paint)
        canvas.drawCircle(x1, y1, 18f, paint)
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len = 44.0
        val a1 = angle + Math.PI * 0.82
        val a2 = angle - Math.PI * 0.82
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo((x2 + len * cos(a1)).toFloat(), (y2 + len * sin(a1)).toFloat())
            moveTo(x2, y2)
            lineTo((x2 + len * cos(a2)).toFloat(), (y2 + len * sin(a2)).toFloat())
        }
        canvas.drawPath(path, paint)
    }
}
