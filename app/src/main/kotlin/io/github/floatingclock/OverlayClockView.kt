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
    private val dragFinished: () -> Unit = {},
) : FrameLayout(context), Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val readings = TimeReadings()
    private var formatter = MillisecondTimeFormatter(java.time.ZoneId.of(OverlayState.preferences.zoneId))
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
    private val fontHeight get() = (OverlayState.preferences.fontSizeSp * resources.configuration.fontScale).toInt()
    private val rowHeight get() = (fontHeight - 27).coerceAtLeast(0) + when (OverlayState.config.mode) {
        DisplayMode.FULL -> 100
        DisplayMode.COMPACT -> 66
        DisplayMode.MINIMAL -> 58
    }
    private val contentWidth get() = dp(maxOf(320, (fontHeight * 8.2f).toInt() + 24))
    internal val desiredWidth get() = contentWidth.coerceAtMost(resources.displayMetrics.widthPixels)
    internal val desiredHeight get() = if (menuOpen) dp(520).coerceAtMost((resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(dp(120)))
        else dp(36 + rowHeight * OverlayState.preferences.clockRows.size).coerceAtMost((resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(dp(80)))
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
        applyAppearance()
        isClickable = true
        isFocusable = true
        contentDescription = "悬浮时钟，精度未验证。点击选择手动预设，长按拖动"
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
        // Warnings keep a high-contrast plate even when the clock background is transparent.
        paint.color = Color.rgb(25, 29, 35)
        canvas.drawRect(0f, 0f, width.toFloat(), dp(34).toFloat(), paint)
        paint.color = Color.rgb(255, 205, 100)
        paint.textSize = dp(13).toFloat()
        val status = if (valid) readings.state(PlatformId.TAOBAO_TMALL).statusLabel() else "无可信时间 · 精度未验证"
        canvas.drawText(if (OverlayState.sourceChoice == SourceChoice.DEMO) "演示 · $status" else "${shortSource(OverlayState.timeState)} · $status",
            dp(12).toFloat(), dp(24).toFloat(), paint)
        val config = OverlayState.config
        val scale = minOf(1f, width.toFloat() / contentWidth,
            (height - dp(36)).coerceAtLeast(0).toFloat() / dp(rowHeight * OverlayState.preferences.clockRows.size))
        canvas.save()
        canvas.translate(0f, dp(36).toFloat())
        canvas.scale(scale, scale)
        paint.setShadowLayer(dp(2).toFloat(), 0f, 0f, if (Color.luminance(OverlayState.preferences.textColorArgb) > 0.5f) Color.BLACK else Color.WHITE)
        OverlayState.preferences.clockRows.forEachIndexed { index, row ->
            val platform = row.preset ?: PlatformId.TAOBAO_TMALL
            var y = dp(index * rowHeight)
            run {
                paint.color = OverlayState.preferences.textColorArgb; paint.textSize = dp(14).toFloat()
                canvas.drawText(row.label(), dp(12).toFloat(), (y + dp(16)).toFloat(), paint)
                y += dp(if (config.mode == DisplayMode.MINIMAL) 18 else 22)
            }
            paint.color = OverlayState.preferences.textColorArgb; paint.textSize = dp(fontHeight).toFloat()
            val text = if (valid && readings.hasTime(platform)) formatter.format(if (row.preset == null) readings.shownUtcEpochNanosWithoutPreset(platform) else readings.shownUtcEpochNanos(platform)) else "无可信时间"
            canvas.drawText(text, dp(12).toFloat(), (y + dp(fontHeight + 1)).toFloat(), paint)
            if (config.mode == DisplayMode.FULL) {
                paint.color = OverlayState.preferences.textColorArgb; paint.textSize = dp(12).toFloat()
                canvas.drawText(OverlayState.timeState.sourceLabel(), dp(12).toFloat(), (y + dp(fontHeight + 23)).toFloat(), paint)
            }
        }
        paint.clearShadowLayer()
        canvas.restore()
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
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging) dragFinished()
            dragging = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        menuOpen = !menuOpen
        configurationChanged()
        return true
    }

    internal fun configurationChanged() {
        applyAppearance()
        removeAllViews()
        if (menuOpen) buildMenu()
        requestLayout(); invalidate(); updateFrames(); resize()
    }

    private fun applyAppearance() {
        val preferences = OverlayState.preferences
        formatter = MillisecondTimeFormatter(java.time.ZoneId.of(preferences.zoneId))
        paint.typeface = when (preferences.font) { ClockFont.MONOSPACE -> Typeface.MONOSPACE; ClockFont.SANS -> Typeface.SANS_SERIF; ClockFont.SERIF -> Typeface.SERIF }
        val opacity = if (preferences.style == VisualStyle.DIGITS) 0 else (preferences.backgroundOpacity * 255).toInt()
        val color = (preferences.backgroundColorArgb and 0xffffff) or (opacity shl 24)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color)
            if (preferences.style == VisualStyle.GLASS) {
                // Translucent frosted appearance; no screen capture or hidden backdrop-blur API.
                colors = intArrayOf(color, (color and 0xffffff) or ((opacity * 0.75f).toInt() shl 24))
                setStroke(dp(1), 0x66ffffff)
            }
        }
    }

    internal fun refreshSourceDetails() {
        if (disposed) return
        if (menuOpen) { removeAllViews(); buildMenu() }
        invalidate()
    }

    private fun buildMenu() {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0); setBackgroundColor(Color.rgb(25, 29, 35)) }
        column.addView(TextView(context).apply {
            text = "手动预设（最多三行，含公共时钟）\n${OverlayState.syncDetails}"
            setTextColor(Color.WHITE)
        })
        column.addView(Button(context).apply {
            text = "立即同步（受限频保护）"
            setOnClickListener { OverlayState.onSyncNow?.invoke() }
        })
        val rows = OverlayState.preferences.clockRows
        ClockRow.entries.forEach { choice ->
            column.addView(CheckBox(context).apply {
                text = choice.label(); setTextColor(Color.WHITE)
                isChecked = choice in rows
                isEnabled = (isChecked || rows.size < 3) && !(choice == ClockRow.PUBLIC && isChecked && rows.size == 1)
                setOnClickListener { AppStorage.update { current -> current.copy(clockRows =
                    if (choice in current.clockRows) (current.clockRows - choice).ifEmpty { listOf(ClockRow.PUBLIC) }
                    else (current.clockRows + choice).distinct().take(3)) } }
            })
        }
        rows.forEachIndexed { index, choice ->
            val row = LinearLayout(context)
            row.addView(TextView(context).apply { text = choice.label(); setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            listOf(-1 to "上移", 1 to "下移").forEach { (direction, label) ->
                row.addView(Button(context).apply {
                    text = label; contentDescription = "${choice.label()}$label"
                    isEnabled = index + direction in rows.indices
                    setOnClickListener { AppStorage.update { current ->
                        val ordered = current.clockRows.toMutableList()
                        val from = ordered.indexOf(choice)
                        if (from >= 0 && from + direction in ordered.indices) java.util.Collections.swap(ordered, from, from + direction)
                        current.copy(clockRows = ordered)
                    } }
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
