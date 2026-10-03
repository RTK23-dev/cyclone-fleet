package com.cyclone.mobile.guided

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.OpenRouterCustomModelStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.applearner.discardFollowMeSession
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cyclone 2.9.1 unified Follow Me + exact-step teaching overlay.
 *
 * The bubble remains visible while learning and exposes Pause, Stop & review and Cancel & discard.
 * Normal navigation teaches semantic pages; exact Tap/Hold/Swipe/Check placements are optional.
 */
object RoutineTeachingOverlayRuntime {
    @Volatile private var controller: RoutineTeachingOverlayController? = null

    fun show(service: CycloneAccessibilityService, session: RoutineTeachingSession) {
        controller?.dismiss(cancelGuided = true)
        controller = RoutineTeachingOverlayController(service, session).also { it.show() }
    }

    fun isShowing(): Boolean = controller != null

    fun requestStop() {
        controller?.stopAndSave() ?: FollowMeLearnerRuntime.finishFromOverlay(null, null)
    }

    fun dismiss() {
        controller?.dismiss(cancelGuided = true)
        controller = null
    }

    internal fun cleared(instance: RoutineTeachingOverlayController) {
        if (controller === instance) controller = null
    }
}

class RoutineTeachingOverlayController(
    private val service: CycloneAccessibilityService,
    initialSession: RoutineTeachingSession,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val engine = GuidedRecorderEngine(service)
    private var sessionName = initialSession.name
    private var modelId = initialSession.modelId
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var capture: View? = null
    private var status: TextView? = null
    private var modelSpinner: Spinner? = null
    private var holdSpinner: Spinner? = null
    private var aiCheck: CheckBox? = null
    private var busy = false
    private var stopping = false

    private val ticker = object : Runnable {
        override fun run() {
            if (bubble == null) return
            updateStatus()
            handler.postDelayed(this, 650)
        }
    }

    fun show() {
        if (bubble != null) return
        engine.start(sessionName)
        bubbleParams = overlayParams(dp(70), dp(70), focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(165)
        }
        bubble = TextView(service).apply {
            text = "LEARN\n●"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11.5f
            background = rounded(Color.rgb(84, 72, 230), dp(35).toFloat())
            elevation = dp(12).toFloat()
            setOnTouchListener(BubbleDragListener())
        }
        wm.addView(bubble, bubbleParams)
        handler.post(ticker)
        DeviceState.addLog("Cyclone 2.9.1 unified teaching overlay opened")
    }

    fun stopAndSave() {
        if (stopping) return
        stopping = true
        busy = true
        updateStatus("Saving routine and building report…")
        bubble?.text = "SAVE\n…"
        val copied = engine.stepCount() > 0
        if (!copied) {
            engine.cancel()
            finishFollowMe(null, null)
            return
        }
        val optimize = aiCheck?.isChecked == true && OpenRouterSecretStore.hasKey(service)
        engine.finish(sessionName, optimize, modelId) { result ->
            val finished = result.getOrNull()
            finishFollowMe(finished?.copiedWorkflow?.id, finished?.optimizedProposal?.id)
        }
    }

    fun dismiss(cancelGuided: Boolean) {
        handler.removeCallbacks(ticker)
        capture?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        capture = null; panel = null; bubble = null
        if (cancelGuided && engine.isRecording()) engine.cancel()
        RoutineTeachingOverlayRuntime.cleared(this)
    }

    private fun finishFollowMe(copiedAutomationId: String?, optimizedAutomationId: String?) {
        dismiss(cancelGuided = false)
        FollowMeLearnerRuntime.finishFromOverlay(copiedAutomationId, optimizedAutomationId)
    }

    private fun togglePanel() {
        if (panel == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(249, 249, 253), dp(24).toFloat())
            elevation = dp(16).toFloat()
        }
        root.addView(TextView(service).apply {
            text = "Cyclone is learning"
            setTextColor(Color.rgb(25, 24, 34)); textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(service).apply {
            text = "Use your phone normally, or teach an exact step below. Cyclone stores one semantic page at a time, the UI controls, the Android actions behind them, and a screenshot timeline."
            setTextColor(Color.rgb(74, 72, 84)); textSize = 12.2f
            setPadding(0, dp(4), 0, dp(8))
        })

        status = TextView(service).apply {
            setTextColor(Color.rgb(58, 56, 72)); textSize = 12f
            setPadding(0, dp(2), 0, dp(7))
        }
        root.addView(status)

        root.addView(TextView(service).apply {
            text = "Teaching model"
            setTextColor(Color.rgb(40, 39, 50)); textSize = 11.5f
        })
        val models = OpenRouterCustomModelStore.all(service)
        modelSpinner = Spinner(service).apply {
            adapter = ArrayAdapter(service, android.R.layout.simple_spinner_dropdown_item, models.map { it.label })
            val selected = models.indexOfFirst { it.id == modelId }.takeIf { it >= 0 } ?: 0
            setSelection(selected)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    modelId = models.getOrNull(position)?.id ?: modelId
                    RoutineTeachingRuntime.updateModel(modelId)
                }
            }
        }
        root.addView(modelSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        val progress = FollowMeLearnerRuntime.progress()
        root.addView(toolRow(
            actionButton(if (progress.paused) "Resume" else "Pause") {
                if (FollowMeLearnerRuntime.progress().paused) FollowMeLearnerRuntime.resume() else FollowMeLearnerRuntime.pause()
                rebuildPanel()
            },
            actionButton("History") { RoutineTeachingRuntime.launchReport(service, null) },
            actionButton("Stop & review") { stopAndSave() },
        ))
        root.addView(actionButton("Cancel & discard") {
            if (!busy && !stopping) discardFollowMeSession(service)
        })
        root.addView(TextView(service).apply {
            text = "Cancel & discard exits Follow Me without a review page, workflow compilation or post-session model analysis."
            setTextColor(Color.rgb(95, 92, 106)); textSize = 10.5f
            setPadding(0, 0, 0, dp(5))
        })

        root.addView(TextView(service).apply {
            text = "Guide an exact step"
            setTextColor(Color.rgb(40, 39, 50)); textSize = 12f
            setPadding(0, dp(8), 0, dp(2))
        })
        root.addView(toolRow(
            actionButton("Tap") { place(GuidedActionKind.TAP) },
            actionButton("Hold") { place(GuidedActionKind.HOLD) },
            actionButton("Swipe") { place(GuidedActionKind.SWIPE) },
            actionButton("Check") { place(GuidedActionKind.ASSERT) },
        ))

        root.addView(TextView(service).apply {
            text = "Hold duration (demonstration only — Cyclone prefers native long-click when available)"
            setTextColor(Color.rgb(72, 70, 82)); textSize = 10.8f
            setPadding(0, dp(5), 0, 0)
        })
        val holdOptions = listOf("0.5 seconds", "1 second", "2 seconds", "3 seconds")
        holdSpinner = Spinner(service).apply {
            adapter = ArrayAdapter(service, android.R.layout.simple_spinner_dropdown_item, holdOptions)
            setSelection(2)
        }
        root.addView(holdSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        root.addView(TextView(service).apply {
            text = "Wait / system steps"
            setTextColor(Color.rgb(40, 39, 50)); textSize = 12f
            setPadding(0, dp(5), 0, dp(2))
        })
        root.addView(toolRow(
            actionButton("1s") { recordWait(1_000) },
            actionButton("3s") { recordWait(3_000) },
            actionButton("5s") { recordWait(5_000) },
            actionButton("Back") { recordSystem(GuidedActionKind.BACK) },
            actionButton("Home") { recordSystem(GuidedActionKind.HOME) },
        ))

        aiCheck = CheckBox(service).apply {
            val hasKey = OpenRouterSecretStore.hasKey(service)
            text = if (hasKey) "Optimize explicit steps with selected model when I stop" else "Add an OpenRouter key to enable model optimization"
            isEnabled = hasKey
            isChecked = hasKey
            setTextColor(Color.rgb(66, 64, 76)); textSize = 11f
        }
        root.addView(aiCheck)
        root.addView(TextView(service).apply {
            text = "Tip: for ordinary taps just use your phone. Use the tools above when timing, a precise target, a hold, swipe or checkpoint matters."
            setTextColor(Color.rgb(95, 92, 106)); textSize = 10.8f
        })

        panel = root
        wm.addView(root, overlayParams(dp(360), WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(82)
            y = dp(62)
        })
        updateStatus()
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null; status = null; modelSpinner = null; holdSpinner = null; aiCheck = null
    }

    private fun rebuildPanel() { hidePanel(); showPanel() }

    private fun updateStatus(message: String? = null) {
        val p = FollowMeLearnerRuntime.progress()
        val session = RoutineTeachingRuntime.activeSession()
        bubble?.text = if (stopping) "SAVE\n…" else if (p.paused) "LEARN\nⅡ" else "LEARN\n${session?.steps?.size ?: 0}"
        status?.text = message ?: buildString {
            append(if (p.paused) "Paused" else "Following you")
            append(" · ${p.appsSeen} apps · ${p.screensSeen} pages · ${p.actionsSeen} actions")
            if (p.currentScreen.isNotBlank()) append("\n${p.currentApp}: ${p.currentScreen}")
            if (p.message.isNotBlank()) append("\n${p.message}")
        }
    }

    private fun place(kind: GuidedActionKind) {
        if (busy || stopping) return
        val duration = if (kind == GuidedActionKind.HOLD) holdDurationMs() else null
        hidePanel()
        setBubbleVisible(false)
        val hint = when (kind) {
            GuidedActionKind.TAP -> "Tap the exact control Cyclone should use"
            GuidedActionKind.HOLD -> "Place the hold target"
            GuidedActionKind.SWIPE -> "Drag from the start to the end of the swipe"
            GuidedActionKind.ASSERT -> "Tap the element Cyclone should verify"
            else -> "Place the step"
        }
        val canvas = TeachingPlacementView(service, kind, hint, duration) { placement ->
            capture?.let { runCatching { wm.removeView(it) } }
            capture = null
            busy = true
            engine.recordPlacement(kind, placement) { result ->
                result.getOrNull()?.let(RoutineTeachingRuntime::recordGuidedStep)
                busy = false
                setBubbleVisible(true)
                showPanel()
                updateStatus(result.fold({ "Captured ${kind.name.lowercase()} with UI evidence" }, { "Could not capture step: ${it.message}" }))
            }
        }
        capture = canvas
        wm.addView(canvas, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
        })
    }

    private fun recordWait(ms: Long) {
        if (busy || stopping) return
        busy = true; hidePanel(); setBubbleVisible(false)
        engine.recordWait(ms) { result ->
            result.getOrNull()?.let(RoutineTeachingRuntime::recordGuidedStep)
            busy = false; setBubbleVisible(true); showPanel()
            updateStatus(result.fold({ "Captured ${ms / 1000}s wait — future replay can replace it with a page condition" }, { "Wait capture failed: ${it.message}" }))
        }
    }

    private fun recordSystem(kind: GuidedActionKind) {
        if (busy || stopping) return
        busy = true; hidePanel(); setBubbleVisible(false)
        engine.recordSystem(kind) { result ->
            result.getOrNull()?.let(RoutineTeachingRuntime::recordGuidedStep)
            busy = false; setBubbleVisible(true); showPanel()
            updateStatus(result.fold({ "Captured ${kind.name.lowercase()}" }, { "Capture failed: ${it.message}" }))
        }
    }

    private fun holdDurationMs(): Long = when (holdSpinner?.selectedItemPosition ?: 2) {
        0 -> 500L
        1 -> 1_000L
        3 -> 3_000L
        else -> 2_000L
    }

    private fun setBubbleVisible(visible: Boolean) { bubble?.visibility = if (visible) View.VISIBLE else View.INVISIBLE }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(service).apply {
        text = label; textSize = 10.5f; isAllCaps = false
        setOnClickListener { action() }
    }

    private fun toolRow(vararg views: View): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
    }

    private fun overlayParams(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, android.graphics.PixelFormat.TRANSLUCENT)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private inner class BubbleDragListener : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var moved = false
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > dp(8)) moved = true
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); wm.updateViewLayout(v, params); return true
                }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(); return true }
            }
            return false
        }
    }
}

private class TeachingPlacementView(
    context: android.content.Context,
    private val kind: GuidedActionKind,
    private val hint: String,
    private val holdDurationMs: Long?,
    private val done: (GuidedPlacement) -> Unit,
) : View(context) {
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(91, 78, 235); strokeWidth = 8f; style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 24, 23, 33); style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 31f }
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f; private var dragging = false

    init { setBackgroundColor(Color.argb(24, 0, 0, 0)) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = event.x; sy = event.y; ex = sx; ey = sy; dragging = true; invalidate(); return true }
            MotionEvent.ACTION_MOVE -> { ex = event.x; ey = event.y; invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                ex = event.x; ey = event.y; dragging = false; invalidate()
                done(when (kind) {
                    GuidedActionKind.SWIPE -> GuidedPlacement(sx.toInt(), sy.toInt(), ex.toInt(), ey.toInt(), 350L)
                    GuidedActionKind.HOLD -> GuidedPlacement(ex.toInt(), ey.toInt(), durationMs = holdDurationMs ?: 2_000L)
                    else -> GuidedPlacement(ex.toInt(), ey.toInt())
                })
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(22f, 34f, width - 22f, 112f, 24f, 24f, shade)
        canvas.drawText(hint.take(62), 44f, 83f, textPaint)
        if (!dragging) return
        if (kind == GuidedActionKind.SWIPE) drawArrow(canvas, sx, sy, ex, ey)
        else {
            canvas.drawCircle(ex, ey, if (kind == GuidedActionKind.HOLD) 46f else 34f, accent)
            canvas.drawCircle(ex, ey, 8f, Paint(accent).apply { style = Paint.Style.FILL })
        }
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        canvas.drawLine(x1, y1, x2, y2, accent)
        canvas.drawCircle(x1, y1, 18f, accent)
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len = 46.0
        val a1 = angle + Math.PI * .82
        val a2 = angle - Math.PI * .82
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo((x2 + len * cos(a1)).toFloat(), (y2 + len * sin(a1)).toFloat())
            moveTo(x2, y2)
            lineTo((x2 + len * cos(a2)).toFloat(), (y2 + len * sin(a2)).toFloat())
        }
        canvas.drawPath(path, accent)
    }
}
