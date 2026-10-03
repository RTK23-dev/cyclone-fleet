package com.cyclone.mobile.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Debug-only Cyclone-owned surface for repeatable Human Gesture device verification. */
class HumanGestureTestActivity : Activity() {
    private lateinit var status: TextView
    private var largeTapCount = 0
    private var smallTapCount = 0
    private var longPressCount = 0
    private var edgeTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = "HG V0.3 harness ready — no events yet"
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        val largeTap = Button(this).apply {
            text = "Large tap target — count 0"
            contentDescription = "Human Gesture large tap target"
            setOnClickListener {
                largeTapCount++
                text = "Large tap target — count $largeTapCount"
                event("large_tap", null)
            }
        }
        telemetry(largeTap, "large_tap")
        root.addView(largeTap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val smallTap = Button(this).apply {
            text = "Small 0"
            contentDescription = "Human Gesture small tap target"
            setOnClickListener {
                smallTapCount++
                text = "Small $smallTapCount"
                event("small_tap", null)
            }
        }
        telemetry(smallTap, "small_tap")
        row.addView(smallTap, LinearLayout.LayoutParams(dp(92), dp(72)))

        val longPress = Button(this).apply {
            text = "Long press — count 0"
            contentDescription = "Human Gesture long press target"
            isLongClickable = true
            setOnLongClickListener {
                longPressCount++
                text = "Long press — count $longPressCount"
                event("long_press", null)
                true
            }
        }
        telemetry(longPress, "long_press")
        row.addView(longPress, LinearLayout.LayoutParams(0, dp(72), 1f))
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))

        val vertical = ScrollView(this).apply {
            contentDescription = "Human Gesture vertical scroll region"
            isFillViewport = true
        }
        val verticalContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(30) { index ->
            verticalContent.addView(TextView(this).apply {
                text = "Vertical scroll item ${index + 1}"
                textSize = 16f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        }
        vertical.addView(verticalContent)
        vertical.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY) event("vertical_scroll", "y=$scrollY")
        }
        telemetry(vertical, "vertical_scroll_region")
        root.addView(vertical, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))

        val horizontal = HorizontalScrollView(this).apply {
            contentDescription = "Human Gesture horizontal swipe region"
            isFillViewport = true
        }
        val horizontalContent = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(16) { index ->
            horizontalContent.addView(TextView(this).apply {
                text = "H${index + 1}"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                setBackgroundColor(if (index % 2 == 0) 0xffeeeeee.toInt() else 0xffdddddd.toInt())
            }, LinearLayout.LayoutParams(dp(116), dp(132)))
        }
        horizontal.addView(horizontalContent)
        horizontal.setOnScrollChangeListener { _, scrollX, _, oldScrollX, _ ->
            if (scrollX != oldScrollX) event("horizontal_scroll", "x=$scrollX")
        }
        telemetry(horizontal, "horizontal_swipe_region")
        root.addView(horizontal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148)))

        val edgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        edgeRow.addView(TextView(this).apply {
            text = "Edge-near target →"
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(0, dp(84), 1f))
        val edge = Button(this).apply {
            text = "Edge 0"
            contentDescription = "Human Gesture edge near tap target"
            setOnClickListener {
                edgeTapCount++
                text = "Edge $edgeTapCount"
                event("edge_tap", null)
            }
        }
        telemetry(edge, "edge_tap")
        edgeRow.addView(edge, LinearLayout.LayoutParams(dp(104), dp(84)))
        root.addView(edgeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(88)))

        setContentView(root)
    }

    private fun telemetry(view: View, name: String) {
        view.setOnTouchListener { _, motion ->
            if (motion.actionMasked == MotionEvent.ACTION_DOWN || motion.actionMasked == MotionEvent.ACTION_UP) {
                event(name, "${if (motion.actionMasked == MotionEvent.ACTION_DOWN) "down" else "up"} x=${motion.x.toInt()} y=${motion.y.toInt()}")
            }
            false
        }
    }

    private fun event(name: String, detail: String?) {
        status.text = buildString {
            append(name)
            if (!detail.isNullOrBlank()) append(" — ").append(detail)
            append(" | large=").append(largeTapCount)
            append(" small=").append(smallTapCount)
            append(" long=").append(longPressCount)
            append(" edge=").append(edgeTapCount)
        }
    }
}
