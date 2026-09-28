package io.github.floatingclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.*
import android.widget.*
import io.github.floatingclock.time.*
import java.util.Locale

/** One overlay window: clock rows are drawn here; the accessible menu is a child view. */
internal class OverlayClockView(
    context: Context,
    private val engine: TimeEngine,
    private val allowed: () -> Boolean,
    private val stop: () -> Unit,
    private val move: (Int, Int) -> Unit,
    private val resize: () -> Unit,
) : FrameLayout(context), Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val readings = TimeReadings()
    private val formatter = MillisecondTimeFormatter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val stats = FrameStats()
    private var disposed = false
    internal var frameScheduled = false
        private set
    internal var menuOpen = false
        private set
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var frameNanos = -1L
    private var lastDrawnFrame = -1L
    private var valid = false
    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val rowHeight get() = when (OverlayState.config.mode) {
        DisplayMode.FULL -> 100
        DisplayMode.COMPACT -> 66
        DisplayMode.MINIMAL -> 42
    }
    internal val desiredWidth get() = dp(320).coerceAtMost(resources.displayMetrics.widthPixels)
    internal val desiredHeight get() = if (menuOpen) dp(520).coerceAtMost((resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(dp(120)))
        else dp(36 + rowHeight * OverlayState.config.platforms.size).coerceAtMost((resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(dp(80)))
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
            if (!menuOpen) performClick()
            return true
        }
        override fun onLongPress(e: android.view.MotionEvent) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    })

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.rgb(25, 29, 35))
        isClickable = true
        isFocusable = true
        contentDescription = "演示时钟，精度未验证。点击选择平台，长按拖动"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateFrames() }
    override fun onDetachedFromWindow() { cancelFrames(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) cancelFrames() else updateFrames()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateFrames(isVisible) }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateFrames() }

    private fun updateFrames(visible: Boolean = isShown && windowVisibility == VISIBLE) {
        if (disposed || !isAttachedToWindow || !visible || menuOpen) cancelFrames()
        else if (!frameScheduled) { frameScheduled = true; choreographer.postFrameCallback(this) }
    }

    private fun cancelFrames() {
        choreographer.removeFrameCallback(this)
        frameScheduled = false
        stats.reset()
        OverlayState.fps = 0.0
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (disposed || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE || menuOpen) { cancelFrames(); return }
        if (!allowed()) { cancelFrames(); stop(); return }
        // One monotonic sample and coherent snapshot for every displayed platform.
        valid = try { engine.readInto(readings); true } catch (_: RuntimeException) { false }
        frameNanos = frameTimeNanos
        invalidate()
        updateFrames()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (menuOpen) return
        val scale = (height.toFloat() / dp(36 + rowHeight * OverlayState.config.platforms.size)).coerceAtMost(1f)
        canvas.scale(scale, scale)
        paint.color = Color.rgb(255, 205, 100)
        paint.textSize = dp(13).toFloat()
        canvas.drawText("演示数据 · 精度未验证", dp(12).toFloat(), dp(24).toFloat(), paint)
        val config = OverlayState.config
        config.platforms.forEachIndexed { index, platform ->
            var y = dp(36 + index * rowHeight)
            if (config.mode != DisplayMode.MINIMAL) {
                paint.color = Color.WHITE; paint.textSize = dp(14).toFloat()
                canvas.drawText(platform.label(), dp(12).toFloat(), (y + dp(16)).toFloat(), paint)
                y += dp(22)
            }
            paint.color = Color.WHITE; paint.textSize = dp(27).toFloat()
            val text = if (valid && readings.hasTime(platform)) formatter.format(readings.shownUtcEpochNanos(platform)) else "无可信时间"
            canvas.drawText(text, dp(12).toFloat(), (y + dp(28)).toFloat(), paint)
            if (config.mode == DisplayMode.FULL) {
                paint.color = Color.LTGRAY; paint.textSize = dp(12).toFloat()
                canvas.drawText("模拟 NTP · 不确定度／误差未知", dp(12).toFloat(), (y + dp(50)).toFloat(), paint)
            }
        }
        if (frameNanos != lastDrawnFrame) {
            lastDrawnFrame = frameNanos
            if (stats.drawn(frameNanos)) OverlayState.fps = stats.fps
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { dragging = false; lastX = event.rawX; lastY = event.rawY }
        gestures.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }
    override fun onInterceptTouchEvent(event: MotionEvent) = dragging

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
            move((event.rawX - lastX).toInt(), (event.rawY - lastY).toInt())
            lastX = event.rawX; lastY = event.rawY
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        menuOpen = !menuOpen
        configurationChanged()
        return true
    }

    internal fun configurationChanged() {
        removeAllViews()
        if (menuOpen) buildMenu()
        requestLayout(); invalidate(); updateFrames(); resize()
    }

    private fun buildMenu() {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        column.addView(TextView(context).apply {
            text = "平台选择（1–3 个）\n演示 NTP · 不确定度／误差未知"
            setTextColor(Color.WHITE)
        })
        val config = OverlayState.config
        PlatformId.entries.forEach { platform ->
            column.addView(CheckBox(context).apply {
                text = platform.label(); setTextColor(Color.WHITE)
                isChecked = platform in config.platforms
                isEnabled = if (isChecked) config.platforms.size > 1 else config.platforms.size < 3
                setOnClickListener { OverlayState.configure(OverlayState.config.toggle(platform)) }
            })
        }
        config.platforms.forEachIndexed { index, platform ->
            val row = LinearLayout(context)
            row.addView(TextView(context).apply { text = platform.label(); setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            listOf(-1 to "上移", 1 to "下移").forEach { (direction, label) ->
                row.addView(Button(context).apply {
                    text = label; contentDescription = "${platform.label()}$label"
                    isEnabled = index + direction in config.platforms.indices
                    setOnClickListener { OverlayState.configure(OverlayState.config.move(platform, direction)) }
                })
            }
            column.addView(row)
        }
        column.addView(Button(context).apply { text = "关闭菜单"; setOnClickListener { performClickOnClock() } })
        val scroll = ScrollView(context).apply { addView(column) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun performClickOnClock() { performClick() }
    internal fun dispose() {
        disposed = true
        cancelFrames()
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        gestures.onTouchEvent(cancel)
        cancel.recycle()
        removeAllViews()
    }
}